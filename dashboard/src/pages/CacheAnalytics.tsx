import { useEffect, useState } from 'react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import api from '../api/client'
import type { Stats, UsageEvent } from '../types'

function CacheAnalytics() {
  const [stats, setStats] = useState<Stats | null>(null)
  const [events, setEvents] = useState<UsageEvent[]>([])

  const fetchData = () => {
    api.get('/stats?days=7').then(r => setStats(r.data)).catch(console.error)
    api.get('/usage?days=7').then(r => setEvents(r.data)).catch(console.error)
  }

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 30000)
    return () => clearInterval(interval)
  }, [])

  const cacheableEvents = events.filter(e => e.requestType !== 'embedding')

  const hitRateByDay = cacheableEvents.reduce<Record<string, { hits: number; total: number }>>((acc, e) => {
    const day = e.timestamp.split('T')[0]
    if (!acc[day]) acc[day] = { hits: 0, total: 0 }
    acc[day].total++
    if (e.cacheHit) acc[day].hits++
    return acc
  }, {})

  const hitRateData = Object.entries(hitRateByDay)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, { hits, total }]) => ({
      date,
      hitRate: total > 0 ? Math.round(hits / total * 100) : 0
    }))

  const recentEvents = cacheableEvents.slice(0, 20)

  const handleFlush = () => {
    if (confirm('Flush the entire semantic cache?')) {
      api.post('/cache/flush').then(fetchData).catch(console.error)
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <h1 style={{ margin: 0 }}>Cache Analytics</h1>
        <button onClick={handleFlush} style={{
          padding: '8px 16px', background: '#ef4444', color: '#fff',
          border: 'none', borderRadius: 6, cursor: 'pointer'
        }}>Flush Cache</button>
      </div>

      {stats && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16, marginBottom: 32 }}>
          <StatCard label="Cache Hit Rate" value={`${stats.cacheHitRate}%`} />
          <StatCard label="Estimated Savings" value={`$${stats.cacheSavings.toFixed(4)}`} />
          <StatCard label="Cache Entries" value={stats.cacheSize.toLocaleString()} />
        </div>
      )}

      <div style={{ background: '#fff', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.1)', marginBottom: 24 }}>
        <h3 style={{ marginTop: 0 }}>Hit Rate Over Time</h3>
        <ResponsiveContainer width="100%" height={250}>
          <LineChart data={hitRateData}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
            <YAxis unit="%" domain={[0, 100]} />
            <Tooltip />
            <Line type="monotone" dataKey="hitRate" stroke="#22c55e" strokeWidth={2} />
          </LineChart>
        </ResponsiveContainer>
      </div>

      <div style={{ background: '#fff', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
        <h3 style={{ marginTop: 0 }}>Recent Requests</h3>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #e5e7eb', textAlign: 'left' }}>
              <th style={{ padding: 8 }}>Time</th>
              <th style={{ padding: 8 }}>Model</th>
              <th style={{ padding: 8 }}>Cache</th>
              <th style={{ padding: 8 }}>Latency</th>
              <th style={{ padding: 8 }}>Cost</th>
            </tr>
          </thead>
          <tbody>
            {recentEvents.map(e => (
              <tr key={e.id} style={{ borderBottom: '1px solid #f3f4f6' }}>
                <td style={{ padding: 8 }}>{new Date(e.timestamp).toLocaleTimeString()}</td>
                <td style={{ padding: 8 }}>{e.model}</td>
                <td style={{ padding: 8 }}>
                  <span style={{
                    padding: '2px 8px', borderRadius: 12, fontSize: 11, fontWeight: 600,
                    background: e.cacheHit ? '#dcfce7' : '#fef3c7',
                    color: e.cacheHit ? '#166534' : '#92400e'
                  }}>
                    {e.cacheHit ? 'HIT' : 'MISS'}
                  </span>
                </td>
                <td style={{ padding: 8 }}>{e.latencyMs}ms</td>
                <td style={{ padding: 8 }}>${e.estimatedCostUsd.toFixed(4)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div style={{
      background: '#fff', borderRadius: 8, padding: 20,
      boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
    }}>
      <div style={{ fontSize: 13, color: '#6b7280', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 700 }}>{value}</div>
    </div>
  )
}

export default CacheAnalytics
