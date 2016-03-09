package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.{EntityWithType, ProcessUrl}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, ErrorReport}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectiveUtils, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}

import spray.client.pipelining._
import spray.http.HttpEncodings._
import spray.http.HttpHeaders.`Accept-Encoding`
import spray.http.HttpResponse
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.encoding.Gzip
import spray.json.DefaultJsonProtocol._
import spray.json.JsValue
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object GetEntitiesWithType {
  case class ProcessUrl(url: String)
  case class EntityWithType(name: String, entityType: String, attributes: Option[Map[String, JsValue]])
  def props(requestContext: RequestContext): Props = Props(new GetEntitiesWithTypeActor(requestContext))
}

class GetEntitiesWithTypeActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  def receive = {
    case ProcessUrl(url: String) =>
      log.debug("Processing entity type map for url: " + url)
      val pipeline = authHeaders(requestContext) ~> addHeader(`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)
      val entityTypesFuture: Future[HttpResponse] = pipeline { Get(url) }
      val allEntitiesResponse = entityTypesFuture.flatMap { response =>
        response.status match {
          case x if x == OK =>
            val entityTypes: List[String] = unmarshal[Map[String, Int]].apply(response).keys.toList
            val entityUrls: List[String] = entityTypes.map(s => FireCloudDirectiveUtils.encodeUri(s"$url/$s"))
            val entityFutures: List[Future[HttpResponse]] = entityUrls map { entitiesUrl => pipeline { Get(entitiesUrl) } }
            getEntitiesForTypesResponse(Future sequence entityFutures, entityTypes)
          case x =>
            Future(RequestComplete(response))
        }
      } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage) }
      allEntitiesResponse pipeTo context.parent
    case _ =>
      Future(RequestCompleteWithErrorReport(BadRequest, "Invalid message received")) pipeTo context.parent
  }

  def getEntitiesForTypesResponse(future: Future[List[HttpResponse]], entityTypes: List[String]): Future[PerRequestMessage] = {
    future.map {
      responses =>
        val allSucceeded = responses.forall(_.status == OK)
        allSucceeded match {
          case true =>
            val entities = responses.flatMap(unmarshal[List[EntityWithType]].apply)
            RequestComplete(OK, entities)
          case false =>
            val errors = responses.filterNot(_.status == OK) map { e => (e, ErrorReport.tryUnmarshal(e)) }
            val errorReports = errors collect { case (_, Success(report)) => report }
            val missingReports = errors collect { case (originalError, Failure(_)) => originalError }
            val errorMessage = {
              val baseMessage = "%d failures out of %d attempts retrieving entityUrls.  Errors: %s"
                .format(errors.size, entityTypes.size, errors mkString ",")
              if (missingReports.isEmpty) baseMessage
              else {
                val supplementalErrorMessage = "Additionally, %d of these failures did not provide error reports: %s"
                  .format(missingReports.size, missingReports mkString ",")
                baseMessage + "\n" + supplementalErrorMessage
              }
            }
            RequestCompleteWithErrorReport(InternalServerError, errorMessage, errorReports)
        }
    } recover { case e:Throwable =>  RequestCompleteWithErrorReport(InternalServerError, e.getMessage) }
  }

}
