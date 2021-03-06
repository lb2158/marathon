package mesosphere.marathon.upgrade

import akka.Done
import akka.actor._
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.DeploymentStatus
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.{ AppStartCanceledException, SchedulerActions }

import scala.async.Async.{ async, await }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

class AppStartActor(
    val deploymentManager: ActorRef,
    val status: DeploymentStatus,
    val scheduler: SchedulerActions,
    val launchQueue: LaunchQueue,
    val instanceTracker: InstanceTracker,
    val eventBus: EventStream,
    val readinessCheckExecutor: ReadinessCheckExecutor,
    val runSpec: RunSpec,
    val scaleTo: Int,
    promise: Promise[Unit]) extends Actor with StartingBehavior with StrictLogging {

  override val nrToStart: Int = scaleTo

  @SuppressWarnings(Array("all")) // async/await
  override def initializeStart(): Future[Done] = async {
    // In case we already have running instances (can happen on master abdication during deployment)
    // with the correct version those will not be killed.
    val runningInstances = await(instanceTracker.specInstances(runSpec.id)).count(_.isActive)
    scheduler.startRunSpec(runSpec.withInstances(Math.max(runningInstances, nrToStart)))
    Done
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    super.postStop()
  }

  def success(): Unit = {
    logger.info(s"Successfully started $scaleTo instances of ${runSpec.id}")
    promise.success(())
    context.stop(self)
  }

  override def shutdown(): Unit = {
    if (!promise.isCompleted && promise.tryFailure(new AppStartCanceledException("The app start has been cancelled"))) {
      scheduler.stopRunSpec(runSpec).onFailure {
        case NonFatal(e) => logger.error(s"while stopping app ${runSpec.id}", e)
      }(context.dispatcher)
    }
    context.stop(self)
  }
}

object AppStartActor {
  @SuppressWarnings(Array("MaxParameters"))
  def props(
    deploymentManager: ActorRef,
    status: DeploymentStatus,
    scheduler: SchedulerActions,
    launchQueue: LaunchQueue,
    taskTracker: InstanceTracker,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    runSpec: RunSpec,
    scaleTo: Int,
    promise: Promise[Unit]): Props = {
    Props(new AppStartActor(deploymentManager, status, scheduler, launchQueue, taskTracker, eventBus,
      readinessCheckExecutor, runSpec, scaleTo, promise))
  }
}
