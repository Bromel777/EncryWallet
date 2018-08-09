package models.directives

import scala.util.Try
import com.google.common.primitives.{Bytes, Ints, Longs}
import models.directives.Directive.DTypeId
import org.encryfoundation.common.serialization.Serializer
import models.box.Box.Amount
import models.box.{AssetBox, EncryBaseBox, EncryProposition}
import org.encryfoundation.common.{Algos, Constants}
import org.encryfoundation.common.utils.Utils
import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor}
import org.encryfoundation.common.transaction.EncryAddress
import org.encryfoundation.common.transaction.EncryAddress.Address
import scorex.crypto.authds
import scorex.crypto.authds.ADKey
import scorex.crypto.hash.Digest32
import supertagged.@@

case class TransferDirective(address: Address,
                             amount: Amount,
                             tokenIdOpt: Option[ADKey] = None) extends Directive {

  override type M = TransferDirective

  override val typeId: DTypeId = TransferDirective.TypeId

  override def boxes(digest: Digest32, idx: Int): Seq[EncryBaseBox] =
    Seq(AssetBox(EncryProposition.addressLocked(address),
      Utils.nonceFromDigest(digest ++ Ints.toByteArray(idx)), amount, tokenIdOpt))

  override lazy val isValid: Boolean = amount > 0 && EncryAddress.resolveAddress(address).isSuccess

  override def serializer: Serializer[M] = TransferDirectiveSerializer

  lazy val isIntrinsic: Boolean = tokenIdOpt.isEmpty
}

object TransferDirective {

  val TypeId: DTypeId = 1.toByte

  implicit val jsonEncoder: Encoder[TransferDirective] = (d: TransferDirective) => Map(
    "typeId" -> d.typeId.asJson,
    "address" -> d.address.toString.asJson,
    "amount" -> d.amount.asJson,
    "tokenId" -> d.tokenIdOpt.map(id => Algos.encode(id)).getOrElse("null").asJson
  ).asJson

  implicit val jsonDecoder: Decoder[TransferDirective] = (c: HCursor) => {
    for {
      address <- c.downField("address").as[String]
      amount <- c.downField("amount").as[Long]
      tokenIdOpt <- c.downField("tokenId").as[Option[String]]
    } yield {
      TransferDirective(
        address,
        amount,
        tokenIdOpt.flatMap(id => Algos.decode(id).map(ADKey @@ _).toOption)
      )
    }
  }
}

object TransferDirectiveSerializer extends Serializer[TransferDirective] {

  override def toBytes(obj: TransferDirective): Array[Byte] = {
    val address: Array[Byte] = obj.address.getBytes(Algos.charset)
    address.length.toByte +: Bytes.concat(
      address,
      Longs.toByteArray(obj.amount),
      obj.tokenIdOpt.getOrElse(Array.empty)
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[TransferDirective] = Try {
    val addressLen: Int = bytes.head.toInt
    val address: Address = new String(bytes.slice(1, 1 + addressLen), Algos.charset)
    val amount: Amount = Longs.fromByteArray(bytes.slice(1 + addressLen, 1 + addressLen + 8))
    val tokenIdOpt: Option[@@[Array[DTypeId], authds.ADKey.Tag]] = if ((bytes.length - (1 + addressLen + 8)) == Constants.ModifierIdSize) {
      Some(ADKey @@ bytes.takeRight(Constants.ModifierIdSize))
    } else None
    TransferDirective(address, amount, tokenIdOpt)
  }
}
