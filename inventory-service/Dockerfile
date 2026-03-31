# ──────────────────────────────────────────────────────────────────────────────
# Multi-stage Docker build — Inventory Service
#
# Stage 1 (builder): Compiles the Scala project and produces a fat JAR via
#                    sbt-assembly. Dependencies are cached in a separate layer.
# Stage 2 (runtime): Minimal JRE-only image that runs the pre-built JAR.
# ──────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Builder ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

ARG SBT_VERSION=1.10.1

# Install curl (needed to fetch sbt) and clean up apt cache in the same layer.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Install sbt.
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    | tar -xz -C /usr/local \
    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /build

# ── Dependency caching layer ──────────────────────────────────────────────────
# Copy only the build definition first so that dependency resolution is cached
# as long as build.sbt and project/ files don't change.
COPY project/build.properties project/build.properties
COPY project/plugins.sbt      project/plugins.sbt
COPY build.sbt                build.sbt

# Warm up the dependency cache without compiling source code.
RUN sbt update --batch

# ── Compile & assemble ────────────────────────────────────────────────────────
COPY src src
RUN sbt assembly --batch \
    && mv target/scala-3.3.3/inventory-service-assembly-*.jar /build/app.jar

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

# Non-root user for security.
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

WORKDIR /app

# Copy the fat JAR from the builder stage.
COPY --from=builder /build/app.jar app.jar

# Optional: allow mounting an external application.conf at runtime.
VOLUME ["/app/config"]

# JVM tuning defaults — override via JAVA_OPTS environment variable.
ENV JAVA_OPTS="\
  -server \
  -XX:+UseG1GC \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/heapdump.hprof \
  -Dfile.encoding=UTF-8 \
  -Dconfig.file=/app/config/application.conf"

# OpenTelemetry Java agent — attach at startup if OTEL_AGENT_PATH is set.
# Mount the agent JAR to /app/otel-agent.jar and set:
#   JAVA_TOOL_OPTIONS=-javaagent:/app/otel-agent.jar
ENV OTEL_SERVICE_NAME=inventory-service
ENV OTEL_TRACES_EXPORTER=otlp
ENV OTEL_METRICS_EXPORTER=otlp
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317

EXPOSE 8080 2552

USER appuser

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
