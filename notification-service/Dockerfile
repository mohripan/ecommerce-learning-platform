FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

COPY notification-service.csproj .
RUN dotnet restore

COPY . .
RUN dotnet build -c Release --no-restore
RUN dotnet publish -c Release --no-restore -o /app/publish

FROM mcr.microsoft.com/dotnet/runtime:8.0 AS runtime
WORKDIR /app

RUN addgroup --system --gid 1001 appgroup && \
    adduser --system --uid 1001 --ingroup appgroup appuser

COPY --from=build /app/publish .

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["dotnet", "notification-service.dll", "--health-check"] || exit 1

ENTRYPOINT ["dotnet", "notification-service.dll"]
