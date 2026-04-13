val ScalatraVersion = "2.8.4"
val DoobieVersion = "1.0.0-RC4"
val FlywayVersion = "9.22.3"

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
      // Test
      "org.scalatra"            %% "scalatra-scalatest"    % ScalatraVersion % Test
    ),
    assembly / assemblyJarName := "emudoi-snelnieuws-api.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "reference.conf"                     => MergeStrategy.concat
      case _                                    => MergeStrategy.first
    }
  )
