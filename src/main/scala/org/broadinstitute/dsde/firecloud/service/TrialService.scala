package org.broadinstitute.dsde.firecloud.service

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes._
import akka.pattern._
import akka.util.Timeout
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SamDAO, ThurloeDAO, _}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impCreateProjectsResponse, impTrialProject}
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses.CreationStatus
import org.broadinstitute.dsde.firecloud.model.Trial.StatusUpdate.Attempt
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enrolled, Terminated}
import org.broadinstitute.dsde.firecloud.model.Trial.{StatusUpdate, TrialStates, UserTrialStatus, _}
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RequestCompleteWithErrorReport, UserInfo, WithAccessToken, WorkbenchUserInfo, _}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.trial.ProjectManager.StartCreation
import org.broadinstitute.dsde.firecloud.utils.PermissionsSupport
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, RawlsBillingProjectName, RawlsUserEmail}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// TODO: Contain userEmail in value class for stronger type safety without incurring performance penalty
object TrialService {
  def constructor(app: Application, projectManager: ActorRef)()(implicit executionContext: ExecutionContext) =
    new TrialService(app.samDAO, app.thurloeDAO, app.rawlsDAO, app.trialDAO, app.googleServicesDAO, projectManager)
}

final class TrialService
  (val samDao: SamDAO, val thurloeDao: ThurloeDAO, val rawlsDAO: RawlsDAO,
   val trialDao: TrialDAO, val googleDAO: GoogleServicesDAO, projectManager: ActorRef)
  (implicit protected val executionContext: ExecutionContext)
  extends PermissionsSupport with SprayJsonSupport with TrialServiceSupport with LazyLogging {

  def EnableUsers(managerInfo: UserInfo, userEmails: Seq[String]) = asTrialCampaignManager(enableUsers(managerInfo, userEmails))(managerInfo)
  def DisableUsers(managerInfo: UserInfo, userEmails: Seq[String]) = asTrialCampaignManager(disableUsers(managerInfo, userEmails))(managerInfo)
  def EnrollUser(userInfo: UserInfo) = enrollUser(userInfo)
  def TerminateUsers(managerInfo: UserInfo, userEmails: Seq[String]) = asTrialCampaignManager(terminateUsers(managerInfo, userEmails))(managerInfo)
  def FinalizeUser(userInfo: UserInfo) = finalizeUser(userInfo)
  def CreateProjects(userInfo: UserInfo, count: Int) = asTrialCampaignManager {createProjects(count)}(userInfo)
  def VerifyProjects(userInfo: UserInfo) = asTrialCampaignManager {verifyProjects}(userInfo)
  def CountProjects(userInfo: UserInfo) = asTrialCampaignManager {countProjects}(userInfo)
  def AdoptProject(userInfo: UserInfo, projectName: String) = asTrialCampaignManager {adoptProject(projectName)}(userInfo)
  def ScratchProject(userInfo: UserInfo, projectName: String) = asTrialCampaignManager {scratchProject(projectName)}(userInfo)
  def Report(userInfo: UserInfo) = asTrialCampaignManager {projectReport}(userInfo)
  def RecordUserAgreement(userInfo: UserInfo) = recordUserAgreement(userInfo)
  def UpdateBillingReport(spreadsheetId: String) = updateBillingReport(spreadsheetId)
//    case x => throw new FireCloudException("unrecognized message: " + x.toString)

  private def enableUserPostProcessing(updateStatus: Attempt, prevStatus: UserTrialStatus, newStatus: UserTrialStatus): Unit = {
    if (updateStatus != StatusUpdate.Success && newStatus.billingProjectName.isDefined) {
      logger.warn(
        s"[trialaudit] The user '${newStatus.userId}' failed to be enabled, releasing the billing project '${newStatus.billingProjectName.get}' back into the available pool.")
      trialDao.releaseProjectRecord(RawlsBillingProjectName(newStatus.billingProjectName.get))
    }
  }

  private def enableUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    val numAvailable:Long = trialDao.countProjects.getOrElse("available", 0L)
    if (userEmails.size.toLong > numAvailable) {
      Future(RequestCompleteWithErrorReport(BadRequest, s"You are enabling ${userEmails.size} users, but there are only " +
        s"$numAvailable projects available. Please create more projects."))
    } else {
      // buildEnableUserStatus is located in TrialServiceSupport
      executeStateTransitions(managerInfo, userEmails, buildEnableUserStatus, enableUserPostProcessing)
    }
  }

  private def buildDisableUserStatus(userInfo: WorkbenchUserInfo, currentStatus: UserTrialStatus): UserTrialStatus = {
    currentStatus.copy(state = Some(Disabled), billingProjectName = None)
  }

  private def disableUserPostProcessing(updateStatus: Attempt, prevStatus: UserTrialStatus, newStatus: UserTrialStatus): Unit = {
    if (updateStatus == StatusUpdate.Success) {
      prevStatus.billingProjectName match {
        case None => None
        case Some(name) =>
          logger.info(
            s"[trialaudit] The user '${newStatus.userId}' was disabled, releasing the billing project '$name' back into the available pool.")
          trialDao.releaseProjectRecord(RawlsBillingProjectName(name))
      }
    }
  }

  private def disableUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    executeStateTransitions(managerInfo, userEmails, buildDisableUserStatus, disableUserPostProcessing)
  }

  private def buildTerminateUserStatus(userInfo: WorkbenchUserInfo, currentStatus: UserTrialStatus): UserTrialStatus = {
    if (currentStatus.state.contains(Enrolled)) {
      if (currentStatus.billingProjectName.isEmpty)
        throw new FireCloudException(s"billing project empty for user ${userInfo.userEmail} at termination.")
      val projectId = currentStatus.billingProjectName.get

      // disassociate billing for this project. This will throw an error if disassociation fails
      val removalResult = googleDAO.trialBillingManagerRemoveBillingAccount(projectId, userInfo.userEmail)
      if (!removalResult)
        logger.info(s"[trialaudit] for user ${userInfo.userEmail} (${userInfo.userSubjectId}), removed billing from project $projectId")
      removalResult
    }

    currentStatus.copy(state = Some(Terminated), terminatedDate = Instant.now)
  }

  private def terminateUserPostProcessing(updateStatus: Attempt, prevStatus: UserTrialStatus, newStatus: UserTrialStatus): Unit = {
    if (updateStatus != StatusUpdate.Success) {
      logger.error(
        s"[trialaudit] The user '${newStatus.userId}' had billing disassociated, but failed to be terminated. This" +
         "user requires manual intervention from a campaign manager.")
    }
  }

  private def terminateUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    executeStateTransitions(managerInfo, userEmails, buildTerminateUserStatus, terminateUserPostProcessing)
  }

  private def requiresStateTransition(currentState: UserTrialStatus, newState: UserTrialStatus): Boolean = {
    // this check needs to happen first for idempotent behavior
    if (currentState.state == newState.state)
      false
    else {
      // make sure the state transition is valid
      if (newState.state.isEmpty || !newState.state.get.isAllowedFrom(currentState.state))
        throw new FireCloudException(s"Cannot transition from ${currentState.state} to $newState.state.get")
      true // indicates transition is required
    }
  }

  private def throwableToStatus(t: Throwable): String = {
    t match {
      case exr: FireCloudExceptionWithErrorReport =>
        if (exr.errorReport.statusCode.contains(NotFound))
          StatusUpdate.toName(StatusUpdate.Failure("User not registered"))
        else StatusUpdate.toName(StatusUpdate.Failure(exr.errorReport.message))
      case ex: FireCloudException =>
        StatusUpdate.toName(StatusUpdate.Failure(ex.getMessage))
      case _ =>
        StatusUpdate.toName(StatusUpdate.ServerError(t.getMessage))
    }
  }

  private def executeStateTransitions(managerInfo: UserInfo, userEmails: Seq[String],
                                      statusTransition: (WorkbenchUserInfo, UserTrialStatus) => UserTrialStatus,
                                      transitionPostProcessing: (Attempt, UserTrialStatus, UserTrialStatus) => Unit): Future[PerRequestMessage] = {

    def checkAndUpdateState(userInfo: WorkbenchUserInfo, userTrialStatus: UserTrialStatus, newStatus: UserTrialStatus): Future[String] = {
      Try(requiresStateTransition(userTrialStatus, newStatus)) match {
        case Success(isRequired) =>
          if (isRequired) {
            updateTrialStatus(managerInfo, userInfo, newStatus) map { stateResponse =>
              transitionPostProcessing(stateResponse, userTrialStatus, newStatus)
              StatusUpdate.toName(stateResponse)

            }
          } else {
            logger.info(s"The user '${userInfo.userEmail}' is already in the trial state of '${newStatus.state.getOrElse("")}'. No further action will be taken.")
            Future.successful(StatusUpdate.toName(StatusUpdate.NoChangeRequired))
          }
        case Failure(t: Throwable) => Future.successful(throwableToStatus(t))
      }
    }

    val userTransitions = userEmails.map { userEmail =>
      val status = Try(for {
        // use Sam's GET /api/users/v1/{email} to get both the userSubjectId and the googleSubjectId
        userIds <- samDao.getUserIds(RawlsUserEmail(userEmail))(managerInfo)
        subId = userIds.userSubjectId
        userInfo = WorkbenchUserInfo(subId, userEmail)
        // Thurloe requires the googleSubjectId from Sam
        userTrialStatus <- thurloeDao.getTrialStatus(userIds.googleSubjectId, managerInfo)
        newStatus = statusTransition(userInfo, userTrialStatus)
        result <- checkAndUpdateState(userInfo, userTrialStatus, newStatus)
      } yield result) match {
        case Success(s) => s
        case Failure(t: Throwable) =>
          Future.successful(throwableToStatus(t))
      }
      status map { finalStatus: String => (finalStatus, userEmail) } recover {
        case ex: Exception =>
          (StatusUpdate.toName(StatusUpdate.ServerError(ex.getMessage)), userEmail)
      }
    }

    Future.sequence(userTransitions) map { results =>
      val sorted: Map[String, Seq[String]] = results.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) }
      RequestComplete(sorted)
    }
  }

  private def updateTrialStatus(managerInfo: UserInfo,
                                userInfo: WorkbenchUserInfo,
                                updatedTrialStatus: UserTrialStatus): Future[StatusUpdate.Attempt] = {
    thurloeDao.saveTrialStatus(updatedTrialStatus.userId, managerInfo, updatedTrialStatus) map {
      case Success(_) =>
        logger.info(s"[trialaudit] updated user ${userInfo.userEmail} (${updatedTrialStatus.userId}) to state ${updatedTrialStatus.state.getOrElse("")}")
        StatusUpdate.Success
      case Failure(ex) => StatusUpdate.ServerError(ex.getMessage)
    }
  }

  private def enrollUser(userInfo: UserInfo): Future[PerRequestMessage] = {
    // get user's trial status, then check the current state
    thurloeDao.getTrialStatus(userInfo.id, userInfo) flatMap { status =>
      // can't determine the user's trial status; don't enroll
      status.state match {
          // user already enrolled; don't re-enroll
          case Some(TrialStates.Enrolled) => Future(RequestCompleteWithErrorReport(BadRequest, "You are already enrolled in a free trial. (Error 20)"))
          // user enabled (eligible) for trial, enroll!
          case Some(TrialStates.Enabled) => {
            if (status.userAgreed) {
              enrollUserInternal(userInfo, status)
            } else {
              Future(RequestCompleteWithErrorReport(Forbidden, "You must agree to the trial terms to enroll. Please try again. (Error 25)"))
            }
          }
          // user in some other state; don't enroll
          case Some(TrialStates.Disabled) => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 30)"))
          case Some(TrialStates.Terminated) => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 40)"))
          case Some(TrialStates.Finalized) => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 45)"))
          case _ => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 50)"))
        }
    }
  }

  private def enrollUserInternal(userInfo: UserInfo, enabledStatus: UserTrialStatus): Future[PerRequestMessage] = {

    val statusFuture = getOrClaimProject(userInfo, enabledStatus)

    val saToken: WithAccessToken = AccessToken(googleDAO.getTrialBillingManagerAccessToken)

    statusFuture flatMap { status =>
      val projectId = status.billingProjectName.get // if we made it to this line, get should be safe
      // 1. Check that the assigned Billing Project exists and contains exactly one member, the SA we used to create it
      rawlsDAO.getProjectMembers(projectId)(saToken) flatMap { members: Seq[RawlsBillingProjectMember] =>
        if (members.map(_.email.value) != Seq(googleDAO.getTrialBillingManagerEmail)) {
          // TODO: for resiliency, try running this operation again with a new project
          logger.warn(s"Cannot add user ${userInfo.userEmail} to billing project $projectId because it already contains members [${members.map(_.email.value).mkString(", ")}]")
          Future(RequestCompleteWithErrorReport(InternalServerError, "We could not process your enrollment. Please contact support. (Error 60)"))
        } else {
          // 2. Add the user as Owner to the assigned Billing Project
          rawlsDAO.addUserToBillingProject(projectId, ProjectRoles.Owner, userInfo.userEmail)(userToken = saToken) flatMap { _ =>
            logger.info(s"[trialaudit] added user ${userInfo.userEmail} (${userInfo.id}) to project $projectId")
            // 3. Update the user's Thurloe profile to indicate Enrolled status
            val updatedTrialStatus = enrolledStatusFromStatus(status)
            thurloeDao.saveTrialStatus(userInfo.id, userInfo, updatedTrialStatus) flatMap {
              case Success(_) =>
                logger.info(s"[trialaudit] updated user ${userInfo.userEmail} (${userInfo.id}) to state ${updatedTrialStatus.state.getOrElse("")}")
                Future(RequestComplete(NoContent)) // <- SUCCESS!
              case Failure(profileUpdateFail) => {
                // We couldn't update trial status, so clean up
                rawlsDAO.removeUserFromBillingProject(projectId, ProjectRoles.Owner, userInfo.userEmail)(userToken = saToken) map { _ =>
                  logger.warn(s"Enrolling user ${userInfo.userEmail} failed at profile update: ${profileUpdateFail.getMessage}. User has been backed out of billing project $projectId.")
                  RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. Please try again later. (Error 70)")
                } recover {
                  case bpFail: Throwable => {
                    logger.warn(s"Enrolling user ${userInfo.userEmail} failed at profile update: ${profileUpdateFail.getMessage}. User is still in billing project $projectId due to cleanup failure: ${bpFail.getMessage}.")
                    RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. Please contact support. (Error 80)")
                  }
                }
              }
            }
          } recover {
            case t: Throwable => {
              logger.error(s"Attempt to add user ${userInfo.userEmail} to project $projectId failed: ${t.getMessage}. User profile has not been modified.")
              RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. Please try again later. (Error 90)")
            }
          }
        }
      } recover {
        case t: Throwable => {
          logger.warn(s"Failed to list members of project $projectId on behalf of user ${userInfo.userEmail}: ${t.getMessage}")
          RequestCompleteWithErrorReport(InternalServerError, "We could not process your enrollment. Please try again later. (Error 110)")
        }
      }
    } recover {
      case t: Throwable => {
        logger.warn(s"Failed to read or claim project on behalf of user ${userInfo.userEmail}: ${t.getMessage}")
        RequestCompleteWithErrorReport(InternalServerError, "We could not process your enrollment. Please try again later. (Error 112)")
      }
    }
  }

  private def getOrClaimProject(userInfo: UserInfo, enabledStatus: UserTrialStatus): Future[UserTrialStatus] = {
    enabledStatus.billingProjectName match {
      case Some(_) => Future.successful(enabledStatus)
      case None => {
        Try(claimProjectWithRetries(WorkbenchUserInfo(userInfo.id, userInfo.userEmail))) match {
          case Success(claimed) =>
            // persist claimed project name to user's profile
            val updatedStatus = enabledStatus.copy(billingProjectName = Some(claimed.name.value))
            thurloeDao.saveTrialStatus(userInfo.id, userInfo, updatedStatus) map {
              case Success(_) => updatedStatus
              case Failure(ex) => throw new FireCloudException("We could not process your enrollment. Please contact support. (Error 58)", ex)
            }
          case Failure(ex) =>
            logger.warn(s"User ${userInfo.userEmail} attempted to enroll in trial but no billing project in profile," +
              s" and a project could not be claimed: ${ex.getMessage}")
            throw new FireCloudException("We could not process your enrollment. Please contact support. (Error 56)", ex)
        }

      }
    }
  }

  private def enrolledStatusFromStatus(status: UserTrialStatus): UserTrialStatus = {
    // build the new state that we want to persist to indicate the user is enrolled
    val now = Instant.now
    val expirationDate = now.plus(FireCloudConfig.Trial.durationDays, ChronoUnit.DAYS)
    status.copy(
      state = Some(TrialStates.Enrolled),
      enrolledDate = now,
      expirationDate = expirationDate
    )
  }

  private def finalizeUser(userInfo: UserInfo): Future[PerRequestMessage] = {
    import TrialStates._

    // Get user's trial status, check and update the current state if it's a valid transition
    // NB: We are being lenient and are not complaining when a user was already 'finalized' previously
    thurloeDao.getTrialStatus(userInfo.id, userInfo) flatMap { status =>
      val state = status.state
      (Finalized.isAllowedFrom(state), state.contains(Terminated)) match {
        case (true, true) =>
          thurloeDao.saveTrialStatus(userInfo.id, userInfo, status.copy(state = Some(Finalized))) flatMap {
            case Success(_) => Future(RequestComplete(NoContent))
            case Failure(ex) => Future(RequestComplete(InternalServerError, ex.getMessage))
          }
        case (true, false) => Future(RequestComplete(NoContent))
        case _ => Future(RequestCompleteWithErrorReport(BadRequest, "Your free trial should have been terminated first."))
      }
    }
  }

  private def createProjects(count: Int): Future[PerRequestMessage] = {
    implicit val timeout:Timeout = 1.minute // timeout to get a response from projectManager
    val create = projectManager ? StartCreation(count)
    create.map {
      case c:CreateProjectsResponse if c.success => RequestComplete(Accepted, c)
      case c:CreateProjectsResponse if !c.success => RequestComplete(BadRequest, c)
      case _ => RequestComplete(InternalServerError)
    }
  }

  private def verifyProjects: Future[PerRequestMessage] = {

    val saToken:WithAccessToken = AccessToken(googleDAO.getTrialBillingManagerAccessToken)
    rawlsDAO.getProjects(saToken) map { projects =>

      val projectStatuses:Map[RawlsBillingProjectName, CreationStatus] = projects.map { proj =>
        proj.projectName -> proj.creationStatus
      }.toMap

      // get unverified projects from the pool
      val unverified = trialDao.listUnverifiedProjects

      unverified.foreach { unv =>
        // get status from the rawls map
        projectStatuses.get(unv.name) match {
          case Some(CreationStatuses.Creating) => // noop
          case Some(CreationStatuses.Error) =>
            logger.warn(s"project ${unv.name.value} errored, so we have to give up on it.")
            trialDao.setProjectRecordVerified(unv.name, verified=true, status = CreationStatuses.Error)
          case Some(CreationStatuses.Ready) =>
            trialDao.setProjectRecordVerified(unv.name, verified=true, status = CreationStatuses.Ready)
          case None =>
            logger.warn(s"project ${unv.name.value} exists in pool but not found via Rawls!")
        }
      }

      RequestComplete(OK, trialDao.countProjects)
    }
  }

  private def countProjects: Future[PerRequestMessage] =
    Future(RequestComplete(OK, trialDao.countProjects))

  /**
    * When supplied with a project name, enter a record that references that project into our pool. This method
    * assumes that the project-being-referenced exists (we don't verify that) and is in good working order (we
    * don't verify that either). If you supply a project name that already exists in the pool, we check to see
    * if the project record is already claimed by a free-tier user. We'll respond with an error if the project
    * is claimed; if it isn't, we'll mark the project as verified (i.e. available for use), even if it was
    * previously not verified.
    *
    * @param projectName project to be adopted
    * @return PerRequestMessage wrapping either the upserted project record or an ErrorReport
    */
  private def adoptProject(projectName: String): Future[PerRequestMessage] = {
    val project = RawlsBillingProjectName(projectName)
    val record = Try(trialDao.getProjectRecord(project)) match {
      case Success(p) => p // project exists!
      case Failure(ex) =>
        if (ex.getMessage.contains("not found")) {
          trialDao.insertProjectRecord(project)
          trialDao.getProjectRecord(project)
        } else {
          throw ex
        }
    }
    if (record.user.isDefined) {
      Future(RequestCompleteWithErrorReport(BadRequest,
        s"adopted project '$projectName' is already claimed by user '${record.user.get}'!"))
    } else {
      // set project verified
      trialDao.setProjectRecordVerified(record.name, true, Trial.CreationStatuses.Ready)
      Future(RequestComplete(OK, trialDao.getProjectRecord(record.name)))
    }
  }

  /**
    * When supplied with a project that has a record in the pool, mark that project as being in error. This results
    * in the project being unavailable for users to claim for their free trial. This scratch method also
    * disassociates the project with any user that had previously claimed it. THIS DISASSOCIATION IS A DESTRUCTIVE
    * AND IRREVERSIBLE OPERATION, so do not use it without being sure that's exactly what you want to do.
    *
    * Will respond with an error if the project-to-be-scratched does not exist in the pool.
    *
    * @param projectName project to be scratched
    * @return PerRequestMessage wrapping either the updated project record or an ErrorReport
    */
  private def scratchProject(projectName: String): Future[PerRequestMessage] = {
    val project = RawlsBillingProjectName(projectName)
    Try(trialDao.getProjectRecord(project)) match {
      case Success(p) =>
        // project exists; set it to error state
        trialDao.setProjectRecordVerified(project, true, Trial.CreationStatuses.Error)
        // ensure project is released
        trialDao.releaseProjectRecord(project)
        Future(RequestComplete(OK, trialDao.getProjectRecord(project)))
      case Failure(ex) =>
        // project doesn't exist or there is some other error querying the pool.
        Future(RequestCompleteWithErrorReport(InternalServerError, s"error scratching project '$projectName'", ex))
    }
  }

  private def projectReport: Future[PerRequestMessage] =
    Future(RequestComplete(OK, trialDao.projectReport))

  private def recordUserAgreement(userInfo: UserInfo): Future[PerRequestMessage] = {
    // Thurloe errors are handled by the caller of this method
    thurloeDao.getTrialStatus(userInfo.id, userInfo) flatMap { status =>
        status.state match {
          case Some(TrialStates.Enabled) =>
            thurloeDao.saveTrialStatus(userInfo.id, userInfo, status.copy(userAgreed = true)) flatMap {
              case Success(_) => Future(RequestComplete(NoContent))
              case Failure(ex) => Future(RequestComplete(InternalServerError, ex.getMessage))
            }
          case _ => Future(RequestCompleteWithErrorReport(Forbidden, "You are not eligible for a free trial."))
        }
    }
  }

  /** This method operates with service account credentials and can thus be invoked by anyone.
    * As the developer, be careful about when you call this.
    */
  private def updateBillingReport(spreadsheetId: String): Future[SpreadsheetResponse] = {
    // get SA credential
    val saToken = AccessToken(googleDAO.getTrialSpreadsheetAccessToken)

    val majorDimension: String = "ROWS"
    val range: String = "Sheet1!A1"
    makeSpreadsheetValues(saToken, trialDao, thurloeDao, majorDimension, range).map { content =>
      Try (googleDAO.updateSpreadsheet(spreadsheetId, content)) match {
        case Success(updatedSheet) =>
          logger.info(s"Successfully updated spreadsheet [$spreadsheetId].")
          makeSpreadsheetResponse(spreadsheetId)
        case Failure(e) =>
          e match {
            case g: GoogleJsonResponseException =>
              logger.error(s"Unable to update spreadsheet [$spreadsheetId]: ${g.getDetails.getMessage}", e)
              val code = StatusCodes.getForKey(g.getDetails.getCode).getOrElse(InternalServerError)
              throw new FireCloudExceptionWithErrorReport(ErrorReport(code, e.getMessage))
            case _ => throw e
          }
      }
    }.recoverWith {
      case e: Throwable =>
        logger.error(s"Unable to update google spreadsheet [$spreadsheetId]: ${e.getMessage}", e)
        throw new FireCloudExceptionWithErrorReport(ErrorReport(InternalServerError, e.getMessage))
    }
  }

}
