# LLM Gateway — Development Tracker

## Progress Overview

| Milestone | Status | Target |
|---|---|---|
| M1: Project Scaffold & Infra | ✅ Complete | Week 1 |
| M2: Auth & API Key Management | ✅ Complete | Week 1-2 |
| M3: Provider Adapters & Routing | ✅ Complete | Week 2-3 |
| M4: Rate Limiting | ✅ Complete | Week 3 |
| M5: Semantic Caching | ✅ Complete | Week 4 |
| M6: Circuit Breaker & Failover | ✅ Complete | Week 5 |
| M7: Kafka Event Pipeline | ✅ Complete | Week 5-6 |
| M8: Analytics Consumer | ✅ Complete | Week 6 |
| M9: Dashboard — Backend API | 🔧 In Progress | Week 7 |
| M10: Dashboard — Frontend | ⬜ Not Started | Week 7-8 |
| M11: Integration & Load Testing | ⬜ Not Started | Week 8-9 |
| M12: Connect Real App | ⬜ Not Started | Week 9 |

---

## M1: Project Scaffold & Infra

Set up the multi-module project structure, Docker Compose, and verify all infrastructure starts cleanly.

- [ ] Initialize Gradle multi-module project (gateway-service, analytics-consumer)
- [ ] Create gateway-service Spring Boot app (Java 21, Spring Boot 3)
- [ ] Create analytics-consumer Spring Boot app
- [ ] Scaffold React + Vite dashboard project
- [ ] Write Docker Compose (Redis Stack, Kafka, Zookeeper, PostgreSQL, MongoDB)
- [ ] Verify all containers start and are reachable
- [ ] Add application.yml with profiles (dev, test)
- [ ] Health check endpoint: `GET /actuator/health`

**Done when:** `docker compose up -d` starts all infra, both Spring Boot apps start without errors, dashboard dev server runs.

---

## M2: Auth & API Key Management

Gateway-issued API keys with hashed storage. Admin CRUD endpoints.

- [ ] Create `api_keys` table (Flyway migration)
- [ ] Create `provider_config` table (Flyway migration)
- [ ] Seed default provider configs (OpenAI, Ollama)
- [ ] ApiKey JPA entity + repository
- [ ] ApiKeyService: create (hash + return plaintext once), validate, revoke
- [ ] ApiKeyFilter: servlet filter extracting `Authorization: Bearer gw-xxx`, validating against DB
- [ ] AdminController: CRUD endpoints for `/api/admin/keys`
- [ ] Admin auth: basic auth with `ADMIN_PASSWORD` env var
- [ ] Tests: key creation, validation, rejection of invalid/disabled keys

**Done when:** Can create a key via admin API, use it in Authorization header, get 401 with bad key, get 403 with disabled key.

---

## M3: Provider Adapters & Routing

Adapter pattern for LLM providers. OpenAI wire-format translation.

- [ ] Define `LlmProvider` interface: `complete(ChatRequest)`, `stream(ChatRequest)`
- [ ] ChatRequest / ChatResponse models (OpenAI wire format)
- [ ] OpenAiProvider: translates to OpenAI API, handles streaming SSE
- [ ] OllamaProvider: translates to Ollama API format, handles streaming
- [ ] ProviderRouter: resolves model → provider using provider_config
- [ ] SseResponseHandler: streams SSE chunks to client, buffers full response
- [ ] ChatCompletionController: `POST /v1/chat/completions`
- [ ] Tests: non-streaming request, streaming request, unknown model → 400

**Done when:** Can send a chat completion request through the gateway to both OpenAI and Ollama, get responses back in OpenAI format. Streaming works end-to-end.

---

## M4: Rate Limiting

Dual token-bucket rate limiter with Redis.

- [ ] RateLimiter interface
- [ ] RedisTokenBucketRateLimiter: request count bucket (INCR + TTL)
- [ ] RedisTokenBucketRateLimiter: token budget bucket (INCRBY + TTL)
- [ ] Lua script for atomic bucket check
- [ ] Integrate into request filter chain (after auth, before cache)
- [ ] Rate limit response headers (X-RateLimit-*)
- [ ] 429 response with Retry-After header
- [ ] Read per-key limits from Postgres (cached in Redis, 60s TTL)
- [ ] Tests: request allowed under limit, rejected over limit, headers correct

**Done when:** Rapid-fire requests get 429 after exceeding configured RPM. Rate limit headers present on every response. Different keys have independent limits.

---

## M5: Semantic Caching

Vector-based similarity cache using Ollama embeddings and RediSearch.

- [ ] EmbeddingService: calls Ollama `/api/embeddings` with nomic-embed-text
- [ ] Create RediSearch vector index (`idx:semantic_cache`)
- [ ] SemanticCache interface
- [ ] RedisSemanticCache: store (embedding + response + model tag + TTL)
- [ ] RedisSemanticCache: lookup (embed prompt → KNN search → cosine > 0.95 → filter by model)
- [ ] Integrate into request flow (after rate limit, before provider call)
- [ ] Cache hit → return response immediately, log as cache hit
- [ ] Cache miss → call provider, store response after streaming completes
- [ ] Exact-match fast path: SHA-256 prompt hash check before vector search
- [ ] Tests: identical prompt → cache hit, similar prompt → cache hit, different model → cache miss

**Done when:** Send same question twice — second response is instant from cache. Dashboard shows cache hit. Slightly reworded question also hits cache.

---

## M6: Circuit Breaker & Failover

Resilience4j circuit breaker with automatic provider failover.

- [ ] Add Resilience4j dependency
- [ ] Configure circuit breaker per provider (failure rate, slow call threshold, wait duration)
- [ ] Wrap provider calls in circuit breaker in ProviderRouter
- [ ] Failover logic: breaker OPEN → use fallback provider with model mapping
- [ ] Failure classification: 5xx/timeout → failure, 4xx → pass-through
- [ ] Failover response headers (X-LLM-Gateway-Fallback)
- [ ] 503 when all providers down
- [ ] Expose circuit breaker state via admin API
- [ ] Tests: simulate provider failure → automatic failover, recovery after wait period

**Done when:** Kill OpenAI (or return 500s), requests automatically route to Ollama. After 30s, breaker goes half-open and probes OpenAI. Dashboard shows breaker state transitions.

---

## M7: Kafka Event Pipeline

Publish usage events to Kafka after every request.

- [ ] Add Spring Kafka dependency
- [ ] KafkaProducerConfig
- [ ] UsageEvent DTO
- [ ] KafkaUsagePublisher: publishes to `llm-usage-events` topic
- [ ] Integrate into request flow (publish after response completes)
- [ ] Include: apiKeyId, provider, model, tokens, cost, latency, cacheHit, fallbackUsed, status
- [ ] Cost estimation from static pricing table (application.yml)
- [ ] Tests: verify event published after successful and failed requests

**Done when:** Every gateway request produces a Kafka message. Can verify with `kafka-console-consumer`.

---

## M8: Analytics Consumer

Separate service consuming Kafka events into MongoDB.

- [ ] KafkaUsageConsumer: `@KafkaListener` on `llm-usage-events`
- [ ] UsageEventDocument: MongoDB document mapping
- [ ] UsageRepository: Spring Data MongoDB
- [ ] MongoDB indexes (timestamp TTL 90d, api_key_id + timestamp, provider + timestamp)
- [ ] Error handling: dead letter topic for malformed events
- [ ] Tests: publish event to Kafka → verify document in MongoDB

**Done when:** Usage events flow from gateway → Kafka → consumer → MongoDB. Documents queryable with timestamps, per-key, per-provider.

---

## M9: Dashboard — Backend API

Admin API endpoints for dashboard data.

- [ ] `GET /api/admin/stats` — overview aggregations from MongoDB
- [ ] `GET /api/admin/usage` — usage events with filters (days, keyId, provider)
- [ ] `GET /api/admin/providers` — provider health + circuit breaker state
- [ ] `PUT /api/admin/providers/{id}/toggle` — enable/disable
- [ ] `PUT /api/admin/providers/{id}/models` — update model mappings
- [ ] `GET /api/admin/keys/{id}` — key details + usage stats
- [ ] `PUT /api/admin/keys/{id}` — update rate limits, enabled status
- [ ] `DELETE /api/admin/keys/{id}` — revoke key
- [ ] `POST /api/admin/cache/flush` — clear semantic cache
- [ ] CORS configuration for dashboard origin
- [ ] Tests: each endpoint returns expected data shape

**Done when:** All admin endpoints work and return correct data. Dashboard can fetch everything it needs from these endpoints.

---

## M10: Dashboard — Frontend

React SPA with monitoring and admin controls.

- [ ] Project setup: React + Vite + React Router + Recharts
- [ ] API client module (axios/fetch wrapper with basic auth)
- [ ] Layout: sidebar navigation, responsive
- [ ] Overview page: request volume line chart, cost bar chart, cache hit rate, error rate
- [ ] Cache page: hit rate trend, cost saved, cache size, recent hits/misses table, flush button
- [ ] Providers page: provider cards with health/circuit state/latency, toggle, model mapping editor
- [ ] API Keys page: key table, create dialog, revoke, rate limit config, per-key usage
- [ ] Auto-refresh: poll every 30 seconds
- [ ] Basic auth login screen
- [ ] Error/loading states for all pages

**Done when:** Dashboard shows live data from the gateway. Can create/revoke keys, toggle providers, view usage charts, flush cache — all from the UI.

---

## M11: Integration & Load Testing

End-to-end tests and performance benchmarks.

- [ ] Integration test: full request flow (auth → rate limit → cache → provider → event → MongoDB)
- [ ] Failover test: disable OpenAI in config → verify Ollama serves requests → re-enable → verify recovery
- [ ] Cache effectiveness test: send 100 similar prompts, measure hit rate
- [ ] Rate limit test: exceed limit, verify 429s and recovery
- [ ] Load test: JMeter or k6 script — measure p50/p95/p99 latency overhead
- [ ] Document results: cache hit rate, cost saved, latency overhead, failover timing

**Done when:** All integration tests pass. Load test results documented with resume-ready metrics.

---

## M12: Connect Real App

Route the AI Personal Knowledge Assistant through the gateway.

- [ ] Create a dedicated API key for the knowledge assistant
- [ ] Update knowledge assistant to point at gateway URL
- [ ] Verify all features work with real traffic (chat, streaming, long prompts)
- [ ] Monitor dashboard with real usage patterns
- [ ] Collect real metrics: cache hit rate, cost savings, latency overhead
- [ ] Screenshot dashboard with real data for portfolio

**Done when:** Knowledge assistant works seamlessly through the gateway. Dashboard shows real usage data. Resume metrics collected.

---

## Resume Metrics Targets

| Metric | Target | How to Measure |
|---|---|---|
| Cache hit rate | > 30% on repeated queries | Dashboard cache analytics |
| Cost saved | Track $ saved from cache hits | (cache hits) x (avg cost per call) |
| p95 latency overhead | < 50ms added by gateway | Load test: direct vs through gateway |
| Failover success | 0 failed requests during provider outage | Kill provider, run traffic, check errors |
| Throughput | > 50 req/s sustained | k6 / JMeter load test |
