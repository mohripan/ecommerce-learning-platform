val scala3Version              = "3.3.3"
val akkaVersion                = "2.9.4"
val akkaHttpVersion            = "10.6.3"
val alpakkaKafkaVersion        = "6.0.0"
val slickVersion               = "3.5.1"
val akkaPersistenceJdbcVersion = "5.4.1"
val sprayJsonVersion           = "1.3.6"
val otelVersion                = "1.40.0"
val logbackVersion             = "1.5.6"
val scalatestVersion           = "3.2.18"

ThisBuild / scalaVersion  := scala3Version
ThisBuild / version       := "0.1.0-SNAPSHOT"
ThisBuild / organization  := "com.ecommerce"

// Lightbend's private repository is required for Akka 2.9.x artifacts.
ThisBuild / resolvers += "Akka library repository".at("https://repo.akka.io/maven")

lazy val root = (project in file("."))
  .settings(
    name := "inventory-service",

    // ── Core Akka Typed ────────────────────────────────────────────────────
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed"            % akkaVersion,
      "com.typesafe.akka" %% "akka-stream"                 % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-typed"          % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed"      % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query"      % akkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson"  % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"                  % akkaVersion,
    ),

    // ── Akka HTTP ──────────────────────────────────────────────────────────
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
    ),

    // ── Akka Persistence JDBC (event journal + snapshot store) ────────────
    libraryDependencies += "com.lightbend.akka" %% "akka-persistence-jdbc" % akkaPersistenceJdbcVersion,

    // ── Slick (read-side projections) ──────────────────────────────────────
    libraryDependencies ++= Seq(
      "com.typesafe.slick" %% "slick"          % slickVersion,
      "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    ),

    // ── Alpakka Kafka ──────────────────────────────────────────────────────
    libraryDependencies += "com.typesafe.akka" %% "akka-stream-kafka" % alpakkaKafkaVersion,

    // ── PostgreSQL driver ──────────────────────────────────────────────────
    libraryDependencies += "org.postgresql" % "postgresql" % "42.7.3",

    // ── JSON (spray-json for HTTP, Jackson for Akka persistence) ──────────
    libraryDependencies += "io.spray" %% "spray-json" % sprayJsonVersion,

    // ── OpenTelemetry ──────────────────────────────────────────────────────
    libraryDependencies ++= Seq(
      "io.opentelemetry" % "opentelemetry-api"             % otelVersion,
      "io.opentelemetry" % "opentelemetry-sdk"             % otelVersion,
      "io.opentelemetry" % "opentelemetry-exporter-otlp"   % otelVersion,
      "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % otelVersion,
    ),

    // ── Logging ────────────────────────────────────────────────────────────
    libraryDependencies += "ch.qos.logback" % "logback-classic" % logbackVersion,

    // ── Test ───────────────────────────────────────────────────────────────
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion  % Test,
      "com.typesafe.akka" %% "akka-stream-testkit"      % akkaVersion  % Test,
      "org.scalatest"     %% "scalatest"                % scalatestVersion % Test,
    ),

    // ── Assembly settings ─────────────────────────────────────────────────
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF")            => MergeStrategy.discard
      case PathList("META-INF", "services", _*)           => MergeStrategy.concat
      case PathList("META-INF", "versions", _, _*)        => MergeStrategy.first
      case PathList("reference.conf")                     => MergeStrategy.concat
      case PathList("application.conf")                   => MergeStrategy.concat
      case PathList("module-info.class")                  => MergeStrategy.discard
      case x if x.endsWith(".proto")                      => MergeStrategy.first
      case _                                              => MergeStrategy.first
    },

    assembly / mainClass := Some("com.ecommerce.inventory.Main"),

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
    ),
  )
