# Distributed Order & Fulfillment Platform

A polyglot microservices backend modeled after large-scale e-commerce systems (Tokopedia, Shopee). Built as a learning platform for distributed systems architecture, covering CQRS, Event Sourcing, Saga pattern, streaming analytics, and full DevOps practices.

## Architecture Overview

```
                         ┌──────────────┐
                         │  API Gateway  │ (C# ASP.NET Core)
                         │  Rate Limit   │ Port 5000
                         │  JWT Auth     │
                         └──────┬───────┘
                                │
                    ┌───────────┼───────────┐
                    │           │           │
             ┌──────▼──────┐  ┌▼─────────┐ │
             │Order Service│  │Inventory  │ │
             │ Java/Spring │  │  Service  │ │
             │ Axon CQRS   │  │Scala/Akka │ │
             │ Port 8081   │  │Port 8082  │ │
             └──────┬──────┘  └─────┬─────┘ │
                    │               │        │
              ┌─────▼───────────────▼────────▼─┐
              │           Apache Kafka          │
              │     (Event Backbone - 9092)     │
              └─────┬───────────────┬───────────┘
                    │               │
          ┌─────────▼──────┐ ┌─────▼──────────┐
          │ Notification   │ │   Analytics    │
          │   Service      │ │    Service     │
          │  C# Worker     │ │ Scala/Akka     │
          │                │ │ Streams        │
          │                │ │ Port 8084      │
          └────────────────┘ └────────────────┘
```

## Services

| Service | Language | Framework | Port | Purpose |
|---------|----------|-----------|------|---------|
| **API Gateway** | C# | ASP.NET Core 8 | 5000 | Rate limiting, JWT auth, request routing, response aggregation |
| **Order Service** | Java 17 | Spring Boot 3 + Axon | 8081 | CQRS + Event Sourcing, Order lifecycle, Saga pattern |
| **Inventory Service** | Scala 3 | Akka Typed + HTTP | 8082 | Concurrent stock reservation via actor model, cluster sharding |
| **Notification Service** | C# | .NET 8 Worker | — | Kafka consumer, email (MailKit) & webhook dispatch |
| **Analytics Service** | Scala 3 | Akka Streams | 8084 | Streaming aggregation pipeline, windowed metrics |

## Infrastructure

| Component | Technology | Purpose |
|-----------|-----------|---------|
| **Kafka** | Confluent 7.6 | Event backbone (order-events, inventory-events, analytics-events) |
| **PostgreSQL** | 16 Alpine | Database-per-service (orderdb, inventorydb, analyticsdb) |
| **Redis** | 7 Alpine | Caching hot reads (product catalog, sessions) |
| **Keycloak** | 24.0 | OAuth2/OIDC identity provider |

## Observability Stack

| Tool | Port | Purpose |
|------|------|---------|
| **Jaeger** | 16686 | Distributed tracing (OpenTelemetry) |
| **Prometheus** | 9090 | Metrics collection |
| **Grafana** | 3000 | Dashboards & visualization |
| **Loki** | 3100 | Log aggregation |

## Quick Start

### Prerequisites
- Docker & Docker Compose v2
- (Optional) Java 17, .NET 8 SDK, SBT for local development

### Run Everything with Docker Compose

```bash
# Start the full stack (infrastructure + all services)
docker compose up -d

# Or start only infrastructure (for running services from IDE)
docker compose -f docker-compose.infra.yml up -d
```

### Service URLs (after startup)

| Service | URL |
|---------|-----|
| API Gateway | http://localhost:5000 |
| Order Service | http://localhost:8081 |
| Inventory Service | http://localhost:8082 |
| Analytics Service | http://localhost:8084 |
| Keycloak Admin | http://localhost:8180 |
| Jaeger UI | http://localhost:16686 |
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |

## Key Patterns Implemented

### CQRS + Event Sourcing (Order Service)
Every order state change is stored as an immutable event. The write model (aggregate) handles commands and emits events. The read model (projection) is built by replaying events. Uses Axon Framework with PostgreSQL-backed event store.

### Saga Pattern (Order Service)
Distributed transaction orchestration for the order flow:
```
OrderCreated → ReserveInventory → InventoryReserved → ProcessPayment → ConfirmOrder ✓
                                → InventoryFailed → CancelOrder ✗
                                                  → PaymentFailed → ReleaseInventory + CancelOrder ✗
```

### Actor Model (Inventory Service)
Each product ID maps to exactly one Akka actor across the cluster. The actor's single-threaded mailbox serializes all reservations — no locks, no race conditions. Cluster sharding distributes actors across nodes.

### Reactive Streams (Analytics Service)
Akka Streams pipeline consumes Kafka events, applies tumbling window aggregation (hourly metrics), and persists to PostgreSQL with full backpressure propagation.

## Kubernetes Deployment

```bash
# Deploy with Helm
helm install ecommerce kubernetes/helm/ecommerce-platform \
  --namespace ecommerce \
  --create-namespace

# Check status
kubectl get pods -n ecommerce
```

The Helm chart includes:
- Per-service Deployments with liveness/readiness probes
- HorizontalPodAutoscalers (1-5 replicas, 70% CPU target)
- Shared ConfigMap for Kafka, OTEL, Redis configuration
- Resource requests/limits for all containers

## Cloud Infrastructure (Terraform)

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your values

terraform init
terraform plan
terraform apply
```

Provisions on AWS:
- **EKS** cluster with managed node groups
- **RDS** PostgreSQL instances (per service)
- **ElastiCache** Redis cluster
- **MSK** Kafka cluster
- VPC with public/private subnets, NAT Gateway

## CI/CD

Each service has its own GitHub Actions workflow:
- **Trigger**: Push/PR to `main` with path filter
- **Pipeline**: Build → Test → Docker Build → Push to GHCR
- **Deploy**: Manual workflow dispatch with environment selection (staging/production)

## Project Structure

```
├── api-gateway/                 # C# ASP.NET Core 8
│   ├── Middleware/               # Correlation ID middleware
│   ├── Services/                 # Order & Inventory HTTP proxies
│   ├── Program.cs                # Rate limiting, auth, routing
│   └── Dockerfile
├── order-service/               # Java 17 Spring Boot + Axon
│   └── src/main/java/.../
│       ├── aggregate/            # OrderAggregate (event sourcing)
│       ├── command/              # CQRS commands
│       ├── event/                # Domain events
│       ├── saga/                 # OrderSaga + saga commands/events
│       ├── query/                # Read model + projections
│       ├── api/                  # REST controllers
│       └── config/               # Axon configuration
├── inventory-service/           # Scala 3 + Akka Typed
│   └── src/main/scala/.../
│       ├── actor/                # InventoryActor + InventoryManager
│       ├── api/                  # Akka HTTP routes
│       ├── kafka/                # Consumer & Producer
│       └── model/                # Domain models
├── notification-service/        # C# .NET 8 Worker Service
│   ├── Workers/                  # Kafka consumer worker
│   ├── Handlers/                 # Event handlers
│   ├── Services/                 # Email & Webhook dispatchers
│   └── Models/                   # DTOs & settings
├── analytics-service/           # Scala 3 + Akka Streams
│   └── src/main/scala/.../
│       ├── stream/               # Event stream + aggregation pipeline
│       ├── api/                  # REST API for analytics queries
│       ├── repository/           # Slick/PostgreSQL metrics store
│       └── model/                # Analytics models
├── kubernetes/helm/             # Helm chart for K8s deployment
├── terraform/                   # AWS infrastructure (EKS, RDS, MSK, Redis)
├── observability/               # Prometheus, Grafana, Loki configs
├── .github/workflows/           # CI/CD pipelines per service
├── docker-compose.yml           # Full stack (local dev)
└── docker-compose.infra.yml     # Infrastructure only (IDE dev)
```

## License

MIT License — see [LICENSE](LICENSE)