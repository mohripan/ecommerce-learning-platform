ThisBuild / scalaVersion := "3.3.1"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.ecommerce"

val AkkaVersion           = "2.8.5"
val AkkaHttpVersion       = "10.5.3"
val AlpakkaKafkaVersion   = "4.0.2"
val SlickVersion           = "3.4.1"
val CirceVersion           = "0.14.6"
val OpenTelemetryVersion   = "1.32.0"

lazy val root = (project in file("."))
  .settings(
    name := "analytics-service",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream"          % AkkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed"     % AkkaVersion,
      "com.typesafe.akka" %% "akka-http"            % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-slf4j"           % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream-kafka"    % AlpakkaKafkaVersion,
      "com.typesafe.slick" %% "slick"               % SlickVersion,
      "com.typesafe.slick" %% "slick-hikaricp"      % SlickVersion,
      "org.postgresql"      % "postgresql"           % "42.7.1",
      "io.spray"           %% "spray-json"           % "1.3.6",
      "ch.qos.logback"      % "logback-classic"      % "1.4.14",
      "io.opentelemetry"    % "opentelemetry-api"           % OpenTelemetryVersion,
      "io.opentelemetry"    % "opentelemetry-sdk"           % OpenTelemetryVersion,
      "io.opentelemetry"    % "opentelemetry-exporter-otlp" % OpenTelemetryVersion,
      "io.opentelemetry"    % "opentelemetry-sdk-extension-autoconfigure" % OpenTelemetryVersion,
      "com.typesafe.akka" %% "akka-testkit"         % AkkaVersion % Test,
      "org.scalatest"     %% "scalatest"             % "3.2.17"    % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings"
    )
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
