FROM sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.10_7_1.9.8_3.3.1 AS build
WORKDIR /app

COPY project/ project/
COPY build.sbt .
RUN sbt update

COPY src/ src/
RUN sbt stage

FROM eclipse-temurin:17-jre-jammy AS final
WORKDIR /app

RUN addgroup --system --gid 1001 appgroup && \
    adduser --system --uid 1001 --ingroup appgroup appuser

COPY --from=build /app/target/universal/stage/ .

RUN chown -R appuser:appgroup /app
USER appuser

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["bin/analytics-service"]
