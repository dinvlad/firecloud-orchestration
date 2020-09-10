package org.broadinstitute.dsde.firecloud.monitor

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HealthChecks}
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor

object BootMonitors extends LazyLogging {

  def bootMonitors(system: ActorSystem): Unit = {
    startHealthChecks(system)
  }

  private def startHealthChecks(system: ActorSystem) = {
    val healthChecks = new HealthChecks(app)
    val healthMonitorChecks = healthChecks.healthMonitorChecks
    val healthMonitor = system.actorOf(HealthMonitor.props(healthMonitorChecks().keySet)( healthMonitorChecks ), "health-monitor")
    system.scheduler.schedule(3.seconds, 1.minute, healthMonitor, HealthMonitor.CheckAll)
  }

}
