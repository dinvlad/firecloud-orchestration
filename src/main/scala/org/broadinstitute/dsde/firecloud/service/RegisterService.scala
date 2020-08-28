package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.Trial.UserTrialStatus
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.Notifications.{ActivationNotification, NotificationFormat}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, RawlsUserSubjectId}

import scala.concurrent.{ExecutionContext, Future}

object RegisterService {

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new RegisterService(app.rawlsDAO, app.samDAO, app.thurloeDAO, app.trialDAO, app.googleServicesDAO)
}

class RegisterService(val rawlsDao: RawlsDAO, val samDao: SamDAO, val thurloeDao: ThurloeDAO, val trialDao: TrialDAO, val googleServicesDAO: GoogleServicesDAO)
  (implicit protected val executionContext: ExecutionContext) extends TrialServiceSupport with LazyLogging {

  def CreateUpdateProfile(userInfo: UserInfo, basicProfile: BasicProfile) = createUpdateProfile(userInfo, basicProfile)
  def UpdateProfilePreferences(userInfo: UserInfo, preferences: Map[String, String]) = updateProfilePreferences(userInfo, preferences)

  private def createUpdateProfile(userInfo: UserInfo, basicProfile: BasicProfile): Future[PerRequestMessage] = {
    for {
      _ <- thurloeDao.saveProfile(userInfo, basicProfile)
      _ <- thurloeDao.saveKeyValues(userInfo, Map("isRegistrationComplete" -> Profile.currentVersion.toString))
      isRegistered <- isRegistered(userInfo)
      userStatus <- if (!isRegistered.enabled.google || !isRegistered.enabled.ldap) {
        for {
          _ <- thurloeDao.saveKeyValues(userInfo,  Map("email" -> userInfo.userEmail))
          registerResult <- registerUser(userInfo)
        } yield registerResult
      } else {
        Future.successful(isRegistered)
      }
    } yield {
      RequestComplete(StatusCodes.OK, userStatus)
    }
  }

  private def isRegistered(userInfo: UserInfo): Future[RegistrationInfo] = {
    samDao.getRegistrationStatus(userInfo) recover {
      case e: FireCloudExceptionWithErrorReport if optAkka2sprayStatus(e.errorReport.statusCode) == Option(StatusCodes.NotFound) =>
        RegistrationInfo(WorkbenchUserInfo(userInfo.id, userInfo.userEmail), WorkbenchEnabled(false, false, false))
    }
  }

  private def registerUser(userInfo: UserInfo): Future[RegistrationInfo] = {
    for {
      registrationInfo <- samDao.registerUser(userInfo)
      freeCredits:Either[Exception,UserTrialStatus] <- enableSelfForFreeCredits(userInfo)
        .map(Right(_)) recover { case e: Exception => Left(e) }
      _ <- googleServicesDAO.publishMessages(FireCloudConfig.Notification.fullyQualifiedNotificationTopic, Seq(NotificationFormat.write(ActivationNotification(RawlsUserSubjectId(userInfo.id))).compactPrint))
    } yield {
      val messages:Option[List[String]] = freeCredits match {
        case Left(ex) => Some(registrationInfo.messages.getOrElse(List.empty[String]) :+
          s"Error enabling free credits during registration. Underlying error: ${ex.getMessage}")
        case Right(_) => registrationInfo.messages
      }
      registrationInfo.copy(messages = messages)
    }
  }

  /*  utility method to determine if a preferences key headed to Thurloe is valid for user input.
      Add whitelist logic here if you need to allow more keys.
      If we find we update this logic frequently, I suggest moving the predicates to a config file!
   */
  private def isValidPreferenceKey(key: String): Boolean = {
    key.startsWith("notifications/")
  }

  private def updateProfilePreferences(userInfo: UserInfo, preferences: Map[String, String]): Future[PerRequestMessage] = {
    if (preferences.keys.forall(isValidPreferenceKey)) {
      thurloeDao.saveKeyValues(userInfo, preferences).map(_ => RequestComplete(StatusCodes.NoContent))
    } else {
      throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "illegal preference key"))
    }
  }

}
