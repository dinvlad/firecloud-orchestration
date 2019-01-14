package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.Metrics.LogitMetric
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.SamResource.{AccessPolicyName, UserPolicy}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

object SearchDAO {
  lazy val serviceName = "Search"
}

trait SearchDAO extends LazyLogging with ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(RawlsDAO.serviceName)

  def initIndex(): Unit
  def recreateIndex(): Unit
  def indexExists(): Boolean
  def createIndex(): Unit
  def deleteIndex(): Unit

  def bulkIndex(docs: Seq[Document], refresh:Boolean = false): LibraryBulkIndexResponse
  def indexDocument(doc: Document): Unit
  def deleteDocument(id: String): Unit
  def findDocuments(criteria: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy]): Future[LibrarySearchResponse]
  def suggestionsFromAll(criteria: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy]): Future[LibrarySearchResponse]
  def suggestionsForFieldPopulate(field: String, text: String): Future[Seq[String]]

  def statistics: LogitMetric

  override def serviceName:String = SearchDAO.serviceName
}
