package models

import com.google.common.primitives.{Bytes, Longs}
import io.circe.{Decoder, Encoder, HCursor}
import io.circe.syntax._
import org.encryfoundation.prismlang.compiler.CompiledContract
import org.encryfoundation.prismlang.core.wrapped.BoxedValue
import crypto.{PrivateKey25519, PublicKey25519, Signature25519}
import models.box.AssetBox
import models.directives.{Directive, ScriptedAssetDirective, TransferDirective}
import scorex.crypto.authds.ADKey
import scorex.crypto.encode.Base58
import scorex.crypto.hash.{Blake2b256, Digest32}

/** Completely assembled atomic state modifier. */
case class EncryTransaction(fee: Long,
                            timestamp: Long,
                            inputs: IndexedSeq[Input],
                            directives: IndexedSeq[Directive],
                            defaultProofOpt: Option[Proof]) {

  val messageToSign: Array[Byte] = UnsignedEncryTransaction.bytesToSign(fee, timestamp, inputs, directives)

  lazy val id: Array[Byte] = Blake2b256.hash(messageToSign)
}

object EncryTransaction {

  implicit val jsonEncoder: Encoder[EncryTransaction] = (tx: EncryTransaction) => Map(
    "id" -> Base58.encode(tx.id).asJson,
    "fee" -> tx.fee.asJson,
    "timestamp" -> tx.timestamp.asJson,
    "inputs" -> tx.inputs.map(_.asJson).asJson,
    "directives" -> tx.directives.map(_.asJson).asJson,
    "defaultProofOpt" -> tx.defaultProofOpt.map(_.asJson).asJson
  ).asJson

  implicit val jsonDecoder: Decoder[EncryTransaction] = (c: HCursor) => {
    for {
      fee <- c.downField("fee").as[Long]
      timestamp <- c.downField("timestamp").as[Long]
      inputs <- c.downField("inputs").as[IndexedSeq[Input]]
      directives <- c.downField("directives").as[IndexedSeq[Directive]]
      defaultProofOpt <- c.downField("defaultProofOpt").as[Option[Proof]]
    } yield EncryTransaction(
        fee,
        timestamp,
        inputs,
        directives,
        defaultProofOpt
      )
  }
}

/** Unsigned version of EncryTransaction (without any
  * proofs for which interactive message is required) */
case class UnsignedEncryTransaction(fee: Long,
                                    timestamp: Long,
                                    inputs: IndexedSeq[Input],
                                    directives: IndexedSeq[Directive]) {

  val messageToSign: Array[Byte] = UnsignedEncryTransaction.bytesToSign(fee, timestamp, inputs, directives)

  def toSigned(proofs: IndexedSeq[Seq[Proof]], defaultProofOpt: Option[Proof]): EncryTransaction = {
    val signedInputs: IndexedSeq[Input] = inputs.zip(proofs).map{case (input, proof) => input.copy(proofs = proof.toList)} ++
      inputs.drop(proofs.length)
    EncryTransaction(fee, timestamp, signedInputs, directives, defaultProofOpt)
  }
}

object UnsignedEncryTransaction {

  def bytesToSign(fee: Long,
                  timestamp: Long,
                  inputs: IndexedSeq[Input],
                  directives: IndexedSeq[Directive]): Digest32 =
    Blake2b256.hash(Bytes.concat(
      inputs.flatMap(_.bytesWithoutProof).toArray,
      directives.flatMap(_.bytes).toArray,
      Longs.toByteArray(timestamp),
      Longs.toByteArray(fee)
    ))
}

object Transaction {

  def defaultPaymentTransactionScratch(privKey: PrivateKey25519,
                                       fee: Long,
                                       timestamp: Long,
                                       useBoxes: IndexedSeq[AssetBox],
                                       recipient: String,
                                       amount: Long,
                                       tokenIdOpt: Option[ADKey] = None): EncryTransaction = {
    val pubKey: PublicKey25519 = privKey.publicImage
    val uInputs: IndexedSeq[Input] = useBoxes.map(bx => Input.unsigned(bx.id, AccountLockedContract(Account(pubKey.address)))).toIndexedSeq
    val change: Long = useBoxes.map(_.value).sum - (amount + fee)
    val directives: IndexedSeq[TransferDirective] = if (change > 0) {
      IndexedSeq(TransferDirective(recipient, amount, tokenIdOpt), TransferDirective(pubKey.address, change, tokenIdOpt))
    } else {
      IndexedSeq(TransferDirective(recipient, amount, tokenIdOpt))
    }

    val uTransaction: UnsignedEncryTransaction = UnsignedEncryTransaction(fee, timestamp, uInputs, directives)
    val signature: Signature25519 = privKey.sign(uTransaction.messageToSign)

    uTransaction.toSigned(IndexedSeq.empty, Some(Proof(BoxedValue.Signature25519Value(signature.bytes.toList))))
  }

  def scriptedAssetTransactionScratch(privKey: PrivateKey25519,
                                      fee: Long,
                                      timestamp: Long,
                                      useBoxes: IndexedSeq[AssetBox],
                                      contract: CompiledContract,
                                      amount: Long,
                                      tokenIdOpt: Option[ADKey] = None): EncryTransaction = {
    val pubKey: PublicKey25519 = privKey.publicImage
    val uInputs: IndexedSeq[Input] = useBoxes.map(bx => Input.unsigned(bx.id, AccountLockedContract(Account(pubKey.address)))).toIndexedSeq
    val change: Long = useBoxes.map(_.value).sum - (amount + fee)
    val assetDirective: ScriptedAssetDirective = ScriptedAssetDirective(contract.hash, amount, tokenIdOpt)
    val directives: IndexedSeq[Directive] =
      if (change > 0) IndexedSeq(assetDirective, TransferDirective(pubKey.address, change, tokenIdOpt))
      else IndexedSeq(assetDirective)

    val uTransaction: UnsignedEncryTransaction = UnsignedEncryTransaction(fee, timestamp, uInputs, directives)
    val signature: Signature25519 = privKey.sign(uTransaction.messageToSign)

    uTransaction.toSigned(IndexedSeq.empty, Some(Proof(BoxedValue.Signature25519Value(signature.bytes.toList))))
  }

  def specialTransactionScratch(privKey: PrivateKey25519,
                                fee: Long,
                                timestamp: Long,
                                useBoxes: IndexedSeq[ADKey],
                                recipient: String,
                                amount: Long,
                                change: Long,
                                tokenIdOpt: Option[ADKey] = None): EncryTransaction = {
    val pubKey: PublicKey25519 = privKey.publicImage
    val uInputs: IndexedSeq[Input] = useBoxes.map(id => Input.unsigned(id, AccountLockedContract(Account(pubKey.address)))).toIndexedSeq
    val transferDirective: TransferDirective = TransferDirective(recipient, amount, tokenIdOpt)
    val directives: IndexedSeq[TransferDirective] =
      if (change > 0) IndexedSeq(transferDirective, TransferDirective(pubKey.address, change, tokenIdOpt))
      else IndexedSeq(transferDirective)

    val uTransaction: UnsignedEncryTransaction = UnsignedEncryTransaction(fee, timestamp, uInputs, directives)
    val signature: Signature25519 = privKey.sign(uTransaction.messageToSign)

    uTransaction.toSigned(IndexedSeq.empty, Some(Proof(BoxedValue.Signature25519Value(signature.bytes.toList))))
  }
}