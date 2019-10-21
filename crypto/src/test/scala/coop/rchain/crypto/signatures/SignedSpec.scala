package coop.rchain.crypto.signatures

import coop.rchain.crypto.hash.Blake2b256
import coop.rchain.shared.Serialize
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Matchers, PropSpec}
import scodec.bits.ByteVector

class SignedSpec extends PropSpec with GeneratorDrivenPropertyChecks with Matchers {
  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  implicit val serializable: Serialize[Array[Byte]] = new Serialize[Array[Byte]] {
    override def encode(bytes: Array[Byte]): ByteVector = ByteVector(bytes)

    override def decode(bytes: ByteVector): Either[Throwable, Array[Byte]] = Right(bytes.toArray)
  }

  implicit val sigAlgorithmArbitrary: Arbitrary[SignaturesAlg] = Arbitrary(
    Gen.oneOf(Secp256k1, Ed25519)
  )

  property("Signed should generate a valid signature") {
    forAll { (sigAlgorithm: SignaturesAlg, input: Array[Byte]) =>
      val (sk, pk) = sigAlgorithm.newKeyPair

      val signed = Signed(input, sigAlgorithm, sk)

      val hash = Blake2b256.hash(signed.data)

      sigAlgorithm.verify(hash, signed.sig.toByteArray, pk) should be(true)
    }
  }
}