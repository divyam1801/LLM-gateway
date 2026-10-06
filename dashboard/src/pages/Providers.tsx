import { useEffect, useState } from 'react'
import api from '../api/client'
import type { ProviderStatus } from '../types'

const stateColors: Record<string, { bg: string; text: string }> = {
  CLOSED: { bg: '#dcfce7', text: '#166534' },
  OPEN: { bg: '#fef2f2', text: '#991b1b' },
  HALF_OPEN: { bg: '#fef3c7', text: '#92400e' },
}

function Providers() {
  const [providers, setProviders] = useState<ProviderStatus[]>([])

  const fetchData = () => {
    api.get('/providers').then(r => setProviders(r.data)).catch(console.error)
  }

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 30000)
    return () => clearInterval(interval)
  }, [])

  return (
    <div>
      <h1>Providers</h1>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 20 }}>
        {providers.map(p => {
          const colors = stateColors[p.circuitBreakerState] || stateColors.CLOSED

          return (
            <div key={p.provider} style={{
              background: '#fff', borderRadius: 8, padding: 24,
              boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                <h2 style={{ margin: 0, textTransform: 'capitalize' }}>{p.provider}</h2>
                <span style={{
                  padding: '4px 12px', borderRadius: 12, fontSize: 12, fontWeight: 600,
                  background: colors.bg, color: colors.text
                }}>
                  {p.circuitBreakerState.replace('_', ' ')}
                </span>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, fontSize: 14 }}>
                <div>
                  <div style={{ color: '#6b7280', fontSize: 12 }}>Total Calls (1h)</div>
                  <div style={{ fontWeight: 600 }}>{p.totalCalls}</div>
                </div>
                <div>
                  <div style={{ color: '#6b7280', fontSize: 12 }}>Error Rate</div>
                  <div style={{ fontWeight: 600, color: p.errorRate > 5 ? '#ef4444' : '#22c55e' }}>
                    {p.errorRate}%
                  </div>
                </div>
                <div>
                  <div style={{ color: '#6b7280', fontSize: 12 }}>p50 Latency</div>
                  <div style={{ fontWeight: 600 }}>{p.p50LatencyMs}ms</div>
                </div>
                <div>
                  <div style={{ color: '#6b7280', fontSize: 12 }}>p95 Latency</div>
                  <div style={{ fontWeight: 600 }}>{p.p95LatencyMs}ms</div>
                </div>
              </div>

              <div style={{ marginTop: 12, fontSize: 13, color: '#6b7280' }}>
                p99: {p.p99LatencyMs}ms
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default Providers
