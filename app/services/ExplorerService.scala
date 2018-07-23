package services

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Host
import akka.stream.Materializer
import akka.util.ByteString
import io.circe.parser.decode
import io.circe.syntax._
import models.box.AssetBox
import models.EncryTransaction
import settings.WalletAppSettings

class ExplorerService @Inject()(implicit val system: ActorSystem, implicit val materializer: Materializer,
                                implicit val ec: ExecutionContext, settings: WalletAppSettings) {

  def requestUtxos(address: String): Future[IndexedSeq[AssetBox]] =
    Http().singleRequest(HttpRequest(
      method = HttpMethods.GET,
      uri = "/transactions/$address/outputs/unspent"
    ).withEffectiveUri(false, Host(settings.explorerAddress)))
      .flatMap(_.entity.dataBytes.runFold(ByteString.empty)(_ ++ _))
      .map(_.utf8String)
      .map(decode[Seq[AssetBox]])
      .map(_.map(_.toIndexedSeq))
      .flatMap(_.fold(Future.failed, Future.successful))

  def commitTransaction(tx: EncryTransaction): Future[HttpResponse] =
    Http().singleRequest(HttpRequest(
      method = HttpMethods.POST,
      uri = "/transactions/send",
      entity = HttpEntity(ContentTypes.`application/json`, tx.asJson.toString)
    ).withEffectiveUri(false, Host(settings.knownPeers.head)))

}