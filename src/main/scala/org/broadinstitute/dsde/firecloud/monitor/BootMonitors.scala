package org.broadinstitute.dsde.firecloud.monitor

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.duration._
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.TrialService

object BootMonitors extends LazyLogging {

  def bootMonitors(system: ActorSystem): Unit = {
    startFreeTrialServiceMonitor(system)
  }


  private def startFreeTrialServiceMonitor(system: ActorSystem) = {

  }

  private def startHealthChecks(system: ActorSystem) = {
    val healthChecks = new HealthChecks(app)
    val healthMonitorChecks = healthChecks.healthMonitorChecks
    val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)( healthMonitorChecks ), "health-monitor")
    system.scheduler.schedule(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)
  }


  //  private val healthChecks = new HealthChecks(app)
  //  val healthMonitorChecks = healthChecks.healthMonitorChecks
  //  val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)( healthMonitorChecks ), "health-monitor")
  //  system.scheduler.schedule(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)

}
