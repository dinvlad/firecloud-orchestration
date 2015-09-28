package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.slf4j.LoggerFactory
import spray.routing.{HttpService, Route}

import org.broadinstitute.dsde.firecloud.FireCloudConfig


abstract class SubmissionServiceActor extends Actor with SubmissionService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait SubmissionService extends HttpService with PerRequestCreator with FireCloudDirectives {

  val routes = postAndGetRoutes
  lazy val log = LoggerFactory.getLogger(getClass)

  def postAndGetRoutes: Route =
    pathPrefix("workspaces" / Segment / Segment) {
      (workspaceNamespace, workspaceName) =>
        pathPrefixTest("submissions") {
          val path = "workspaces/" + workspaceNamespace + "/" + workspaceName + "/submissions"
          passthroughAllPaths("submissions", FireCloudConfig.Rawls.baseUrl + "/" + path)
        }
    }
}
