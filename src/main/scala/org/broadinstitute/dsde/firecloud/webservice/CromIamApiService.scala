package org.broadinstitute.dsde.firecloud.webservice


import akka.http.scaladsl.server.PathMatchers._
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.HttpMethods
import spray.routing.Route
import spray.routing.HttpService

trait CromIamApiService extends HttpService with FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  lazy val workflowRoot: String = FireCloudConfig.CromIAM.authUrl + "/workflows/v1"
  lazy val womtoolRoute: String = FireCloudConfig.CromIAM.authUrl + "/womtool/v1"
  lazy val engineRoot: String = FireCloudConfig.CromIAM.baseUrl + "/engine/v1"
  lazy val rawlsWorkflowRoot: String = FireCloudConfig.Rawls.authUrl + "/workflows"

  // This is the subset of CromIAM endpoints required for Job Manager. Orchestration is acting as a proxy between
  // CromIAM and Job Manager as of February 2019.
  // Adam Nichols, 2019-02-04

  val cromIamApiServiceRoutes: Route =
    pathPrefix( "workflows" / Segment ) { _ =>
      path("query") {
        pathEnd {
          get {
            passthrough(s"$workflowRoot/query", HttpMethods.GET)
          } ~
          post {
            passthrough(s"$workflowRoot/query", HttpMethods.POST)
          }
        }
      } ~
      pathPrefix( Segment ) { workflowId: String =>
        path("abort") {
          pathEnd {
            post {
              passthrough(s"$workflowRoot/$workflowId/abort", HttpMethods.POST)
            }
          }
        } ~
        path("metadata") {
          pathEnd {
            get {
              passthrough(s"$workflowRoot/$workflowId/metadata", HttpMethods.GET)
            }
          }
        } ~
        path("labels") {
          pathEnd {
            patch {
              passthrough(s"$workflowRoot/$workflowId/labels", HttpMethods.PATCH)
            }
          }
        } ~
        path("backend" / "metadata" / Segment.repeat(Slash)) { operationId =>
          get {
            passthrough(s"$rawlsWorkflowRoot/$workflowId/genomics/${operationId.mkString("/")}", HttpMethods.GET)
          }
        }
      }
    } ~
    pathPrefix( "womtool" / Segment ) { _ =>
      path( "describe" ) {
        pathEnd {
          post {
            passthrough(s"$womtoolRoute/describe", HttpMethods.POST)
          }
        }
      }
    }

  val cromIamEngineRoutes: Route =
    pathPrefix( "engine" / Segment ) { _ =>
      path("version" ) {
        pathEnd {
          passthrough(s"$engineRoot/version", HttpMethods.GET)
        }
      } ~
      path("status" ) {
        pathEnd {
          passthrough(s"$engineRoot/status", HttpMethods.GET)
        }
      }
    }

}