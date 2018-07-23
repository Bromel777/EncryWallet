package services

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.model.HttpResponse
import org.encryfoundation.prismlang.compiler.PCompiler.compile
import models._
import storage.LSMStorage

class TransactionService @Inject()(implicit ec: ExecutionContext, lsmStrorage: LSMStorage, es: ExplorerService) {

  def sendPaymentTransaction(walletId: String, ptr: PaymentTransactionRequest): Future[HttpResponse] = {
    Wallet.fromId(walletId) match {
      case Some(w) => es.requestUtxos(w.account.address).map { boxes =>
        Transaction.defaultPaymentTransactionScratch(
          lsmStrorage.getWalletSecret(w),
          ptr.fee,
          System.currentTimeMillis,
          boxes,
          ptr.recipient,
          ptr.amount,
          None
        )
      }.flatMap {
        es.commitTransaction
      }
      case None => Future.failed(new IllegalArgumentException("Wallet ID is not a valid Base16 encoded string"))
    }
  }

  def sendScriptedTransaction(walletId: String, str: ScriptedTransactionRequest): Future[HttpResponse] = {
    Wallet.fromId(walletId) match {
      case Some(w) => es.requestUtxos(w.account.address).flatMap { boxes =>
        compile(str.source).map { contract =>
          Transaction.scriptedAssetTransactionScratch(
            lsmStrorage.getWalletSecret(w),
            str.fee,
            System.currentTimeMillis,
            boxes,
            contract,
            str.amount,
            None
          )
        }.fold(Future.failed, Future.successful)
      }.flatMap {
        es.commitTransaction
      }
      case None => Future.failed(new IllegalArgumentException("Wallet ID is not a valid Base16 encoded string"))
    }

  }
}