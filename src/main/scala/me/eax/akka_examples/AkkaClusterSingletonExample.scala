package me.eax.akka_examples

import akka.actor._
import akka.event._
import akka.pattern._
import akka.contrib.pattern._
import scala.concurrent.duration._
import akka.cluster.ClusterEvent._
import akka.cluster._

class ClusterListener extends Actor with ActorLogging {
  var clusterSize = 0
  var minClusterSize = 1
  val cluster = Cluster(context.system)

  def updateClusterSize(msg: String) {
    clusterSize = cluster.state.members.count(m => m.status == MemberStatus.Up || m.status == MemberStatus.Joining)
    log.info(s"[Listener] event: $msg, cluster size: $clusterSize")

    val temp = clusterSize/2 + 1
    if(temp > minClusterSize) {
      log.info(s"[Listener] changing minimum cluster size from $minClusterSize to $temp")
      minClusterSize = temp
    } else if(clusterSize < minClusterSize) {
      log.info(s"[Listener] cluster size is less then $minClusterSize, shutting down...")
      context.system.shutdown()
    }
  }

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart() {
    cluster.subscribe(self, InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
    // updateClusterSize("(preStart call)")
  }

  override def postStop() {
    cluster.unsubscribe(self)
  }

  def receive = LoggingReceive {
    case msg: MemberEvent =>
      updateClusterSize(msg.toString)

    case msg: UnreachableMember =>
      updateClusterSize(msg.toString)
  }
}

case object GetTimeRequest
case class GetTimeResponse(time: Long)

class RemoteTimeActor extends Actor with ActorLogging {
  override def preStart() = {
    log.debug("RemoteTimeActor started!")
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
        singletonPath = s"/user/$singletonManagerName/$remoteTimeActorName",
        role = singletonManagerNodeRole
      ),
      name = s"${remoteTimeActorName}Proxy"
    )
  val syncPeriod = 5.seconds // 500.millis
  var time = System.currentTimeMillis()

  def scheduleSync() = {
    context.system.scheduler.scheduleOnce(syncPeriod, self, SyncTime)
  }

  override def preStart() = scheduleSync()

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
      role = singletonManagerNodeRole
    ), name = singletonManagerName)
  system.actorOf(Props[LocalTimeActor], name = "localTimeActor")
  system.awaitTermination()
}
