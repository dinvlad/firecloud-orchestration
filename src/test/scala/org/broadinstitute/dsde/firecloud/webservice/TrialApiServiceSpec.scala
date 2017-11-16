package org.broadinstitute.dsde.firecloud.webservice

import java.time.temporal.ChronoUnit

import org.broadinstitute.dsde.firecloud.dataaccess.HttpThurloeDAO
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils.thurloeServerPort
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, ProfileWrapper, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, TrialService}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import spray.http.StatusCodes.{BadRequest, NoContent, OK}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impProfileWrapper
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialStates, UserTrialStatus}
import spray.http.HttpMethods.POST
import spray.json._

import scala.concurrent.Future

class TrialApiServiceSpec extends BaseServiceSpec with UserApiService {
  // note that the endpoint tested in this class lives in UserApiService, but I'm breaking it out into
  // a standalone test class for modularity.

  def actorRefFactory = system
  val trialServiceConstructor:() => TrialService = TrialService.constructor(app.copy(thurloeDAO = new TrialApiServiceSpecThurloeDAO))

  var profileServer: ClientAndServer = _

  val disabledUser = "disabled-user"
  val enabledUser = "enabled-user"
  val enrolledUser = "enrolled-user"
  val terminatedUser = "terminated-user"

  val disabledProps = Map.empty[String,String]
  val enabledProps = Map(
    "trialCurrentState" -> "Enabled",
    "trialEnabledDate" -> "1"
  )
  val enrolledProps = Map(
    "trialCurrentState" -> "Enrolled",
    "trialEnabledDate" -> "11",
    "trialEnrolledDate" -> "22",
    "trialExpirationDate" -> "99"
  )
  val terminatedProps = Map(
    "trialCurrentState" -> "Terminated",
    "trialEnabledDate" -> "111",
    "trialEnrolledDate" -> "222",
    "trialTerminatedDate" -> "333",
    "trialExpirationDate" -> "999"
  )

  private def profile(user:String, props:Map[String,String]): ProfileWrapper =
    ProfileWrapper(user, props.toList.map {
      case (k:String, v:String) => FireCloudKeyValue(Some(k), Some(v))
    })


  override protected def beforeAll(): Unit = {
    profileServer = startClientAndServer(thurloeServerPort)

    List((disabledUser,disabledProps),(enabledUser,enabledProps),(enrolledUser,enrolledProps),(terminatedUser,terminatedProps)).foreach {
      case (user, props) =>
        profileServer
          .when(request()
            .withMethod("GET")
            .withHeader(fireCloudHeader.name, fireCloudHeader.value)
            .withPath(UserApiService.remoteGetAllPath.format(user)))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
              .withBody(profile(user, props).toJson.compactPrint)
          )
    }
  }

  override protected def afterAll(): Unit = {
    profileServer.stop()
  }

  val enrollPath = "/api/profile/trial"

  "Free Trial Endpoints" - {
    "User-initiated enrollment endpoint" - {
      allHttpMethodsExcept(POST) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)(enrollPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
            assert(!handled)
          }
        }
      }
    }

    "attempting to enroll as a disabled user" - {
      "should be a BadRequest" in {
        Post(enrollPath) ~> dummyUserIdHeaders(disabledUser) ~> userServiceRoutes ~> check {
          status should equal(BadRequest)
        }
      }
    }

    "attempting to enroll as an enabled user" - {
      "should be NoContent success" in {
        Post(enrollPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
          assertResult(NoContent, response.entity.asString) { status }
        }
      }
    }

    "attempting to enroll as an enrolled user" - {
      "should be a BadRequest" in {
        Post(enrollPath) ~> dummyUserIdHeaders(enrolledUser) ~> userServiceRoutes ~> check {
          status should equal(BadRequest)
        }
      }
    }

    "attempting to enroll as a terminated user" - {
      "should be a BadRequest" in {
        Post(enrollPath) ~> dummyUserIdHeaders(terminatedUser) ~> userServiceRoutes ~> check {
          status should equal(BadRequest)
        }
      }
    }
  }

  class TrialApiServiceSpecThurloeDAO extends HttpThurloeDAO {
    override def saveTrialStatus(userInfo: UserInfo, trialStatus: UserTrialStatus) = {
      // Note: because HttpThurloeDAO catches exceptions, the assertions here will
      // result in InternalServerErrors instead of appearing nicely in unit test output.
      userInfo.id match {
        case `enabledUser` => {
          // TODO: read duration from conf, once runtime also reads it from conf
          val expectedExpirationDate = trialStatus.enrolledDate.plus(60,ChronoUnit.DAYS)
          assertResult(Some(TrialStates.Enrolled)) { trialStatus.currentState }
          assert(trialStatus.enrolledDate.toEpochMilli > 0)
          assert(trialStatus.expirationDate.toEpochMilli > 0)
          assertResult( expectedExpirationDate ) { trialStatus.expirationDate }
          assertResult(0) { trialStatus.terminatedDate.toEpochMilli }
          Future.successful(())
        }
        case _ => fail("should only be updating the enabled user")
      }
    }
  }

}

