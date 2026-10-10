import { useEffect, useState } from 'react'
import { BarChart, Bar, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend, PieChart, Pie, Cell } from 'recharts'
import api from '../api/client'
import type { Stats, UsageEvent } from '../types'

function Overview() {
  const [stats, setStats] = useState<Stats | null>(null)
  const [events, setEvents] = useState<UsageEvent[]>([])
  const [days, setDays] = useState(7)

  const fetchData = () => {
    api.get(`/stats?days=${days}`).then(r => setStats(r.data)).catch(console.error)
    api.get(`/usage?days=${days}`).then(r => setEvents(r.data)).catch(console.error)
  }

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 30000)
    return () => clearInterval(interval)
  }, [days])

  const volumeByHour = events.reduce<Record<string, { requests: number; cacheHits: number; rateLimited: number }>>((acc, e) => {
    const hour = e.timestamp.slice(0, 13) + ':00'
    if (!acc[hour]) acc[hour] = { requests: 0, cacheHits: 0, rateLimited: 0 }
    acc[hour].requests += 1
    if (e.cacheHit && e.requestType !== 'embedding') acc[hour].cacheHits += 1
    if (e.status === 'rate_limited') acc[hour].rateLimited += 1
    return acc
  }, {})

  const volumeData = Object.entries(volumeByHour)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([time, data]) => ({
      time: formatTime(time),
      ...data,
    }))

  const latencyByHour = events
    .filter(e => e.status === 'success' && !e.cacheHit)
    .reduce<Record<string, { total: number; count: number }>>((acc, e) => {
      const hour = e.timestamp.slice(0, 13) + ':00'
      if (!acc[hour]) acc[hour] = { total: 0, count: 0 }
      acc[hour].total += e.latencyMs
      acc[hour].count += 1
      return acc
    }, {})

  const latencyData = Object.entries(latencyByHour)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([time, data]) => ({
      time: formatTime(time),
      avgLatency: Math.round(data.total / data.count),
    }))

  const requestTypeData = events.reduce<Record<string, number>>((acc, e) => {
    const type = e.requestType || 'unknown'
    acc[type] = (acc[type] || 0) + 1
    return acc
  }, {})

  const typeChartData = Object.entries(requestTypeData)
    .map(([type, count]) => ({ name: formatType(type), value: count }))

  const COLORS = ['#3b82f6', '#22c55e', '#eab308', '#ef4444', '#8b5cf6', '#ec4899']

  const costData = stats ? Object.entries(stats.costByProvider).map(([provider, cost]) => ({
    provider,
    cost: Math.round(cost * 10000) / 10000
  })) : []

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <h1 style={{ margin: 0 }}>Overview</h1>
        <select value={days} onChange={e => setDays(Number(e.target.value))}
                style={{ padding: '6px 12px', borderRadius: 4, border: '1px solid #ddd' }}>
          <option value={1}>Last 24h</option>
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
        </select>
      </div>

      {stats && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 16, marginBottom: 32 }}>
            <StatCard label="Total Requests" value={stats.totalRequests.toLocaleString()} />
            <StatCard label="Cache Hit Rate" value={`${stats.cacheHitRate}%`}
                       color={stats.cacheHitRate > 30 ? '#22c55e' : '#eab308'} />
            <StatCard label="Total Cost" value={`$${stats.totalCost.toFixed(4)}`} />
            <StatCard label="Rate Limit Hits" value={stats.rateLimitHits.toLocaleString()}
                       color={stats.rateLimitHits > 0 ? '#ef4444' : '#22c55e'} />
            <StatCard label="Error Rate" value={`${stats.errorRate}%`}
                       color={stats.errorRate > 5 ? '#ef4444' : '#22c55e'} />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24, marginBottom: 24 }}>
            <div style={{ background: '#fff', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
              <h3 style={{ marginTop: 0 }}>Request Volume (Hourly)</h3>
              <ResponsiveContainer width="100%" height={280}>
                <BarChart data={volumeData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="time" tick={{ fontSize: 11 }} angle={-30} textAnchor="end" height={50} />
                  <YAxis />
                  <Tooltip />
                  <Legend />
                  <Bar dataKey="requests" fill="#3b82f6" name="Requests" radius={[2, 2, 0, 0]} />
                  <Bar dataKey="cacheHits" fill="#22c55e" name="Cache Hits" radius={[2, 2, 0, 0]} />
                  <Bar dataKey="rateLimited" fill="#ef4444" name="Rate Limited" radius={[2, 2, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>

            <div style={{ background: '#fff', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
              <h3 style={{ marginTop: 0 }}>Avg Latency (Hourly, ms)</h3>
              <ResponsiveContainer width="100%" height={280}>
                <LineChart data={latencyData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="time" tick={{ fontSize: 11 }} angle={-30} textAnchor="end" height={50} />
                  <YAxis />
                  <Tooltip />
                  <Line type="monotone" dataKey="avgLatency" stroke="#8b5cf6" strokeWidth={2} dot={{ r: 3 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
            <div style={{ background: '#fff', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
              <h3 style={{ marginTop: 0 }}>Request Types</h3>
              <ResponsiveContainer width="100%" height={250}>
                <PieChart>
                  <Pie data={typeChartData} dataKey="value" nameKey="name" cx="50%" cy="50%"
                       outerRadius={90} label={({ name, value }) => `${name}: ${value}`}>
                    {typeChartData.map((_, i) => (
                      <Cell key={i} fill={COLORS[i % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip />
                </PieChart>
              </ResponsiveContainer>
            </div>

            <div style={{ background: '#fff', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
              <h3 style={{ marginTop: 0 }}>Cost by Provider</h3>
              <ResponsiveContainer width="100%" height={250}>
                <BarChart data={costData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="provider" />
                  <YAxis />
                  <Tooltip />
                  <Bar dataKey="cost" fill="#8b5cf6" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>

          <div style={{ background: '#fff', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.1)', marginTop: 24 }}>
            <h3 style={{ marginTop: 0 }}>Recent Requests</h3>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr style={{ borderBottom: '2px solid #e5e7eb', textAlign: 'left' }}>
                    <th style={{ padding: '8px 12px' }}>Time</th>
                    <th style={{ padding: '8px 12px' }}>Model</th>
                    <th style={{ padding: '8px 12px' }}>Type</th>
                    <th style={{ padding: '8px 12px' }}>Tokens</th>
                    <th style={{ padding: '8px 12px' }}>Latency</th>
                    <th style={{ padding: '8px 12px' }}>Cache</th>
                    <th style={{ padding: '8px 12px' }}>Status</th>
                    <th style={{ padding: '8px 12px' }}>Cost</th>
                  </tr>
                </thead>
                <tbody>
                  {events.slice(0, 50).map((e, i) => (
                    <tr key={e.id || i} style={{ borderBottom: '1px solid #f3f4f6' }}>
                      <td style={{ padding: '6px 12px', whiteSpace: 'nowrap' }}>
                        {new Date(e.timestamp).toLocaleString()}
                      </td>
                      <td style={{ padding: '6px 12px' }}>{e.model}</td>
                      <td style={{ padding: '6px 12px' }}>
                        <span style={{
                          padding: '2px 6px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                          background: typeColor(e.requestType).bg, color: typeColor(e.requestType).fg
                        }}>
                          {formatType(e.requestType)}
                        </span>
                      </td>
                      <td style={{ padding: '6px 12px' }}>{e.inputTokens + e.outputTokens}</td>
                      <td style={{ padding: '6px 12px' }}>{e.latencyMs}ms</td>
                      <td style={{ padding: '6px 12px' }}>
                        {e.requestType === 'embedding' ? (
                          <span style={{
                            padding: '2px 6px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                            background: '#f3f4f6', color: '#9ca3af'
                          }}>N/A</span>
                        ) : (
                          <span style={{
                            padding: '2px 6px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                            background: e.cacheHit ? '#dcfce7' : '#fef9c3',
                            color: e.cacheHit ? '#166534' : '#854d0e'
                          }}>
                            {e.cacheHit ? 'HIT' : 'MISS'}
                          </span>
                        )}
                      </td>
                      <td style={{ padding: '6px 12px' }}>
                        <span style={{
                          padding: '2px 6px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                          background: statusColor(e.status).bg, color: statusColor(e.status).fg
                        }}>
                          {e.status}
                        </span>
                      </td>
                      <td style={{ padding: '6px 12px' }}>${e.estimatedCostUsd.toFixed(4)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso)
    return `${(d.getMonth()+1).toString().padStart(2,'0')}/${d.getDate().toString().padStart(2,'0')} ${d.getHours().toString().padStart(2,'0')}:00`
  } catch {
    return iso.slice(5, 16)
  }
}

function formatType(type: string | null): string {
  if (!type) return 'unknown'
  return type.replace(/_/g, ' ')
}

function typeColor(type: string | null) {
  switch (type) {
    case 'chat_stream': return { bg: '#dbeafe', fg: '#1e40af' }
    case 'chat': return { bg: '#e0e7ff', fg: '#3730a3' }
    case 'embedding': return { bg: '#fce7f3', fg: '#9d174d' }
    case 'rate_limited': return { bg: '#fee2e2', fg: '#991b1b' }
    default: return { bg: '#f3f4f6', fg: '#374151' }
  }
}

function statusColor(status: string) {
  switch (status) {
    case 'success': return { bg: '#dcfce7', fg: '#166534' }
    case 'error': return { bg: '#fee2e2', fg: '#991b1b' }
    case 'rate_limited': return { bg: '#fef3c7', fg: '#92400e' }
    case 'circuit_open': return { bg: '#fce7f3', fg: '#9d174d' }
    default: return { bg: '#f3f4f6', fg: '#374151' }
  }
}

function StatCard({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div style={{
      background: '#fff', borderRadius: 8, padding: 20,
      boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
    }}>
      <div style={{ fontSize: 13, color: '#6b7280', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 700, color: color || '#111' }}>{value}</div>
    </div>
  )
}

export default Overview
