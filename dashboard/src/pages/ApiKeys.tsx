import { useEffect, useState } from 'react'
import api from '../api/client'
import type { ApiKeyInfo, CreateKeyResponse } from '../types'

function ApiKeys() {
  const [keys, setKeys] = useState<ApiKeyInfo[]>([])
  const [showCreate, setShowCreate] = useState(false)
  const [newKeyName, setNewKeyName] = useState('')
  const [newKeyOwner, setNewKeyOwner] = useState('')
  const [createdKey, setCreatedKey] = useState<string | null>(null)

  const fetchKeys = () => {
    api.get('/keys').then(r => setKeys(r.data)).catch(console.error)
  }

  useEffect(() => {
    fetchKeys()
  }, [])

  const handleCreate = () => {
    api.post<CreateKeyResponse>('/keys', { name: newKeyName, owner: newKeyOwner })
      .then(r => {
        setCreatedKey(r.data.key)
        setNewKeyName('')
        setNewKeyOwner('')
        fetchKeys()
      })
      .catch(console.error)
  }

  const handleRevoke = (id: string) => {
    if (confirm('Revoke this API key?')) {
      api.delete(`/keys/${id}`).then(fetchKeys).catch(console.error)
    }
  }

  const handleToggle = (key: ApiKeyInfo) => {
    api.put(`/keys/${key.id}`, { enabled: !key.enabled }).then(fetchKeys).catch(console.error)
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <h1 style={{ margin: 0 }}>API Keys</h1>
        <button onClick={() => setShowCreate(true)} style={{
          padding: '8px 16px', background: '#3b82f6', color: '#fff',
          border: 'none', borderRadius: 6, cursor: 'pointer'
        }}>Create Key</button>
      </div>

      {showCreate && (
        <div style={{
          background: '#fff', borderRadius: 8, padding: 20, marginBottom: 24,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
        }}>
          <h3 style={{ marginTop: 0 }}>Create New API Key</h3>
          <div style={{ display: 'flex', gap: 12, marginBottom: 12 }}>
            <input placeholder="Name" value={newKeyName} onChange={e => setNewKeyName(e.target.value)}
                   style={{ padding: 8, border: '1px solid #d1d5db', borderRadius: 4, flex: 1 }} />
            <input placeholder="Owner" value={newKeyOwner} onChange={e => setNewKeyOwner(e.target.value)}
                   style={{ padding: 8, border: '1px solid #d1d5db', borderRadius: 4, flex: 1 }} />
            <button onClick={handleCreate} disabled={!newKeyName || !newKeyOwner} style={{
              padding: '8px 16px', background: '#22c55e', color: '#fff',
              border: 'none', borderRadius: 4, cursor: 'pointer'
            }}>Create</button>
            <button onClick={() => { setShowCreate(false); setCreatedKey(null) }} style={{
              padding: '8px 16px', background: '#6b7280', color: '#fff',
              border: 'none', borderRadius: 4, cursor: 'pointer'
            }}>Cancel</button>
          </div>
          {createdKey && (
            <div style={{
              padding: 12, background: '#f0fdf4', border: '1px solid #bbf7d0',
              borderRadius: 6, fontFamily: 'monospace', fontSize: 13, wordBreak: 'break-all'
            }}>
              <strong>Save this key — it won't be shown again:</strong><br />{createdKey}
            </div>
          )}
        </div>
      )}

      <div style={{ background: '#fff', borderRadius: 8, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #e5e7eb', textAlign: 'left' }}>
              <th style={{ padding: 12 }}>Name</th>
              <th style={{ padding: 12 }}>Owner</th>
              <th style={{ padding: 12 }}>RPM</th>
              <th style={{ padding: 12 }}>TPM</th>
              <th style={{ padding: 12 }}>Status</th>
              <th style={{ padding: 12 }}>Created</th>
              <th style={{ padding: 12 }}>Last Used</th>
              <th style={{ padding: 12 }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {keys.map(k => (
              <tr key={k.id} style={{ borderBottom: '1px solid #f3f4f6' }}>
                <td style={{ padding: 12, fontWeight: 500 }}>{k.name}</td>
                <td style={{ padding: 12 }}>{k.owner}</td>
                <td style={{ padding: 12 }}>{k.rateLimitRpm}</td>
                <td style={{ padding: 12 }}>{k.rateLimitTpm.toLocaleString()}</td>
                <td style={{ padding: 12 }}>
                  <span style={{
                    padding: '2px 8px', borderRadius: 12, fontSize: 11, fontWeight: 600,
                    background: k.enabled ? '#dcfce7' : '#fef2f2',
                    color: k.enabled ? '#166534' : '#991b1b'
                  }}>
                    {k.enabled ? 'Active' : 'Disabled'}
                  </span>
                </td>
                <td style={{ padding: 12 }}>{new Date(k.createdAt).toLocaleDateString()}</td>
                <td style={{ padding: 12 }}>{k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString() : '—'}</td>
                <td style={{ padding: 12 }}>
                  <button onClick={() => handleToggle(k)} style={{
                    padding: '4px 8px', marginRight: 8, fontSize: 12, cursor: 'pointer',
                    background: 'transparent', border: '1px solid #d1d5db', borderRadius: 4
                  }}>{k.enabled ? 'Disable' : 'Enable'}</button>
                  <button onClick={() => handleRevoke(k.id)} style={{
                    padding: '4px 8px', fontSize: 12, cursor: 'pointer',
                    background: 'transparent', border: '1px solid #fca5a5', borderRadius: 4, color: '#dc2626'
                  }}>Revoke</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export default ApiKeys
