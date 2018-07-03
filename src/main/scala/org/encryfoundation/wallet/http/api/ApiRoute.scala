package org.encryfoundation.wallet.http.api

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import org.encryfoundation.wallet.WalletApp
import org.encryfoundation.wallet.crypto.Base58Check
import scorex.crypto.authds.ADKey
import scorex.crypto.encode.Base16

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try

trait ApiRoute extends FailFastCirceSupport with PredefinedFromEntityUnmarshallers with Directives {

  def context: ActorRefFactory

  def route: Route

  implicit lazy val timeout: Timeout = Timeout(WalletApp.settings.restApi.timeout)

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  protected def toJsonResponse(js: Json): Route = complete(HttpEntity(ContentTypes.`application/json`, js.spaces2))

  protected def toJsonResponse(fn: Future[Json]): Route = onSuccess(fn) { toJsonResponse }

  protected def toJsonOptionalResponse(fn: Future[Option[Json]]): Route = onSuccess(fn) {
    _.map(toJsonResponse).getOrElse(complete(StatusCodes.NotFound))
  }

  val paging: Directive[(Int, Int)] = parameters("offset".as[Int] ? 0, "limit".as[Int] ? 50)

  val modifierId: Directive1[String] = pathPrefix(Segment).flatMap { h =>
    if (Base16.decode(h).isSuccess) provide(h) else reject
  }

  val height: Directive1[Int] = pathPrefix(Segment).flatMap { hs =>
    Try(hs.toInt).filter(_ >= 0).map(provide).getOrElse(reject)
  }

  val qty: Directive1[Int] = pathPrefix(Segment).flatMap { hs =>
    Try(hs.toInt).filter(_ > 0).map(provide).getOrElse(reject)
  }

  val address: Directive1[String] = pathPrefix(Segment).flatMap { addr =>
    if(Base58Check.decode(addr).isSuccess) provide(addr) else reject
  }

  val boxId: Directive1[ADKey] = pathPrefix(Segment).flatMap { key =>
    Base16.decode(key).map(k => provide(ADKey @@ k)).getOrElse(reject)
  }

  implicit class OkJsonResp(fn: Future[Json]) {
    def okJson(): Route = toJsonResponse(fn)
  }

  implicit class OkJsonOptResp(fn: Future[Option[Json]]) {
    def okJson(): Route = toJsonOptionalResponse(fn)
  }

//  lazy val withAuth: Directive0 = optionalHeaderValueByName(apiKeyHeaderName).flatMap {
//    case _ if settings.apiKeyHash.isEmpty => pass
//    case None => reject(AuthorizationFailedRejection)
//    case Some(key) =>
//      if (settings.apiKeyHash.exists(_.toCharArray.sameElements(Blake2b256(key)))) pass
//      else reject(AuthorizationFailedRejection)
//  }
}
