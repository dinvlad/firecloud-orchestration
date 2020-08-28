package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Route, StandardRoute}
import org.broadinstitute.dsde.firecloud.model.Trial.TrialOperations
import org.broadinstitute.dsde.firecloud.model.Trial.TrialOperations._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, TrialService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.rawls.model.ErrorReport
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait TrialApiService extends FireCloudDirectives
  with StandardUserInfoDirectives with SprayJsonSupport {

  implicit val executionContext: ExecutionContext
  val trialServiceConstructor: () => TrialService

  val trialApiServiceRoutes: Route = {
    pathPrefix("trial" / "manager") {
      // TODO: See if it makes sense to use DELETE for terminate, and perhaps PUT for disable
      post {
        path("enable|disable|terminate".r) { (operation: String) =>
          requireUserInfo() { managerInfo => // We will need the manager's credentials for authentication downstream
            entity(as[Seq[String]]) { userEmails =>
              updateUsers(managerInfo, TrialOperations.withName(operation), userEmails)
            }
          }
        } ~
        path("projects") {
          parameter("count".as[Int] ? 0) { count =>
            parameter("project".as[String] ? "") { projectName =>
              parameter("operation") { op =>
                requireUserInfo() { userInfo =>
                    op.toLowerCase match {
                      case "create" => complete { trialServiceConstructor().CreateProjects(userInfo, count) }
                      case "verify" => complete { trialServiceConstructor().VerifyProjects(userInfo) }
                      case "count" => complete { trialServiceConstructor().CountProjects(userInfo) }
                      case "adopt" => complete { trialServiceConstructor().AdoptProject(userInfo, projectName) }
                      case "scratch" => complete { trialServiceConstructor().ScratchProject(userInfo, projectName) }
                      case "report" => complete { trialServiceConstructor().Report(userInfo) }
                      case _ => complete { RequestComplete(StatusCodes.BadRequest, ErrorReport(s"invalid operation '$op'")) }
                    }
                }
              }
            }
          }
        }
      }
    }
  }

  private def updateUsers(managerInfo: UserInfo,
                          operation: TrialOperation,
                          userEmails: Seq[String]): StandardRoute = operation match {
    case Enable => complete { trialServiceConstructor().EnableUsers(managerInfo, userEmails) }
    case Disable => complete { trialServiceConstructor().DisableUsers(managerInfo, userEmails) }
    case Terminate => complete { trialServiceConstructor().TerminateUsers(managerInfo, userEmails) }
  }
}
