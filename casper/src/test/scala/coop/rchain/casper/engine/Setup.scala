package coop.rchain.casper.engine

import cats._
import cats.implicits._
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift}
import cats.temp.par
import coop.rchain.blockstorage._
import coop.rchain.blockstorage.dag.{BlockDagRepresentation, InMemBlockDagStorage}
import coop.rchain.blockstorage.finality.LastFinalizedMemoryStorage
import coop.rchain.casper._
import coop.rchain.casper.genesis.contracts.{Validator, Vault}
import coop.rchain.casper.helper.BlockDagStorageTestFixture
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.casper.util.{GenesisBuilder, TestTime}
import coop.rchain.catscontrib.ApplicativeError_
import coop.rchain.catscontrib.TaskContrib._
import coop.rchain.comm._
import coop.rchain.comm.rp.Connect.{Connections, ConnectionsCell}
import coop.rchain.metrics.{Metrics, NoopSpan, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.p2p.EffectsTestInstances._
import coop.rchain.rholang.interpreter.Runtime
import coop.rchain.rholang.interpreter.util.RevAddress
import coop.rchain.shared.Cell
import monix.eval.Task
import monix.execution.Scheduler

object Setup {
  def apply() = new {
    implicit val log              = new LogStub[Task]
    implicit val eventLogStub     = new EventLogStub[Task]
    implicit val metrics          = new Metrics.MetricsNOP[Task]
    implicit val span: Span[Task] = NoopSpan[Task]()
    val networkId                 = "test"
    val scheduler                 = Scheduler.io("test")
    val runtimeDir                = BlockDagStorageTestFixture.blockStorageDir
    val (space, replay, _) = {
      implicit val s = scheduler
      Runtime.setupRSpace[Task](runtimeDir, 3024L * 1024).unsafeRunSync
    }
    val activeRuntime =
      Runtime
        .createWithEmptyCost[Task]((space, replay))(
          Concurrent[Task],
          log,
          metrics,
          span,
          par.Par[Task]
        )
        .unsafeRunSync(scheduler)

    implicit val runtimeManager = RuntimeManager.fromRuntime(activeRuntime).unsafeRunSync(scheduler)

    val params @ (_, genesisParams) = GenesisBuilder.buildGenesisParameters()
    val context                     = GenesisBuilder.buildGenesis(params)

    val (validatorSk, validatorPk) = context.validatorKeyPairs.head
    val bonds                      = genesisParams.proofOfStake.validators.flatMap(Validator.unapply).toMap
    val requiredSigs               = 1
    val shardId                    = genesisParams.shardId
    val finalizationRate           = 1
    val deployTimestamp            = genesisParams.timestamp

    val genesis: BlockMessage = context.genesisBlock

    val validatorId = ValidatorIdentity(validatorPk, validatorSk, "secp256k1")
    val bap = BlockApproverProtocol
      .of[Task](
        validatorId,
        deployTimestamp,
        Traverse[List]
          .traverse(genesisParams.proofOfStake.validators.map(_.pk).toList)(
            RevAddress.fromPublicKey
          )
          .get
          .map(Vault(_, 0L)),
        bonds,
        genesisParams.proofOfStake.minimumBond,
        genesisParams.proofOfStake.maximumBond,
        genesisParams.proofOfStake.epochLength,
        genesisParams.proofOfStake.quarantineLength,
        genesisParams.proofOfStake.numberOfActiveValidators,
        requiredSigs
      )
      .unsafeRunSync(monix.execution.Scheduler.Implicits.global)

    val local: PeerNode = peerNode("src", 40400)

    implicit val nodeDiscovery = new NodeDiscoveryStub[Task]
    implicit val connectionsCell: ConnectionsCell[Task] =
      Cell.unsafe[Task, Connections](List(local))
    implicit val transportLayer = new TransportLayerStub[Task]
    implicit val rpConf         = createRPConfAsk[Task](local)
    implicit val time           = TestTime.instance
    implicit val currentRequests: Running.RequestedBlocks[Task] =
      Cell.unsafe[Task, Map[BlockHash, Running.Requested]](Map.empty[BlockHash, Running.Requested])
    implicit val commUtil = CommUtil.of[Task]
    implicit val errHandler =
      ApplicativeError_.applicativeError(new ApplicativeError[Task, CommError] {
        override def raiseError[A](e: CommError): Task[A] =
          Task.raiseError(new Exception(s"CommError: $e"))
        override def handleErrorWith[A](fa: Task[A])(f: CommError => Task[A]): Task[A] =
          fa.onErrorHandleWith(th => f(UnknownCommError(th.getMessage)))
        override def pure[A](x: A): Task[A]                           = Task.pure(x)
        override def ap[A, B](ff: Task[A => B])(fa: Task[A]): Task[B] = Applicative[Task].ap(ff)(fa)
      })
    implicit val lab =
      LastApprovedBlock.of[Task].unsafeRunSync(monix.execution.Scheduler.Implicits.global)
    implicit val blockMap         = Ref.unsafe[Task, Map[BlockHash, BlockMessageProto]](Map.empty)
    implicit val approvedBlockRef = Ref.unsafe[Task, Option[ApprovedBlock]](None)
    implicit val blockStore       = InMemBlockStore.create[Task]
    implicit val blockDagStorage = InMemBlockDagStorage
      .create[Task]
      .unsafeRunSync(monix.execution.Scheduler.Implicits.global)
    implicit val lastFinalizedStorage = LastFinalizedMemoryStorage
      .make[Task]
      .unsafeRunSync(monix.execution.Scheduler.Implicits.global)
    implicit val safetyOracle = new SafetyOracle[Task] {
      override def normalizedFaultTolerance(
          blockDag: BlockDagRepresentation[Task],
          estimateBlockHash: BlockHash
      ): Task[Float] = Task.pure(1.0f)
    }
    implicit val lastFinalizedBlockCalculator = LastFinalizedBlockCalculator[Task](0f)
    implicit val synchronyConstraintChecker   = SynchronyConstraintChecker[Task](0d)
    implicit val estimator                    = Estimator[Task](Estimator.UnlimitedParents)
  }
  private def endpoint(port: Int): Endpoint = Endpoint("host", port, port)

  private def peerNode(name: String, port: Int): PeerNode =
    PeerNode(NodeIdentifier(name.getBytes), endpoint(port))

}
