package org.broadinstitute.dsde.firecloud

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.dataaccess.MockSamDAO
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.scalatest.concurrent.ScalaFutures
import akka.http.scaladsl.model.StatusCodes.{InternalServerError, NotFound}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

class HealthChecksSpec extends BaseServiceSpec with ScalaFutures {

  // brute-force logging off for the StartupChecks class while running this test
  org.slf4j.LoggerFactory.getLogger(new HealthChecks(app).getClass)
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  private def doChecks(healthChecks: HealthChecks) = {
    Future.sequence(Seq(healthChecks.maybeRegisterAdminSA)).map(_.exists(_.isDefined))
  }


  "Startup checks" - {
    val tokens = Map(
      "admin SA" -> app.googleServicesDAO.getAdminUserAccessToken
    )

    "When automatic registration of SAs is disabled" - {

      "should pass if all SAs are registered" in {
        val testApp = app.copy(samDAO = new StartupChecksMockSamDAO)
        val healthChecks = new HealthChecks(testApp, registerSAs = false)
        assert(!doChecks(healthChecks).futureValue)
      }

      tokens.foreach { case (name, token) =>
        s"should fail if $name is not ldap-enabled" in {
          val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
            unregisteredTokens = Seq(token)))
          val healthChecks = new HealthChecks(testApp, registerSAs = false)
          assert(doChecks(healthChecks).futureValue)
        }
      }
      "should fail if all SAs are not ldap-enabled" in {
        val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
          unregisteredTokens = tokens.values.toSeq))
        val healthChecks = new HealthChecks(testApp, registerSAs = false)
        assert(doChecks(healthChecks).futureValue)
      }

      tokens.foreach { case (name, token) =>
        s"should fail if $name is not registered at all (404)" in {
          val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
            notFounds = Seq(token)))
          val healthChecks = new HealthChecks(testApp, registerSAs = false)
          assert(doChecks(healthChecks).futureValue)
        }
      }
      "should fail if all SAs are not registered at all (404)" in {
        val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
          notFounds = tokens.values.toSeq))
        val healthChecks = new HealthChecks(testApp, registerSAs = false)
        assert(doChecks(healthChecks).futureValue)
      }

      tokens.foreach { case (name, token) =>
        s"should fail if $name returns unexpected error)" in {
          val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
            unexpectedErrors = Seq(token)))
          val healthChecks = new HealthChecks(testApp, registerSAs = false)
          assert(doChecks(healthChecks).futureValue)
        }
      }
      "should fail if all SAs return unexpected error" in {
        val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
          unexpectedErrors = tokens.values.toSeq))
        val healthChecks = new HealthChecks(testApp, registerSAs = false)
        assert(doChecks(healthChecks).futureValue)
      }
    }

    "When automatic registration of SAs is enabled" - {
      "should pass when all SAs need to be registered, and succeed" in {
        val mockDAO = new StartupChecksMockSamDAO(
            unregisteredTokens = tokens.values.toSeq)
        val testApp = app.copy(samDAO = mockDAO)
        val healthChecks = new HealthChecks(testApp, registerSAs = true)
        assert(!doChecks(healthChecks).futureValue)
        assertResult(tokens.values.toSet) {mockDAO.listRegisteredUsers().toSet}
      }
      "should pass when only one SA needs to be registered, and succeeds" in {
        val mockDAO = new StartupChecksMockSamDAO(
          notFounds = Seq(tokens.head._2))
        val testApp = app.copy(samDAO = mockDAO)
        val healthChecks = new HealthChecks(testApp, registerSAs = true)
        assert(!doChecks(healthChecks).futureValue)
        assertResult(Set(tokens.head._2)) {mockDAO.listRegisteredUsers().toSet}
      }
      "should fail if automatic registration fails for the single unregistered SA" in {
        val mockDAO = new StartupChecksMockSamDAO(
          notFounds = Seq(tokens.head._2),
          cantRegister = Seq(tokens.head._2))
        val testApp = app.copy(samDAO = mockDAO)
        val healthChecks = new HealthChecks(testApp, registerSAs = true)
        assert(doChecks(healthChecks).futureValue)
        assert(mockDAO.listRegisteredUsers().isEmpty)
      }
      "should fail if automatic registration fails for any of the unregistered SAs" in {
        val mockDAO = new StartupChecksMockSamDAO(
          notFounds = tokens.values.toSeq,
          cantRegister = Seq(tokens.head._2))
        val testApp = app.copy(samDAO = mockDAO)
        val healthChecks = new HealthChecks(testApp, registerSAs = true)
        assert(doChecks(healthChecks).futureValue)
        assertResult(tokens.tail.values.toSet) {mockDAO.listRegisteredUsers().toSet}
      }
    }

  }
}

class StartupChecksMockSamDAO(unregisteredTokens:Seq[String] = Seq.empty[String],
                              notFounds:Seq[String] = Seq.empty[String],
                              unexpectedErrors:Seq[String] = Seq.empty[String],
                              cantRegister:Seq[String] = Seq.empty[String]) extends MockSamDAO {

  val system = ActorSystem("StartupChecksSpec")
  val registerUserStateActor: ActorRef = system.actorOf(Props[RegisterTokenActor], name = "RegisterTokenActor")

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    if (notFounds.contains(userInfo.accessToken.token)) {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(NotFound, "unit test intentional failure")))
    } else {
      if (unexpectedErrors.contains(userInfo.accessToken.token)) {
        Future.failed(new Exception("unit test generic error"))
      } else {
        val isRegistered = !unregisteredTokens.contains(userInfo.accessToken.token)
        Future.successful(RegistrationInfo(
          WorkbenchUserInfo(userSubjectId = "foo", userEmail = "bar"),
          WorkbenchEnabled(google = true, ldap = isRegistered, allUsersGroup = true)))
      }
    }
  }

  override def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    if (cantRegister.contains(userInfo.accessToken.token)) {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(InternalServerError, "unit test intentional registration fail")))
    } else {
      registerUserStateActor ! RegisterUserToken(userInfo.accessToken.token)
      super.registerUser
    }
  }

  def listRegisteredUsers(): Seq[String] = {
    implicit val timeout: Timeout = Timeout(1 minute)
    val f = ask(registerUserStateActor, ListUserTokens).mapTo[Seq[String]]
    Await.result(f, timeout.duration)
  }

}

case class RegisterUserToken(entity: String)
case object ListUserTokens
class RegisterTokenActor extends Actor {

  private var entitySet = mutable.Set.empty[String]

  override def receive: Receive = {
    case RegisterUserToken(entity) => entitySet += entity
    case ListUserTokens => sender ! entitySet.toSeq
  }

}
