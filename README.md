# IVDR Document Management System

> **Production-grade backend for IVDR (In Vitro Diagnostic Regulation) compliance document management.**



[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.7-black?logo=apachekafka)](https://kafka.apache.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 📋 Table of Contents

- [Why This Project?](#why-this-project)
- [Architecture Overview](#architecture-overview)
- [Tech Stack & Rationale](#tech-stack--rationale)
- [Project Structure](#project-structure)
- [Quick Start](#quick-start)
- [API Reference](#api-reference)
- [Key Design Decisions](#key-design-decisions)
- [CV Talking Points](#cv-talking-points)
- [Environment Variables](#environment-variables)
- [Deployment](#deployment)

---

## Why This Project?

IVDR (EU 2017/746) requires medical device diagnostic manufacturers to maintain **complete, immutable, tamper-evident audit trails** for all document interactions. This system provides:

- **Multi-tenant isolation** — each organization's data is completely separated at the database level (PostgreSQL Row-Level Security)
- **Immutable audit trail** — every action (view, download, login) is captured via Kafka and stored in an append-only log
- **Real-time collaboration** — WebSocket STOMP shows who is currently viewing a document
- **AI-assisted compliance** — automatic document summarization helps auditors quickly understand large document sets
- **Zero-downtime deploys** — Docker + Flyway migrations ensure safe schema evolution

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Client (React / Mobile)                      │
└───────────────┬─────────────────────────────────────────────────────┘
                │ HTTPS / WSS
┌───────────────▼─────────────────────────────────────────────────────┐
│              Spring Boot 3.x  (Java 21 Virtual Threads)              │
│                                                                       │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────────────────┐ │
│  │ REST API     │  │ WebSocket STOMP│  │ Kafka Consumer           │ │
│  │ (Auth, Doc,  │  │ (Live Presence)│  │ (Audit Pipeline)         │ │
│  │  Workspace,  │  │                │  │                          │ │
│  │  AI, Audit)  │  │                │  │  ┌───────────────────┐   │ │
│  └──────┬───────┘  └────────┬───────┘  │  │AnomalyDetection   │   │ │
│         │                   │          │  └───────────────────┘   │ │
│  ┌──────▼───────────────────▼──────┐   └──────────────────────────┘ │
│  │        Security Layer            │                                 │
│  │  JWT + Spring Security + RBAC   │                                 │
│  │  TenantContextService (RLS)     │                                 │
│  └─────────────────────────────────┘                                 │
└────────────┬────────────┬───────────────┬────────────────────────────┘
             │            │               │
   ┌──────────▼──┐  ┌──────▼────┐  ┌──────▼──────────┐  ┌────────────┐
   │ PostgreSQL  │  │  Redis    │  │  Apache Kafka   │  │  AWS S3 /  │
   │ (RLS, JSONB)│  │ (Cache,   │  │  (Audit events, │  │  MinIO     │
   │  Flyway     │  │  Rate     │  │   DLQ, Presence)│  │  (Files)   │
   │  migration) │  │  Limit,   │  │                 │  │            │
   │             │  │  Presence)│  │                 │  │            │
   └─────────────┘  └───────────┘  └─────────────────┘  └────────────┘
```

### Audit Event Pipeline

```
API Request
    │
    ▼
REST Handler
    │  fire-and-forget (non-blocking)
    ▼
AuditEventProducer ──HMAC sign──► Kafka Topic (3 partitions, key=orgId)
                                        │
                                        ▼
                               AuditEventConsumer
                                        │
                               ┌────────┴────────────┐
                               │                     │
                          idempotency check    verify signature
                               │                     │
                               ▼                     │
                         persist AuditLog ◄──────────┘
                               │
                          async analyze()
                               │
                    AnomalyDetectionService
                               │
                     ┌─────────┴──────────┐
                     │                    │
                 mark anomaly    WebSocket alert
                   in DB           to user
```

---

## Tech Stack & Rationale

| Layer | Technology | Why (CV Talking Points) |
|---|---|---|
| **Language** | Java 21 | Virtual Threads (Project Loom) — handle 10k+ concurrent connections with blocking code style. Records, Sealed Classes — modern APIs. |
| **Framework** | Spring Boot 3.3 | Industry standard; demonstrates framework depth, not just CRUD knowledge. |
| **Security** | Spring Security + JWT | Custom RBAC filter chain, per-resource permission checks, account lockout after N failed attempts. |
| **Database** | PostgreSQL 16 + Flyway | Row-Level Security for zero-trust multi-tenancy. JSONB for flexible audit metadata. Flyway for versioned, reproducible migrations. |
| **Cache** | Redis + Lettuce | Sliding-window rate limiting (Lua script atomicity). Presence TTL keys. Session cache. |
| **Streaming** | Apache Kafka | Decoupled audit write path — API latency not affected by audit DB writes. Dead Letter Queue for durability. Partitioning by orgId for ordering guarantees. |
| **WebSocket** | Spring WebSocket (STOMP) | Real-time presence awareness in collaborative document review. SockJS fallback for firewalled environments. |
| **Storage** | AWS S3 / MinIO | Presigned URLs for secure, time-limited direct client downloads. |
| **AI** | Claude API / OpenAI | Document summarization and anomaly explanation. Prompt engineering for compliance-specific outputs. |
| **DevOps** | Docker + GitHub Actions | Multi-stage build, GHCR push, Render deployment. OWASP dependency scan in CI. |

---

## Project Structure

```
ivdr/
├── .github/
│   └── workflows/
│       └── ci.yml                    # CI: test → OWASP scan → Docker push → Deploy
├── docker/
│   └── postgres/
│       └── init.sql                  # Extensions: uuid-ossp, pgcrypto, pg_trgm
├── src/
│   ├── main/
│   │   ├── java/com/ivdr/
│   │   │   ├── IvdrApplication.java  # Entry point, Virtual Threads config
│   │   │   ├── config/
│   │   │   │   ├── KafkaConfig.java  # Topics, producer (idempotent), consumer, DLQ
│   │   │   │   ├── WebSocketConfig.java
│   │   │   │   └── InfraConfig.java  # S3 client + Redis template beans
│   │   │   ├── security/
│   │   │   │   ├── JwtTokenProvider.java   # HS512 JWT, access + refresh tokens
│   │   │   │   ├── UserPrincipal.java      # Java 21 Record, implements UserDetails
│   │   │   │   ├── JwtAuthFilter.java      # Token validation + RLS context injection
│   │   │   │   └── SecurityConfig.java     # SecurityFilterChain, CORS, BCrypt
│   │   │   ├── common/
│   │   │   │   ├── exception/ApiException.java
│   │   │   │   ├── exception/GlobalExceptionHandler.java
│   │   │   │   ├── response/ApiResponse.java    # Generic response wrapper
│   │   │   │   └── util/CryptoUtil.java         # HMAC-SHA256, SHA256 checksum
│   │   │   └── domain/
│   │   │       ├── auth/            # Register, Login, Refresh, Logout
│   │   │       ├── workspace/       # Multi-tenant workspaces + RBAC members
│   │   │       ├── document/        # Upload, Download (presigned), Rate limit
│   │   │       ├── audit/           # Kafka producer+consumer, DLQ, anomaly detection
│   │   │       ├── presence/        # WebSocket STOMP, Redis TTL presence
│   │   │       ├── analytics/       # Heatmap, timeline, workspace stats
│   │   │       └── ai/              # Document summarization, anomaly explanation
│   │   └── resources/
│   │       ├── application.yml      # Full config: DB, Redis, Kafka, S3, AI, JWT
│   │       └── db/migration/
│   │           ├── V1__init_schema.sql    # Tables, indexes, triggers
│   │           └── V2__rls_policies.sql   # Row-Level Security policies
│   └── test/
│       └── java/com/ivdr/
│           ├── auth/AuthServiceTest.java
│           ├── audit/AuditEventConsumerTest.java
│           └── document/RateLimiterServiceTest.java
├── Dockerfile                        # Multi-stage: builder + runtime (ZGC)
├── docker-compose.yml                # Postgres, Redis, Kafka, MinIO, App
└── pom.xml                           # Java 21, Spring Boot 3.3, all deps
```

---

## Quick Start

### Prerequisites

- Docker Desktop (for local services)
- Java 21+ (for local development)
- Maven 3.9+

### 1. Clone and start infrastructure

```bash
git clone https://github.com/tothehung/ivdr.git
cd ivdr

# Start all infrastructure services (PostgreSQL, Redis, Kafka, MinIO)
docker-compose up postgres redis kafka minio -d

# Wait for services to be healthy (~30 seconds)
docker-compose ps
```

### 2. Run the application

```bash
# Option A: Run with Maven (local dev, hot reload)
export JWT_SECRET="your-secret-key-at-least-256-bits-long-here"
export AI_API_KEY="your-anthropic-or-openai-key"
mvn spring-boot:run

# Option B: Run everything with Docker Compose
cp .env.example .env   # Edit with your secrets
docker-compose up --build
```

### 3. Access the API

| Service | URL |
|---|---|
| **API Base** | `http://localhost:8080/api/v1` |
| **Swagger UI** | `http://localhost:8080/api/v1/swagger-ui.html` |
| **OpenAPI JSON** | `http://localhost:8080/api/v1/api-docs` |
| **Health Check** | `http://localhost:8080/api/v1/actuator/health` |
| **MinIO Console** | `http://localhost:9001` |

### 4. Seed test data

```bash
# Register a new organization + admin user
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "organizationName": "Acme Medical",
    "fullName": "John Doe",
    "email": "john@acme.com",
    "password": "SecurePass123!"
  }'
```

---

## API Reference

### Authentication

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/auth/register` | Register org + admin user | No |
| `POST` | `/auth/login` | Login, get JWT tokens | No |
| `POST` | `/auth/refresh` | Refresh access token | No |
| `POST` | `/auth/logout` | Revoke refresh token | Yes |

### Workspaces

| Method | Endpoint | Description | Min Role |
|---|---|---|---|
| `POST` | `/workspaces` | Create workspace | MEMBER |
| `GET` | `/workspaces` | List my workspaces | MEMBER |
| `GET` | `/workspaces/{id}` | Get workspace details | MEMBER |
| `DELETE` | `/workspaces/{id}` | Delete workspace | ADMIN |
| `POST` | `/workspaces/{id}/members` | Add member | OWNER |
| `DELETE` | `/workspaces/{id}/members/{userId}` | Remove member | OWNER |

### Documents

| Method | Endpoint | Description | Min Role |
|---|---|---|---|
| `POST` | `/workspaces/{id}/documents/upload` | Upload file (multipart) | EDITOR |
| `GET` | `/workspaces/{id}/documents` | List documents | VIEWER |
| `GET` | `/workspaces/{id}/documents/{docId}/download-url` | Get presigned URL | VIEWER |
| `DELETE` | `/workspaces/{id}/documents/{docId}` | Soft delete | EDITOR |

### Audit

| Method | Endpoint | Description | Min Role |
|---|---|---|---|
| `GET` | `/audit/logs` | All audit logs (pageable) | MANAGER |
| `GET` | `/audit/logs/user/{userId}` | Logs by user | MANAGER |
| `GET` | `/audit/logs/type/{eventType}` | Logs by event type | MANAGER |
| `GET` | `/audit/anomalies` | Anomalous events only | ADMIN |

### AI

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/ai/summarize/{documentId}` | AI summary of a document |
| `POST` | `/ai/explain-anomaly/{auditLogId}` | Explain why action is anomalous |
| `GET` | `/ai/recommendations?query=` | Document recommendations |

### Analytics

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/analytics/workspace/{id}/heatmap` | Document access heatmap |
| `GET` | `/analytics/users/{userId}/timeline` | User activity timeline |
| `GET` | `/analytics/workspace/{id}/stats` | Workspace summary stats |

### WebSocket (STOMP)

Connect to: `ws://localhost:8080/api/v1/ws` (with SockJS fallback)

| Destination | Direction | Description |
|---|---|---|
| `/app/workspace/{id}/join` | Client → Server | Mark user as present |
| `/app/workspace/{id}/leave` | Client → Server | Mark user as left |
| `/app/workspace/{id}/heartbeat` | Client → Server | Keep presence alive |
| `/topic/presence/{workspaceId}` | Server → Client | Presence updates (subscribe) |
| `/user/queue/alerts` | Server → Client | Personal anomaly alerts |

---

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `JWT_SECRET` | ✅ | — | Min 256-bit secret for JWT signing |
| `DB_HOST` | ✅ | `localhost` | PostgreSQL host |
| `DB_PASSWORD` | ✅ | `ivdr_secret` | DB password |
| `REDIS_HOST` | ✅ | `localhost` | Redis host |
| `REDIS_PASSWORD` | ✅ | `ivdr_redis_secret` | Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | ✅ | `localhost:9092` | Kafka brokers |
| `AWS_S3_BUCKET` | ✅ | `ivdr-documents` | S3/MinIO bucket name |
| `AWS_ACCESS_KEY_ID` | ✅ | — | S3/MinIO access key |
| `AWS_SECRET_ACCESS_KEY` | ✅ | — | S3/MinIO secret key |
| `AWS_S3_ENDPOINT` | 🔶 | — | Override for MinIO (leave empty for real AWS) |
| `AI_API_KEY` | 🔶 | — | Claude or OpenAI API key |
| `AI_PROVIDER` | 🔶 | `anthropic` | `anthropic` or `openai` |

---

## Deployment

### Deploy to Render (Free Tier)

1. Fork this repository
2. Create a Render account at [render.com](https://render.com)
3. Create a new **Web Service** pointing to your fork
4. Set environment variables in Render dashboard
5. Render will auto-deploy on every push to `main`

### Deploy to AWS (ECS)

```bash
# Build and push
docker build -t your-ecr-url/ivdr:latest .
docker push your-ecr-url/ivdr:latest

# Update ECS service
aws ecs update-service --cluster ivdr-cluster --service ivdr-app --force-new-deployment
```

### Local MinIO Setup

```bash
# Create bucket after MinIO starts
docker-compose up minio -d
sleep 5
docker run --rm --network ivdr_default \
  minio/mc alias set local http://minio:9000 ivdr_minio_user ivdr_minio_secret && \
  mc mb local/ivdr-documents
```

---

## Running Tests

```bash
# Unit tests only (no Docker required)
mvn test

# With coverage report
mvn verify
open target/site/jacoco/index.html

# Integration tests (requires Docker for Testcontainers)
mvn verify -P integration-tests

# Load testing using k6 (requires App running locally)
k6 run k6-load-test.js
```

---

## License

MIT License — see [LICENSE](LICENSE) file.

---
