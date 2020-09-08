package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig

trait SwaggerApiService extends LazyLogging {

  private val swaggerUiPath = "META-INF/resources/webjars/swagger-ui/3.25.0"

  val swaggerContents: String = loadResource("/swagger/api-docs.yaml") //todo: make sure this is the right location

  val swaggerRoutes: server.Route = {
    path("") {
      get {
        serveIndex()
      }
    } ~
    path("api-docs.yaml") {
      get {
        complete(HttpEntity(ContentTypes.`application/octet-stream`, swaggerContents.getBytes))
      }
    }
  }

  private def serveIndex(): server.Route = {
    val swaggerOptions =
      """
        |        validatorUrl: null,
        |        apisSorter: "alpha",
        |        operationsSorter: "alpha"
      """.stripMargin

    mapResponseEntity { entityFromJar =>
      entityFromJar.transformDataBytes(Flow.fromFunction[ByteString, ByteString] { original: ByteString =>
        ByteString(original.utf8String
          .replace("""url: "https://petstore.swagger.io/v2/swagger.json"""", "url: '/api-docs.yaml'")
          .replace("""layout: "StandaloneLayout"""", s"""layout: "StandaloneLayout", $swaggerOptions""")
          .replace("window.ui = ui", s"""ui.initOAuth({
                                        |        clientId: "${FireCloudConfig.Auth.googleClientId}",
                                        |        clientSecret: "${FireCloudConfig.Auth.swaggerRealm}",
                                        |        realm: "${FireCloudConfig.Auth.swaggerRealm}",
                                        |        appName: "firecloud",
                                        |        scopeSeparator: " ",
                                        |        additionalQueryStringParams: {}
                                        |      })
                                        |      window.ui = ui
                                        |      """.stripMargin)
        )})
    } {
      getFromResource(s"$swaggerUiPath/index.html")
    }
  }

  private def loadResource(filename: String): String = {
    val source = scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename))
    try source.mkString finally source.close()
  }

}