package org.broadinstitute.dsde.firecloud.metrics

import akka.actor.{Actor, Props}
import akka.pattern.pipe
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{LogitDAO, RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.firecloud.metrics.MetricsActor.RecordMetrics
import org.broadinstitute.dsde.firecloud.model.Metrics._
import org.joda.time.DateTime

import scala.concurrent.Future

object MetricsActor {
  sealed trait MetricsActorMessage
  case object RecordMetrics extends MetricsActorMessage

  def props(app: Application) = Props(new MetricsActor(app.logitDAO, app.rawlsDAO, app.searchDAO))

}

class MetricsActor(logitDAO: LogitDAO, rawlsDAO: RawlsDAO, searchDAO: SearchDAO) extends Actor with LazyLogging {
  import context.dispatcher

  override def receive: Receive = {
    case RecordMetrics => recordMetrics pipeTo sender
  }

  private def recordMetrics: Future[LogitMetric] = {
    /*  calculate the start/end date to use when querying rawls for stats.
        because the entity counts are not time-bound, we don't care what we send for start/end date, as long as
        we send legal values. So, send a 1-second span to make Rawls' SQL queries
        as light as possible.
     */
    val endDate = new DateTime()
    val startDate = endDate.minusSeconds(1)
    val workspaceNamespace = FireCloudConfig.Metrics.entityWorkspaceNamespace
    val workspaceName = FireCloudConfig.Metrics.entityWorkspaceName

    // get admin-stats from rawls
    rawlsDAO.adminStats(startDate, endDate, workspaceNamespace, workspaceName) flatMap { stats =>
      // out of all the metrics Rawls returns, we only care to extract the number of samples right now
      val numSamples = stats.statistics.currentEntityStatistics.entityStats.getOrElse("sample", 0)

      // get sum of samples from Library
      val numSubjects = searchDAO.statistics match {
        case ns:NumSubjects => ns.numSubjects
        case _ => 0
      }

      val metric = SamplesAndSubjects(numSamples = numSamples, numSubjects = numSubjects)

      logitDAO.recordMetric(metric) recover {
        case t:Throwable =>
          logger.warn(s"LogitDAO.recordMetric failure: ${t.getMessage}", t)
          NoopMetric
      }
    } recover {
      case t:Throwable =>
        logger.warn(s"MetricsActor.recordMetrics failure: ${t.getMessage}", t)
        NoopMetric
    }
  }

}
