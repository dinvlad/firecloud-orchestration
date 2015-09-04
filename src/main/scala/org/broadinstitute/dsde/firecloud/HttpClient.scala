package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat
import scala.concurrent.Future
import scala.util.{Failure, Success}

import akka.actor.{Actor, Props}
import org.slf4j.{Logger, LoggerFactory}
import spray.client.pipelining._
import spray.http.HttpHeaders.Cookie
import spray.http._
import spray.routing.RequestContext

import org.broadinstitute.dsde.firecloud.HttpClient.PerformExternalRequest
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}


object HttpClient {

  case class PerformExternalRequest(request: HttpRequest)

  def props(requestContext: RequestContext): Props = Props(new HttpClient(requestContext))

  def createJsonHttpEntity(json: String) = {
    HttpEntity(ContentType(MediaType.custom("application", "json")), json)
  }

}

class HttpClient (requestContext: RequestContext) extends Actor
    with FireCloudRequestBuilding with LogRequestBuilding {

  import system.dispatcher
  implicit val system = context.system

  lazy val log = LoggerFactory.getLogger(getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {

    case PerformExternalRequest(externalRequest: HttpRequest) =>
      createResponseFutureFromExternalRequest(requestContext, externalRequest)

  }

  def createResponseFutureFromExternalRequest(
      requestContext: RequestContext,
      externalRequest: HttpRequest): Unit = {
    val pipeline: HttpRequest => Future[HttpResponse] =
      authHeaders(requestContext) ~> addHeader(Cookie(requestContext.request.cookies)) ~>
        logRequest(log) ~> sendReceive
    pipeline(externalRequest) onComplete {
      case Success(response) =>
        log.debug("Got response: " + response)
        context.parent ! RequestCompleteWithHeaders(response, response.headers.filterNot(isAutomaticHeader):_*)
      case Failure(error) =>
        log.error("External request failed", error)
        context.parent ! RequestComplete(StatusCodes.InternalServerError, error.getMessage)
    }
  }

  private def isAutomaticHeader(h: HttpHeader): Boolean = h match {
    case _:HttpHeaders.Date => true
    case _:HttpHeaders.Server => true
    case _:HttpHeaders.`Content-Type` => true
    case _:HttpHeaders.`Content-Length` => true
    case _ => false
  }
}


trait LogRequestBuilding extends spray.httpx.RequestBuilding {
  def logRequest(log: Logger): RequestTransformer = { request =>
    log.debug("Sending request: " + request)
    request
  }
}

