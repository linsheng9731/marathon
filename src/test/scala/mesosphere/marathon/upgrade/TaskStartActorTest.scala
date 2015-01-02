package mesosphere.marathon.upgrade

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestKit }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.marathon.{ MarathonConf, SchedulerActions, TaskUpgradeCanceledException }
import org.apache.mesos.SchedulerDriver
import org.apache.mesos.state.InMemoryState
import org.mockito.Mockito.{ times, spy, verify }
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class TaskStartActorTest
    extends TestKit(ActorSystem("System"))
    with FunSuiteLike
    with Matchers
    with MockitoSugar
    with BeforeAndAfterAll {

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Start success") {
    val driver = mock[SchedulerDriver]
    val scheduler = mock[SchedulerActions]
    val taskQueue = new TaskQueue
    val registry = new MetricRegistry
    val taskTracker = new TaskTracker(new InMemoryState, mock[MarathonConf], registry)
    val promise = Promise[Unit]()
    val app = AppDefinition("myApp".toPath, instances = 5)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with tasks in taskQueue") {
    val driver = mock[SchedulerDriver]
    val scheduler = mock[SchedulerActions]
    val taskQueue = new TaskQueue
    val registry = new MetricRegistry
    val taskTracker = new TaskTracker(new InMemoryState, mock[MarathonConf], registry)
    val promise = Promise[Unit]()
    val app = AppDefinition("myApp".toPath, instances = 5)

    taskQueue.add(app)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with no instances to start") {
    val driver = mock[SchedulerDriver]
    val scheduler = mock[SchedulerActions]
    val taskQueue = new TaskQueue
    val registry = new MetricRegistry
    val taskTracker = new TaskTracker(new InMemoryState, mock[MarathonConf], registry)
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp".toPath, instances = 0)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks") {
    val driver = mock[SchedulerDriver]
    val scheduler = mock[SchedulerActions]
    val taskQueue = new TaskQueue
    val registry = new MetricRegistry
    val taskTracker = new TaskTracker(new InMemoryState, mock[MarathonConf], registry)
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp".toPath, instances = 5)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      true,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(HealthStatusChanged(app.id, s"task_$i", app.version.toString, alive = true))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks with no instances to start") {
    val driver = mock[SchedulerDriver]
    val scheduler = mock[SchedulerActions]
    val taskQueue = new TaskQueue
    val registry = new MetricRegistry
    val taskTracker = new TaskTracker(new InMemoryState, mock[MarathonConf], registry)
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp".toPath, instances = 0)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      true,
      promise))

    watch(ref)

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Cancelled") {
    val driver = mock[SchedulerDriver]
    val scheduler = mock[SchedulerActions]
    val taskQueue = new TaskQueue
    val registry = new MetricRegistry
    val taskTracker = new TaskTracker(new InMemoryState, mock[MarathonConf], registry)
    val promise = Promise[Boolean]()
    val app = AppDefinition("myApp".toPath, instances = 5)

    val ref = system.actorOf(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }

  test("Task fails to start") {
    val driver = mock[SchedulerDriver]
    val scheduler = mock[SchedulerActions]
    val taskQueue = spy(new TaskQueue)
    val registry = new MetricRegistry
    val taskTracker = new TaskTracker(new InMemoryState, mock[MarathonConf], registry)
    val promise = Promise[Unit]()
    val app = AppDefinition("myApp".toPath, instances = 1)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      false,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 1, 3.seconds)

    taskQueue.purge(app.id)

    system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_FAILED", "", app.id, "", Nil, app.version.toString))

    awaitCond(taskQueue.count(app.id) == 1, 3.seconds)

    verify(taskQueue, times(2)).add(app, 1)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }
}
