val ScalatraVersion     = "2.8.4"
val DoobieVersion       = "1.0.0-RC4"
val FlywayVersion       = "9.22.3"
val KafkaClientsVersion = "3.9.0"
val CirceVersion        = "0.14.6"

ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.snelnieuws"

lazy val root = (project in file("."))
  .settings(
    name := "emudoi-snelnieuws-api",
    version := "0.1.0-SNAPSHOT",
    mainClass := Some("com.snelnieuws.JettyLauncher"),
    libraryDependencies ++= Seq(
      "org.scalatra"            %% "scalatra"              % ScalatraVersion,
      "org.scalatra"            %% "scalatra-json"         % ScalatraVersion,
      "org.json4s"              %% "json4s-jackson"        % "4.0.6",
      "org.eclipse.jetty"        % "jetty-webapp"          % "9.4.53.v20231009" % "compile",
      "javax.servlet"            % "javax.servlet-api"     % "3.1.0"   % "provided",
      "org.tpolecat"            %% "doobie-core"           % DoobieVersion,
      "org.tpolecat"            %% "doobie-hikari"         % DoobieVersion,
      "org.tpolecat"            %% "doobie-postgres"       % DoobieVersion,
      "org.typelevel"           %% "cats-effect"           % "3.5.2",
      "com.typesafe"             % "config"                % "1.4.2",
      "ch.qos.logback"           % "logback-classic"       % "1.4.11",
      "org.flywaydb"             % "flyway-core"           % FlywayVersion,
      // Kafka consumer for summarized-article events from emudoi-snelnieuws-ingestion-api
      "org.apache.kafka"         % "kafka-clients"         % KafkaClientsVersion,
      "io.circe"                %% "circe-core"            % CirceVersion,
      "io.circe"                %% "circe-generic"         % CirceVersion,
      "io.circe"                %% "circe-parser"          % CirceVersion,
      // Pushy — JVM APNs (HTTP/2) client. Used to push notifications directly
      // to Apple, no Firebase indirection.
      "com.eatthepath"           % "pushy"                 % "0.15.4",
      // Firebase Admin — only used to verify Firebase Auth ID tokens that
      // the iOS app attaches to authenticated requests. Never used to send
      // notifications (those still go through Pushy → APNs directly).
      "com.google.firebase"      % "firebase-admin"        % "9.2.0",
      // Test
      "org.scalatra"            %% "scalatra-scalatest"              % ScalatraVersion % Test,
      "org.scalatest"           %% "scalatest"                       % "3.2.17"        % Test,
      "com.dimafeng"            %% "testcontainers-scala-scalatest"  % "0.41.0"        % Test,
      "com.dimafeng"            %% "testcontainers-scala-postgresql" % "0.41.0"        % Test
    ),
    // All test classes share one Postgres testcontainer + one Database singleton —
    // parallel suites would race on inserts and break "as_of_article_id" invariants.
    Test / parallelExecution := false,
    assembly / assemblyJarName := "emudoi-snelnieuws-api.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "reference.conf"                     => MergeStrategy.concat
      case _                                    => MergeStrategy.first
    }
  )
