import Dependencies._

val sparkVersion = "2.2.0"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.tuzhucheng",
      scalaVersion := "2.11.12",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "finesand",
    logLevel := Level.Warn,
    logBuffered in Test := false,
    resolvers ++= Seq(
      "apache-snapshots" at "http://repository.apache.org/snapshots/"
    ),
    libraryDependencies ++= Seq(
      scalaTest % Test,
      "org.apache.spark" %% "spark-core" % sparkVersion,
      "org.apache.spark" %% "spark-sql" % sparkVersion,
      "org.apache.spark" %% "spark-mllib" % sparkVersion,
      "org.rogach" %% "scallop" % "3.1.1",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2"
    ),
    assemblyMergeStrategy in assembly := {
     case PathList("META-INF", xs @ _*) => MergeStrategy.discard
     case x => MergeStrategy.first
    }
  )

