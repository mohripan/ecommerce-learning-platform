# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Cache the Maven wrapper and dependencies layer separately from the source.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies without building the project (layer-cached).
RUN ./mvnw dependency:go-offline -B -q

# Copy source and build the fat JAR.
COPY src/ src/
RUN ./mvnw package -B -DskipTests -q && \
    java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

# Security hardening: run as a non-root user.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy layers from the builder stage in order of increasing change frequency.
COPY --from=builder /build/target/extracted/dependencies/          ./
COPY --from=builder /build/target/extracted/spring-boot-loader/    ./
COPY --from=builder /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/target/extracted/application/           ./

# ── Container metadata ───────────────────────────────────────────────────────
LABEL org.opencontainers.image.title="order-service" \
      org.opencontainers.image.description="Order Service — CQRS + Event Sourcing" \
      org.opencontainers.image.vendor="ecommerce-learning-platform"

# ── Ports ────────────────────────────────────────────────────────────────────
EXPOSE 8080

# ── Health check ─────────────────────────────────────────────────────────────
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# ── JVM tuning ───────────────────────────────────────────────────────────────
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
