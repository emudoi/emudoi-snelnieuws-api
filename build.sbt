val ScalatraVersion = "2.8.4"
val DoobieVersion = "1.0.0-RC4"

ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.snelnieuws"

lazy val root = (project in file("."))
  .settings(
    name := "SnelNieuwsApi",
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      // Scalatra
      "org.scalatra" %% "scalatra" % ScalatraVersion,
      "org.scalatra" %% "scalatra-json" % ScalatraVersion,
      "org.json4s" %% "json4s-jackson" % "4.0.6",

      // Jetty
      "org.eclipse.jetty" % "jetty-webapp" % "11.0.15",
      "org.eclipse.jetty" % "jetty-server" % "11.0.15",
      "javax.servlet" % "javax.servlet-api" % "4.0.1" % "provided",

      // Doobie
      "org.tpolecat" %% "doobie-core" % DoobieVersion,
      "org.tpolecat" %% "doobie-hikari" % DoobieVersion,
      "org.tpolecat" %% "doobie-postgres" % DoobieVersion,

      // Cats Effect
      "org.typelevel" %% "cats-effect" % "3.5.2",

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.11",

      // Config
      "com.typesafe" % "config" % "1.4.2",

      // Testing
      "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test"
    ),

    assembly / assemblyJarName := "snelnieuws-api.jar",
    assembly / mainClass := Some("com.snelnieuws.JettyLauncher"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case x => MergeStrategy.first
    }
  )
