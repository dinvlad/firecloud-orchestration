package org.broadinstitute.dsde.firecloud

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, StatusCodes}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.http.scaladsl.server.{Directive, Directive0, ExceptionHandler, RouteResult}
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.webservice._
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext}
import scala.language.postfixOps

object FireCloudApiService {

  val exceptionHandler = {

    import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._

    implicit val errorReportSource = ErrorReportSource("FireCloud") //TODO make sure this doesn't clobber source names globally

    ExceptionHandler {
      case withErrorReport: FireCloudExceptionWithErrorReport =>
        complete(withErrorReport.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError) -> withErrorReport.errorReport)
      case e: Throwable =>
        complete(StatusCodes.InternalServerError -> ErrorReport(e))
    }
  }
}

trait FireCloudApiService extends CookieAuthedApiService
  with EntityApiService
  with ExportEntitiesApiService
  with LibraryApiService
  with NamespaceApiService
  with NihApiService
  with OauthApiService
  with RegisterApiService
  with StorageApiService
  with WorkspaceApiService
  with NotificationsApiService
  with MethodConfigurationApiService
  with BillingService
  with SubmissionService
  with StatusApiService
  with MethodsApiService
  with Ga4ghApiService
  with UserApiService
  with SwaggerApiService
  with ShareLogApiService
  with ManagedGroupApiService
  with CromIamApiService
  with HealthService
  with StaticNotebooksApiService
{

  override lazy val log = LoggerFactory.getLogger(getClass)

  val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor
  val entityServiceConstructor: (ModelSchema) => EntityService
  val libraryServiceConstructor: (UserInfo) => LibraryService
  val ontologyServiceConstructor: () => OntologyService
  val namespaceServiceConstructor: (UserInfo) => NamespaceService
  val nihServiceConstructor: () => NihService
  val registerServiceConstructor: () => RegisterService
  val storageServiceConstructor: (UserInfo) => StorageService
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService
  val statusServiceConstructor: () => StatusService
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService
  val userServiceConstructor: (UserInfo) => UserService
  val shareLogServiceConstructor: () => ShareLogService
  val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService
  val trialServiceConstructor: () => TrialService
  val agoraPermissionService: (UserInfo) => AgoraPermissionService

  implicit val executionContext: ExecutionContext
  implicit val materializer: Materializer

  private def logRequests: Directive0 = {

    def myLoggingFunction(logger: LoggingAdapter)(req: HttpRequest)(res: RouteResult): Unit = {
      val entry: Option[LogEntry] = res match {
        case Complete(resp) =>
          try {
            val logLevel: LogLevel = resp.status.intValue / 100 match {
              case 5 => Logging.ErrorLevel
              case _ => Logging.DebugLevel // this will log everything, if logback level is set to debug!
            }
            resp.entity match {
              case HttpEntity.Strict(_, data) =>
                val entityAsString = data.decodeString(java.nio.charset.Charset.defaultCharset())
                Option(LogEntry(s"${req.method} ${req.uri}: ${resp.status} entity: $entityAsString", logLevel))
              case _ =>
                // note that some responses, if large enough, are returned as Chunked, and we don't try to log
                // those here.
                None
            }
          } catch {
            case e:Exception =>
              // error when extracting the response, likely in decoding the raw bytes
              None
          }
        case _ => None // route rejections; don't attempt to log

      }
      entry.foreach(_.logTo(logger))
    }

    DebuggingDirectives.logRequestResult(LoggingMagnet(log => myLoggingFunction(log)))
  }

  // So we have the time when users send us error screenshots
  val appendTimestampOnFailure: Directive0 = mapResponse { response =>
    if (response.status.isSuccess()) {
      response
    } else {
      try {
        import spray.json._
        response.mapEntity {
          case HttpEntity.Strict(contentType, data) =>
            data.decodeString(java.nio.charset.Charset.defaultCharset()).parseJson match {
              case jso: JsObject =>
                val withTimestamp = jso.fields + ("timestamp" -> JsNumber(System.currentTimeMillis()))
                HttpEntity.apply(contentType, JsObject(withTimestamp).prettyPrint.getBytes)
              // was not a JsObject
              case _ => HttpEntity.Strict(contentType, data)
            }
          case x => x
        }
      } catch {
        case _: Exception => response
      }
    }
  }

  // routes under /api
  def apiRoutes: server.Route =
    options { complete(StatusCodes.OK) } ~
      withExecutionContext(ExecutionContext.global) {
        methodsApiServiceRoutes ~
          profileRoutes ~
          cromIamApiServiceRoutes ~
          methodConfigurationRoutes ~
          submissionServiceRoutes ~
          nihRoutes ~
          billingServiceRoutes ~
          shareLogServiceRoutes ~
          staticNotebooksRoutes
      }

  val routeWrappers: Directive[Unit] =
   handleRejections(org.broadinstitute.dsde.firecloud.model.defaultErrorReportRejectionHandler) &
      handleExceptions(FireCloudApiService.exceptionHandler) &
      appendTimestampOnFailure &
      logRequests

  def route: server.Route = (routeWrappers) {
    cromIamEngineRoutes ~
      exportEntitiesRoutes ~
      cromIamEngineRoutes ~
      exportEntitiesRoutes ~
      entityRoutes ~
      healthServiceRoutes ~
      libraryRoutes ~
      namespaceRoutes ~
      oauthRoutes ~
      profileRoutes ~
      registerRoutes ~
      storageRoutes ~
      swaggerRoutes ~
      syncRoute ~
      userServiceRoutes ~
      managedGroupServiceRoutes ~
      workspaceRoutes ~
      notificationsRoutes ~
      statusRoutes ~
      ga4ghRoutes ~
      pathPrefix("api") {
        apiRoutes
      } ~
      // insecure cookie-authed routes
      cookieAuthedRoutes
  }

}

class FireCloudApiServiceImpl(val agoraPermissionService: (UserInfo) => AgoraPermissionService, val trialServiceConstructor: () => TrialService, val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor, val entityServiceConstructor: (ModelSchema) => EntityService, val libraryServiceConstructor: (UserInfo) => LibraryService, val ontologyServiceConstructor: () => OntologyService, val namespaceServiceConstructor: (UserInfo) => NamespaceService, val nihServiceConstructor: () => NihService, val registerServiceConstructor: () => RegisterService, val storageServiceConstructor: (UserInfo) => StorageService, val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService, val statusServiceConstructor: () => StatusService, val permissionReportServiceConstructor: (UserInfo) => PermissionReportService, val userServiceConstructor: (UserInfo) => UserService, val shareLogServiceConstructor: () => ShareLogService, val managedGroupServiceConstructor: (WithAccessToken) => ManagedGroupService)(implicit val actorRefFactory: ActorRefFactory, implicit val executionContext: ExecutionContext, val materializer: Materializer, val system: ActorSystem) extends FireCloudApiService with StandardUserInfoDirectives
