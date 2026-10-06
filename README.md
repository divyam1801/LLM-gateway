# LLM Gateway

A proxy service that sits between your applications and LLM providers (OpenAI, Ollama), exposing a **unified OpenAI-compatible API** with rate limiting, semantic caching, automatic failover, and usage observability.

Instead of calling LLM providers directly, your apps call the gateway. It handles the operational concerns they shouldn't own: per-user rate limits, cost-aware caching, circuit-breaker failover between providers, and a real-time analytics dashboard.

## Architecture

```
                         ┌──────────────────┐
    Client App ─────────►│  Gateway Service  │──────► OpenAI API
                         │  (Spring Boot)    │──────► Ollama (local)
                         │                   │
                         │  Auth Filter      │
                         │  Rate Limiter ◄──►│──► Redis (token buckets)
                         │  Semantic Cache◄─►│──► Redis (RediSearch vectors)
                         │  Circuit Breaker  │      + Ollama (embeddings)
                         │  Admin API ◄─────►│──► PostgreSQL (keys/config)
                         │  Kafka Producer   │
                         └────────┬──────────┘
                                  │ Kafka
                         ┌────────▼──────────┐
                         │ Analytics Consumer │──► MongoDB (usage events)
                         └───────────────────┘

                         ┌───────────────────┐
                         │   React Dashboard  │──► Gateway Admin API
                         └───────────────────┘
```

## Core Features

### Unified API
- OpenAI-compatible `/v1/chat/completions` endpoint
- Supports both streaming (SSE) and non-streaming responses
- Swap providers by changing the `model` field — no client code changes

### Rate Limiting
- Dual token-bucket algorithm (requests/min + tokens/min) per API key
- Atomic Redis operations via Lua scripts
- OpenAI-compatible rate limit response headers
- Configurable limits per API key via admin dashboard

### Semantic Caching
- Embeds prompts locally via Ollama (`nomic-embed-text`)
- Searches Redis (RediSearch) for semantically similar past prompts (cosine > 0.95)
- Cache-scoped by model — a GPT-4o response won't be served for a Llama request
- Configurable TTL (default 1 hour)

### Circuit Breaker & Failover
- Resilience4j circuit breaker per provider
- Automatic failover with configurable model mapping (e.g., `gpt-4o` → `llama3`)
- Failover headers in response (`X-LLM-Gateway-Fallback: true`)
- States visible on dashboard: CLOSED / OPEN / HALF_OPEN

### Usage Analytics
- Every request produces a Kafka event with tokens, cost, latency, cache hit status
- Dedicated consumer writes events to MongoDB
- 90-day TTL with auto-expiry
- Cost estimation via configurable pricing table

### Admin Dashboard (React)
- **Overview:** request volume, cost breakdown, cache hit rate, error rate
- **Cache:** hit rate trends, cost saved, flush controls
- **Providers:** health status, circuit breaker state, p50/p95/p99 latency, enable/disable toggle
- **API Keys:** create, revoke, configure rate limits, per-key usage stats

## Tech Stack

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3 |
| Rate Limiting & Cache | Redis 7 + RediSearch |
| Embeddings | Ollama (nomic-embed-text) |
| Circuit Breaker | Resilience4j |
| Event Streaming | Apache Kafka |
| User/Key Storage | PostgreSQL 16 |
| Usage Storage | MongoDB 7 |
| Dashboard | React + Vite + Recharts |
| Infrastructure | Docker Compose |

## Project Structure

```
llm-gateway/
├── gateway-service/        # Main Spring Boot application
├── analytics-consumer/     # Kafka consumer → MongoDB
├── dashboard/              # React SPA (Vite)
├── docker/                 # Docker Compose for infrastructure
└── docs/                   # Design specs and documentation
```

## Prerequisites

- Java 21 (JDK)
- Node.js 18+ and npm
- Docker and Docker Compose
- Ollama (running locally with `nomic-embed-text` and a chat model like `llama3`)

## Getting Started

### 1. Start infrastructure

```bash
docker compose -f docker/docker-compose.yml up -d
```

This starts Redis (with RediSearch), Kafka, Zookeeper, PostgreSQL, and MongoDB.

### 2. Start the gateway service

```bash
cd gateway-service
./gradlew bootRun
```

The gateway runs on `http://localhost:8080`.

### 3. Start the analytics consumer

```bash
cd analytics-consumer
./gradlew bootRun
```

### 4. Start the dashboard

```bash
cd dashboard
npm install
npm run dev
```

The dashboard runs on `http://localhost:5173`.

### 5. Create an API key

```bash
curl -X POST http://localhost:8080/api/admin/keys \
  -u admin:${ADMIN_PASSWORD} \
  -H "Content-Type: application/json" \
  -d '{"name": "my-app", "owner": "dev"}'
```

Save the returned API key — it's shown only once.

### 6. Make a request

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer gw-your-key-here" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama3",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'
```

## Usage with Existing Apps

Point your application at the gateway instead of the LLM provider directly:

```python
# Before: calling Ollama directly
client = OpenAI(base_url="http://localhost:11434/v1", api_key="ollama")

# After: calling through the gateway
client = OpenAI(base_url="http://localhost:8080/v1", api_key="gw-your-key-here")
```

The gateway handles provider routing, caching, rate limiting, and failover transparently.

## Configuration

Key environment variables for the gateway service:

| Variable | Description | Default |
|---|---|---|
| `ADMIN_PASSWORD` | Dashboard basic auth password | `admin` |
| `REDIS_HOST` | Redis connection host | `localhost` |
| `REDIS_PORT` | Redis connection port | `6379` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address | `localhost:9092` |
| `POSTGRES_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/llmgateway` |
| `OPENAI_API_KEY` | OpenAI API key for the OpenAI provider | — |
| `OLLAMA_BASE_URL` | Ollama server URL | `http://localhost:11434` |
| `CACHE_TTL_SECONDS` | Semantic cache TTL | `3600` |
| `CACHE_SIMILARITY_THRESHOLD` | Cosine similarity threshold for cache hits | `0.95` |

## License

MIT
