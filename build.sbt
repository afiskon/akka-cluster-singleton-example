name := "akka-cluster-singleton-example"

version := "0.1"

scalaVersion := "2.11.4"

val akkaVersion = "2.3.8"

libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster" % akkaVersion
  )

resolvers += "Akka Snapshots" at "http://repo.akka.io/snapshots/"
