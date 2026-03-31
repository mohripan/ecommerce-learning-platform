# ── Stage 1: restore & build ────────────────────────────────────────────────
FROM mcr.microsoft.com/dotnet/sdk:8.0-alpine AS build
WORKDIR /src

# Copy the project file first so Docker layer-caching skips restore when only
# source files change.
COPY api-gateway.csproj ./
RUN dotnet restore api-gateway.csproj \
        --runtime linux-musl-x64 \
        --locked-mode 2>/dev/null || dotnet restore api-gateway.csproj --runtime linux-musl-x64

# Copy remaining sources and publish a self-contained, trimmed binary.
COPY . ./
RUN dotnet publish api-gateway.csproj \
        -c Release \
        -r linux-musl-x64 \
        --self-contained true \
        -p:PublishSingleFile=false \
        -p:PublishTrimmed=false \
        -o /app/publish

# ── Stage 2: runtime ────────────────────────────────────────────────────────
FROM mcr.microsoft.com/dotnet/aspnet:8.0-alpine AS runtime

# Install curl for the Docker HEALTHCHECK command.
RUN apk add --no-cache curl

# Run as a non-root user.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app
COPY --from=build /app/publish ./

# ASP.NET Core configuration.
ENV ASPNETCORE_URLS=http://+:8080
ENV ASPNETCORE_ENVIRONMENT=Production
ENV DOTNET_RUNNING_IN_CONTAINER=true
# Tune the thread-pool for I/O-heavy gateway workloads.
ENV DOTNET_SYSTEM_NET_HTTP_SOCKETSHTTPHANDLER_HTTP2SUPPORT=true

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8080/health/live || exit 1

ENTRYPOINT ["dotnet", "ApiGateway.dll"]
