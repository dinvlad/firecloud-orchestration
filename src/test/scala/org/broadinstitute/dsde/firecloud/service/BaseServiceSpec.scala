package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.{Application, FireCloudApiService}
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.scalatest.BeforeAndAfter

class BaseServiceSpec extends ServiceSpec with BeforeAndAfter {

  // this gets fed into sealRoute so that exceptions are handled the same in tests as in real life
  implicit val exceptionHandler = FireCloudApiService.exceptionHandler

  val agoraDao:MockAgoraDAO = new MockAgoraDAO
  val googleServicesDao:MockGoogleServicesDAO = new MockGoogleServicesDAO
  val ontologyDao:MockOntologyDAO = new MockOntologyDAO
  val consentDao:MockConsentDAO = new MockConsentDAO
  val rawlsDao:MockRawlsDAO = new MockRawlsDAO
  val samDao:MockSamDAO = new MockSamDAO
  val searchDao:MockSearchDAO = new MockSearchDAO
  val researchPurposeSupport:MockResearchPurposeSupport = new MockResearchPurposeSupport
  val thurloeDao:MockThurloeDAO = new MockThurloeDAO
  val logitDao:MockLogitDAO = new MockLogitDAO
  val shareLogDao:MockShareLogDAO = new MockShareLogDAO
  val importServiceDao:MockImportServiceDAO = new MockImportServiceDAO

  val app:Application =
    new Application(agoraDao, googleServicesDao, ontologyDao, consentDao, rawlsDao, samDao, searchDao, researchPurposeSupport, thurloeDao, logitDao, shareLogDao, importServiceDao)

}
