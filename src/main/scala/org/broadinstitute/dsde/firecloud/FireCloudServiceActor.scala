package org.broadinstitute.dsde.firecloud

import akka.stream.ActorMaterializer
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.elastic.ElasticUtils
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WithAccessToken}
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.http._
import spray.routing.{HttpServiceActor, Route}
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.webservice._
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, SubsystemStatus}
import org.broadinstitute.dsde.workbench.util.health.Subsystems._
import org.elasticsearch.client.transport.TransportClient

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps


class FireCloudServiceActor extends HttpServiceActor with FireCloudDirectives
  with CookieAuthedApiService
  with EntityService
  with ExportEntitiesApiService
  with LibraryApiService
  with NamespaceApiService
  with NihApiService
  with OauthApiService
  with RegisterApiService
  with StorageApiService
  with WorkspaceApiService
  with NotificationsApiService
  with StatusApiService
  with MethodsApiService
  with Ga4ghApiService
  with UserApiService
  {

  implicit val system = context.system

  trait ActorRefFactoryContext {
    def actorRefFactory = context
  }

  val elasticSearchClient: TransportClient = ElasticUtils.buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)

  val agoraDAO:AgoraDAO = new HttpAgoraDAO(FireCloudConfig.Agora)
  val rawlsDAO:RawlsDAO = new HttpRawlsDAO
  val samDAO:SamDAO = new HttpSamDAO
  val thurloeDAO:ThurloeDAO = new HttpThurloeDAO
  val googleServicesDAO:GoogleServicesDAO = HttpGoogleServicesDAO
  val ontologyDAO:OntologyDAO = new ElasticSearchOntologyDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.ontologyIndexName)
  val consentDAO:ConsentDAO = new HttpConsentDAO
  val searchDAO:SearchDAO = new ElasticSearchDAO(elasticSearchClient, FireCloudConfig.ElasticSearch.indexName, ontologyDAO)

  val app:Application = new Application(agoraDAO, googleServicesDAO, ontologyDAO, consentDAO, rawlsDAO, samDAO, searchDAO, thurloeDAO)
  val materializer: ActorMaterializer = ActorMaterializer()

  val healthMonitorChecks = app.healthMonitorChecks
  val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks.keySet)( () => healthMonitorChecks ), "health-monitor")
  system.scheduler.schedule(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)

  val exportEntitiesByTypeConstructor: (ExportEntitiesByTypeArguments) => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app, materializer)
  val libraryServiceConstructor: (UserInfo) => LibraryService = LibraryService.constructor(app)
  val ontologyServiceConstructor: () => OntologyService = OntologyService.constructor(app)
  val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)
  val nihServiceConstructor: () => NihServiceActor = NihService.constructor(app)
  val oauthServiceConstructor: () => OAuthService = OAuthService.constructor(app)
  val registerServiceConstructor: () => RegisterService = RegisterService.constructor(app)
  val storageServiceConstructor: (UserInfo) => StorageService = StorageService.constructor(app)
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)
  val statusServiceConstructor: () => StatusService = StatusService.constructor(healthMonitor)
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(app)
  val trialServiceConstructor: () => TrialService = TrialService.constructor(app)

  // routes under /api
  val methodConfigurationService = new MethodConfigurationService with ActorRefFactoryContext
  val submissionsService = new SubmissionService with ActorRefFactoryContext
  val billingService = new BillingService with ActorRefFactoryContext
  val apiRoutes = methodsApiServiceRoutes ~ profileRoutes ~
    methodConfigurationService.routes ~ submissionsService.routes ~
    nihRoutes ~ billingService.routes

  val healthService = new HealthService with ActorRefFactoryContext

  override lazy val log = LoggerFactory.getLogger(getClass)
  val logRequests = mapInnerRoute { route => requestContext =>
    log.debug(requestContext.request.toString)
    route(requestContext)
  }

  // So we have the time when users send us error screenshots
  val appendTimestampOnFailure = mapHttpResponse { response =>
    if (response.status.isFailure) {
      try {
        import spray.json.DefaultJsonProtocol._
        import spray.json._
        val dataMap = response.entity.asString.parseJson.convertTo[Map[String, JsValue]]
        val withTimestamp = dataMap + ("timestamp" -> JsNumber(System.currentTimeMillis()))
        val contentType = response.header[HttpHeaders.`Content-Type`].map{_.contentType}.getOrElse(ContentTypes.`application/json`)
        response.withEntity(HttpEntity(contentType, withTimestamp.toJson.prettyPrint + "\n"))
      } catch {
        // usually a failure to parse, if the response isn't JSON (e.g. HTML responses from Google)
        case e: Exception => response
      }
    } else response
  }

  // wraps route rejections in an ErrorReport
  import org.broadinstitute.dsde.firecloud.model.errorReportRejectionHandler

  def receive = runRoute(
    appendTimestampOnFailure {
      logRequests {
        exportEntitiesRoutes ~
        entityRoutes ~
        healthService.routes ~
        libraryRoutes ~
        namespaceRoutes ~
        oauthRoutes ~
        profileRoutes ~
        registerRoutes ~
        storageRoutes ~
        swaggerUiService ~
        syncRoute ~
        userServiceRoutes ~
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
  )

  private val swaggerUiPath = "META-INF/resources/webjars/swagger-ui/2.2.5"

  val swaggerUiService = {
    path("") {
      get {
        parameter("url") {urlparam =>
          requestUri {uri =>
            redirect(uri.withQuery(Map.empty[String,String]), MovedPermanently)
          }
        } ~
        serveIndex()
      }
    } ~
    path("api-docs.yaml") {
      get {
        withResourceFileContents("swagger/api-docs.yaml") { apiDocs =>
          complete(apiDocs)
        }
      }
    } ~
    // We have to be explicit about the paths here since we're matching at the root URL and we don't
    // want to catch all paths lest we circumvent Spray's not-found and method-not-allowed error
    // messages.
    (pathSuffixTest("o2c.html") | pathSuffixTest("swagger-ui.js")
        | pathPrefixTest("css" /) | pathPrefixTest("fonts" /) | pathPrefixTest("images" /)
        | pathPrefixTest("lang" /) | pathPrefixTest("lib" /)) {
      get {
        getFromResourceDirectory(swaggerUiPath)
      }
    }
  }

  private def serveIndex(): Route = {
    withResourceFileContents(swaggerUiPath + "/index.html") { indexHtml =>
      complete {
        val swaggerOptions =
          """
            |        validatorUrl: null,
            |        apisSorter: "alpha",
            |        operationsSorter: "alpha",
          """.stripMargin

        HttpEntity(ContentType(MediaTypes.`text/html`),
          indexHtml
            .replace("your-client-id", FireCloudConfig.Auth.googleClientId)
            .replace("your-realms", FireCloudConfig.Auth.swaggerRealm)
            .replace("your-app-name", FireCloudConfig.Auth.swaggerRealm)
            .replace("scopeSeparator: \",\"", "scopeSeparator: \" \"")
            .replace("jsonEditor: false,", "jsonEditor: false," + swaggerOptions)
            .replace("url = \"http://petstore.swagger.io/v2/swagger.json\";",
              "url = '/api-docs.yaml';")
        )
      }
    }
  }

}
