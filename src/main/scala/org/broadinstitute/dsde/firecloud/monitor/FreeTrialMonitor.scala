package org.broadinstitute.dsde.firecloud.monitor

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.monitor.BootMonitors.logger
import org.broadinstitute.dsde.firecloud.monitor.FreeTrialMonitor.UpdateBillingReport
import org.broadinstitute.dsde.firecloud.service.TrialService

import scala.concurrent.ExecutionContext

object FreeTrialMonitor {
  def props()(implicit executionContext: ExecutionContext): Props = {
    Props(new FreeTrialMonitorActor())
  }

  sealed trait FreeTrialMessage
  case class UpdateBillingReport(spreadsheetId: String) extends FreeTrialMessage
}

class FreeTrialMonitorActor extends Actor with LazyLogging {

  implicit val executionContext: ExecutionContext

  override def receive = {
    case UpdateBillingReport(spradsheetId) => {
      if (FireCloudConfig.Trial.spreadsheetId.nonEmpty && FireCloudConfig.Trial.spreadsheetUpdateFrequencyMinutes > 0) {
        val freq = FireCloudConfig.Trial.spreadsheetUpdateFrequencyMinutes
        val scheduledTrialService = context.system.actorOf(FreeTrialMonitor.props(), "trial-spreadsheet-actor")
        // use a randomized startup delay to avoid multiple instances of this app executing on the same cycle
        val initialDelay = 1 + scala.util.Random.nextInt(freq/2)
        logger.info(s"Free credits spreadsheet updates are enabled: every $freq minutes, starting $initialDelay minutes from now.")
        context.system.scheduler.schedule(initialDelay.minutes, freq.minutes, scheduledTrialService, UpdateBillingReport(FireCloudConfig.Trial.spreadsheetId))
      } else {
        logger.info("Free credits spreadsheet id or update frequency not found in configuration. Spreadsheet updates are disabled for this instance.")
      }
    }
  }

}
