package coop.rchain.casper

import cats.Applicative
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.casper.Estimator.{BlockHash, Validator}
import coop.rchain.casper.Validate.ignore
import coop.rchain.casper.protocol.{BlockMessage, Justification}
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.crypto.hash.Blake2b256
import coop.rchain.crypto.signatures.Ed25519
import coop.rchain.p2p.effects.Log

import scala.collection.mutable
import scala.util.Try

object Validate {
  def ignore(b: BlockMessage, reason: String): String =
    s"CASPER: Ignoring block ${PrettyPrinter.buildString(b.blockHash)} because $reason"

  def missingBlocks[F[_]: Applicative: Log](block: BlockMessage, dag: BlockDag): F[Boolean] = {
    val parentsPresent = ProtoUtil.parents(block).forall(p => dag.blockLookup.contains(p))
    val justificationsPresent =
      block.justifications.forall(j => dag.blockLookup.contains(j.latestBlockHash))
    if (parentsPresent && justificationsPresent) {
      true.pure[F]
    } else {
      for {
        _ <- Log[F].debug(
              s"Fetching missing dependencies for ${PrettyPrinter.buildString(block.blockHash)}.")
      } yield false
    }
  }

  def blockSignature[F[_]: Applicative: Log](b: BlockMessage): F[Boolean] =
    if (b.sigAlgorithm == "ed25519") {
      val justificationHash = ProtoUtil.protoSeqHash(b.justifications)
      val sigData =
        Blake2b256.hash(justificationHash.toByteArray ++ b.blockHash.toByteArray)
      val isValid =
        Try(Ed25519.verify(sigData, b.sig.toByteArray, b.sender.toByteArray)).getOrElse(false)

      if (isValid) {
        true.pure[F]
      } else {
        for {
          _ <- Log[F].warn(ignore(b, "signature is invalid."))
        } yield false
      }
    } else {
      for {
        _ <- Log[F].warn(ignore(b, s"signature algorithm ${b.sigAlgorithm} is unsupported."))
      } yield false
    }

  def blockNumber[F[_]: Applicative: Log](b: BlockMessage, dag: BlockDag): F[Boolean] = {
    val parentNumber = ProtoUtil
      .parents(b)
      .headOption
      .map(dag.blockLookup andThen ProtoUtil.blockNumber)
    val number = ProtoUtil.blockNumber(b)
    val result = parentNumber.fold(number == 0)(_ + 1 == number)

    if (result) {
      true.pure[F]
    } else {
      val log = parentNumber.fold(
        Log[F].warn(
          ignore(b, s"block number $number is not zero, but block has no parents.")
        )
      )(n => {
        Log[F].warn(
          ignore(b, s"block number $number is not one more than parent number $n.")
        )
      })
      for {
        _ <- log
      } yield false
    }
  }

  // TODO: Double check ordering of validity checks
  def sequenceNumber[F[_]: Applicative: Log](b: BlockMessage, dag: BlockDag): F[Boolean] = {
    val creatorJustificationSeqNumber = b.justifications
      .find {
        case Justification(validator, _) => validator == b.sender
      }
      .fold(-1) {
        case Justification(_, latestBlockHash) => dag.blockLookup(latestBlockHash).seqNum
      }
    val number = b.seqNum
    val result = creatorJustificationSeqNumber + 1 == number

    if (result) {
      true.pure[F]
    } else {
      for {
        _ <- Log[F].warn(ignore(
              b,
              s"seq number $number is not one more than creator justification number $creatorJustificationSeqNumber."))
      } yield false
    }
  }

  def blockSender[F[_]: Applicative: Log](b: BlockMessage,
                                          genesis: BlockMessage,
                                          dag: BlockDag): F[Boolean] =
    if (b == genesis) {
      true.pure[F] //genesis block has a valid sender
    } else {
      val weight = ProtoUtil.weightFromSender(b, dag.blockLookup)
      if (weight > 0)
        true.pure[F]
      else
        for {
          _ <- Log[F].warn(
                ignore(b, s"block creator ${PrettyPrinter.buildString(b.sender)} has 0 weight."))
        } yield false
    }

  def parents[F[_]: Applicative: Log](b: BlockMessage,
                                      genesis: BlockMessage,
                                      dag: BlockDag): F[Boolean] = {
    val bParents = b.header.fold(Seq.empty[ByteString])(_.parentsHashList)

    if (b.justifications.isEmpty) {
      if (bParents.exists(_ != genesis.blockHash))
        for {
          _ <- Log[F].warn(ignore(b, "justification is empty, but block has non-genesis parents."))
        } yield false
      else
        true.pure[F]
    } else {
      val latestMessages = b.justifications
        .foldLeft(Map.empty[Validator, BlockHash]) {
          case (map, Justification(v, hash)) => map.updated(v, hash)
        }
      val viewDag     = dag.copy(latestMessages = latestMessages)
      val estimate    = Estimator.tips(viewDag, genesis)
      val trueParents = ProtoUtil.chooseNonConflicting(estimate, genesis, dag).map(_.blockHash)

      if (bParents == trueParents)
        true.pure[F]
      else
        for {
          _ <- Log[F].warn(
                ignore(b, "block parents did not match estimate based on justification."))
        } yield false
    }
  }
}
