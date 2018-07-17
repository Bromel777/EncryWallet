package org.encryfoundation.wallet.deprecated

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.parser.decode
import io.circe.syntax._
import org.encryfoundation.wallet.Wallet
import org.encryfoundation.wallet.deprecated.Implicits._
import org.encryfoundation.wallet.transaction.box.AssetBox
import org.encryfoundation.wallet.transaction.{EncryTransaction, Transaction}
import org.encryfoundation.wallet.utils.ExtUtils._
import scorex.crypto.authds.ADKey
import scorex.crypto.encode.Base58

import scala.concurrent.Future

trait WalletActions {
  var walletData: WalletView = WalletView(None, "3BxEZq6XcBzMSoDbfmY1V9qoYCwu73G1JnJAToaYEhikQ3bBKK")
  val config: Config = ConfigFactory.load()
  val nodeHost: String = config.getString("wallet.node.host")

  def walletWithError: Future[_] => Future[Unit] = _
    .map{_ => walletData = walletData.copy(error = None)}
    .recover{case fail => walletData = walletData.copy(error = Some(fail.toString)) }

  def withWallet[T](f: Wallet => Future[T]): Future[T] =
    walletData.wallet.map(f).getOrElse(Future.failed(new Exception("Send transaction without wallet")))

  def getBoxesFromNode(address: String, amountAndFee: Long): Future[IndexedSeq[AssetBox]] =
    Uri(s"$nodeHost/state/boxes/$address".trace)
      .rapply(uri => HttpRequest(uri = uri))
      .rapply(Http().singleRequest(_))
      .flatMap(_.entity.dataBytes.runFold(ByteString.empty)(_ ++ _))
      .map(_.utf8String)
      .map(decode[Seq[AssetBox]])
      .map(_.map(_.foldLeft(Seq[AssetBox]()) { case (seq, box) =>
        if (seq.map(_.value).sum < amountAndFee) seq :+ box else seq
      }.toIndexedSeq))
      .flatMap(_.fold(Future.failed, Future.successful))

  def sendTransactionsToNode(encryTransaction: EncryTransaction): Future[HttpResponse] =
    encryTransaction.asJson.toString
      .rapply(HttpEntity(ContentTypes.`application/json`,_))
      .rapply(HttpRequest(method = HttpMethods.POST, uri = Uri(s"$nodeHost/transactions/send")).withEntity(_))
      .rapply(Http().singleRequest(_))

  def sendTransaction(fee: Long, amount: Long, recipient: String): Future[HttpResponse] =
    withWallet { wallet =>
      getBoxesFromNode(wallet.account.address, fee + amount).flatMap {
        Transaction.defaultPaymentTransactionScratch(wallet.getSecret, fee, System.currentTimeMillis, _,
          recipient, amount, None).rapply(sendTransactionsToNode)
      }
    }

  def sendTransactionWithBox(fee: Long, amount: Long, recipient: String, boxId: String, change: Long): Future[HttpResponse] =
    withWallet { wallet =>
      Base58.decode(boxId)
        .map(id => IndexedSeq(ADKey @@ id))
        .map(Transaction.specialTransactionScratch(wallet.getSecret, fee, System.currentTimeMillis, _, recipient, amount, change, None))
        .map(sendTransactionsToNode)
        .fold(Future.failed, x => x)
    }

  import org.encryfoundation.prismlang.compiler.PCompiler.compile
  def sendTransactionWithScript (fee: Long, amount: Long, src: String): Future[HttpResponse] =
    withWallet { wallet =>
      getBoxesFromNode(wallet.account.address, fee + amount).flatMap { boxes =>
        compile(src).map(
          Transaction.scriptedAssetTransactionScratch(wallet.getSecret, fee, System.currentTimeMillis, boxes, _, amount, None)
        ).map(sendTransactionsToNode).fold(Future.failed, x => x)
      }
    }
}