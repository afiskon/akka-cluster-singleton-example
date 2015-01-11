package me.eax

import akka.util.Timeout
import scala.concurrent.duration._

package object akka_examples {
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val timeout = Timeout(5 seconds)
  implicit val executionContext = global

  def singletonManagerName = "singleton"
  def singletonManagerNodeRole: Option[String] = None // Some("worker") // можно пометитить, на каких нодах может быть запущен синглтон
  def remoteTimeActorName = "remoteTimeActor"

}
