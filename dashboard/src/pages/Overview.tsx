import { useEffect, useState } from 'react'
import { BarChart, Bar, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
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

  const volumeByDay = events.reduce<Record<string, number>>((acc, e) => {
    const day = e.timestamp.split('T')[0]
    acc[day] = (acc[day] || 0) + 1
    return acc
  }, {})

  const volumeData = Object.entries(volumeByDay)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, count]) => ({ date, requests: count }))

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
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16, marginBottom: 32 }}>
            <StatCard label="Total Requests" value={stats.totalRequests.toLocaleString()} />
            <StatCard label="Cache Hit Rate" value={`${stats.cacheHitRate}%`}
                       color={stats.cacheHitRate > 30 ? '#22c55e' : '#eab308'} />
            <StatCard label="Total Cost" value={`$${stats.totalCost.toFixed(4)}`} />
            <StatCard label="Error Rate" value={`${stats.errorRate}%`}
                       color={stats.errorRate > 5 ? '#ef4444' : '#22c55e'} />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
            <div style={{ background: '#fff', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
              <h3 style={{ marginTop: 0 }}>Request Volume</h3>
              <ResponsiveContainer width="100%" height={250}>
                <LineChart data={volumeData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                  <YAxis />
                  <Tooltip />
                  <Line type="monotone" dataKey="requests" stroke="#3b82f6" strokeWidth={2} />
                </LineChart>
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
        </>
      )}
    </div>
  )
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
