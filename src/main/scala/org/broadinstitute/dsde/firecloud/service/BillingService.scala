package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig

trait BillingService extends FireCloudDirectives {
  private val billingUrl = FireCloudConfig.Rawls.authUrl + "/billing"
  
  val routes: Route =
    pathPrefix("billing") {
      pathEnd {
        post {
          passthrough(billingUrl, HttpMethods.POST)
        }
      } ~
      pathPrefix(Segment) { projectId =>
        path("members") {
          get {
            passthrough(s"$billingUrl/$projectId/members", HttpMethods.GET)
          }
        } ~
          path("googleRole" / Segment / Segment) { (googleRole, email) =>
            (delete | put) {
              extract(_.request.method) { method =>
                passthrough(s"$billingUrl/$projectId/googleRole/$googleRole/$email", method)
              }
            }
          } ~
        // workbench project role: owner or user
        path(Segment / Segment) { (workbenchRole, email) =>
          (delete | put) {
            extract(_.request.method) { method =>
              passthrough(s"$billingUrl/$projectId/$workbenchRole/$email", method)
            }
          }
        }
      }
    }
}
