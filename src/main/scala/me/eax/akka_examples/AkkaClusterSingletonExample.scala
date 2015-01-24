package me.eax.akka_examples

import akka.actor._
import akka.event._
import akka.pattern._
import akka.contrib.pattern._
import scala.concurrent.duration._
import akka.cluster.ClusterEvent._
import akka.cluster._

class ClusterListener extends Actor with ActorLogging {
  val minClusterSize = 2 // TODO: read from config!
  val cluster = Cluster(context.system)
  var timerCancellable: Option[Cancellable] = None

  case class CheckClusterSize(msg: String)

  def checkClusterSize(msg: String) {
    val clusterSize = cluster.state.members.count { m =>
      m.status == MemberStatus.Up ||
        m.status == MemberStatus.Joining
    }
    log.info(s"[Listener] event: $msg, cluster size: $clusterSize " +
      s"(${cluster.state.members})")


    if(clusterSize < minClusterSize) {
      log.info(s"[Listener] cluster size is less than $minClusterSize" +
        ", shutting down!")
      context.system.shutdown()
    }
  }

  def scheduleClusterSizeCheck(msg: String) {
    timerCancellable.foreach(_.cancel())
    timerCancellable = Some(
      context.system.scheduler.scheduleOnce(
        1.second, self, CheckClusterSize(msg)
      )
    )
  }

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart() {
    log.info(s"[Listener] started!")
    cluster.subscribe(self, InitialStateAsEvents, classOf[MemberEvent],
      classOf[UnreachableMember])
  }

  override def postStop() {
    log.info("[Listener] stopped!")
    cluster.unsubscribe(self)
  }

  def receive = LoggingReceive {
    case msg: MemberEvent =>
      scheduleClusterSizeCheck(msg.toString)

    case msg: UnreachableMember =>
      scheduleClusterSizeCheck(msg.toString)

    case r: CheckClusterSize =>
      checkClusterSize(r.msg)
  }
}

case object GetTimeRequest
case class GetTimeResponse(time: Long)

class RemoteTimeActor extends Actor with ActorLogging {
  override def preStart() = {
    log.info("[RemoteTimeActor] started!")
  }

  def receive = LoggingReceive {
    case GetTimeRequest =>
      sender ! GetTimeResponse(System.currentTimeMillis())
  }
}

case object SyncTime
case object ScheduleSync

class LocalTimeActor extends Actor with ActorLogging {
  val remoteTimeActor = context.system.actorOf(
      ClusterSingletonProxy.props(
        singletonPath = s"/user/$managerName/$remoteTimeActorName",
        role = managerNodeRole
      ),
      name = s"${remoteTimeActorName}Proxy"
    )
  val syncPeriod = 5.seconds
  var time = System.currentTimeMillis()

  def scheduleSync() = {
    context.system.scheduler.scheduleOnce(syncPeriod, self, SyncTime)
  }

  override def preStart() = {
    log.info("[LocalTimeActor] started!")
    scheduleSync()
  }

  // see http://eax.me/akka-scheduler/
  override def postRestart(reason: Throwable) = {}

  def receive = LoggingReceive {
    case SyncTime =>
      val fResp = remoteTimeActor ? GetTimeRequest
      fResp pipeTo self
      fResp onComplete { case _ => self ! ScheduleSync }

    case ScheduleSync =>
      scheduleSync()

    case GetTimeResponse(remoteTime) =>
      log.info(s"[LocalTimeActor] sync: $remoteTime")
      time = remoteTime

    case GetTimeRequest =>
      sender ! GetTimeResponse(time)
  }
}

object AkkaClusterSingletonExample extends App {
  val system = ActorSystem("system")
  system.actorOf(Props[ClusterListener], name = "clusterListener")
  system.actorOf(ClusterSingletonManager.props(
      singletonProps = Props[RemoteTimeActor],
      singletonName = remoteTimeActorName,
      terminationMessage = PoisonPill,
      role = managerNodeRole
    ), name = managerName)
  system.actorOf(Props[LocalTimeActor], name = "localTimeActor")
  system.awaitTermination()
}
