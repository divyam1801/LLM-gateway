export interface Stats {
  totalRequests: number
  cacheHits: number
  cacheHitRate: number
  totalCost: number
  cacheSavings: number
  errorRate: number
  avgLatencyMs: number
  costByProvider: Record<string, number>
  activeKeys: number
  cacheSize: number
}

export interface UsageEvent {
  id: string
  apiKeyId: string
  provider: string
  model: string
  requestType: string | null
  inputTokens: number
  outputTokens: number
  estimatedCostUsd: number
  latencyMs: number
  cacheHit: boolean
  fallbackUsed: boolean
  status: string
  errorMessage: string | null
  timestamp: string
}

export interface ProviderStatus {
  provider: string
  circuitBreakerState: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  totalCalls: number
  errorRate: number
  p50LatencyMs: number
  p95LatencyMs: number
  p99LatencyMs: number
}

export interface ApiKeyInfo {
  id: string
  name: string
  owner: string
  rateLimitRpm: number
  rateLimitTpm: number
  enabled: boolean
  createdAt: string
  lastUsedAt: string | null
}

export interface CreateKeyResponse {
  id: string
  key: string
  name: string
  owner: string
  message: string
}
