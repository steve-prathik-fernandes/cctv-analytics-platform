import { useCallback, useEffect, useState } from 'react'
import { api } from './api'

interface AuditEntry {
  id: number
  userId: number | null
  action: string
  resourceType: string | null
  resourceId: number | null
  ipAddress: string | null
  details: Record<string, unknown> | null
  createdAt: string
}

interface AuditPage {
  content: AuditEntry[]
  page: number
  totalPages: number
  totalElements: number
}

const ACTIONS = ['LOGIN_SUCCESS', 'LOGIN_FAILURE', 'LOGIN_BLOCKED', 'USER_CREATE', 'USER_UPDATE', 'USER_CAMERAS_UPDATE',
  'CAMERA_CREATE', 'CAMERA_UPDATE', 'CAMERA_DELETE', 'ZONE_CREATE', 'ZONE_DELETE', 'RULE_CREATE', 'RULE_UPDATE',
  'RULE_DELETE', 'VIDEO_UPLOAD', 'VIDEO_DELETE', 'EVENT_SEARCH', 'EVENT_VIEW', 'CLIP_VIEW']

/** Admin: read the audit log (UC-08), newest first, filtered by action. */
function AuditLogPage() {
  const [data, setData] = useState<AuditPage | null>(null)
  const [action, setAction] = useState('')
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    const params = new URLSearchParams({ page: String(page), size: '25' })
    if (action) params.set('action', action)
    try {
      setData(await api<AuditPage>(`/api/audit-logs?${params}`))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load the audit log')
    }
  }, [action, page])

  useEffect(() => {
    load()
  }, [load])

  return (
    <section>
      <h2>Audit log</h2>
      <label htmlFor="audit-action">Action </label>
      <select id="audit-action" value={action} onChange={(e) => { setAction(e.target.value); setPage(0) }}>
        <option value="">All</option>
        {ACTIONS.map((a) => <option key={a} value={a}>{a}</option>)}
      </select>
      {error && <p role="alert">{error}</p>}
      {data && (
        <>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ borderCollapse: 'collapse', width: '100%', marginTop: 8 }}>
              <thead>
                <tr>{['Time', 'User', 'Action', 'Resource', 'Details', 'IP'].map((h) => <th key={h} style={cell}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {data.content.map((a) => (
                  <tr key={a.id}>
                    <td style={cell}>{new Date(a.createdAt).toLocaleString()}</td>
                    <td style={cell}>{a.userId ?? '–'}</td>
                    <td style={cell}>{a.action}</td>
                    <td style={cell}>{a.resourceType ? `${a.resourceType} ${a.resourceId ?? ''}` : '–'}</td>
                    <td style={{ ...cell, fontFamily: 'var(--mono)', fontSize: 14 }}>{a.details ? JSON.stringify(a.details) : ''}</td>
                    <td style={cell}>{a.ipAddress ?? '–'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {data.page + 1} of {Math.max(data.totalPages, 1)} ({data.totalElements} entries)</span>
            <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
          </p>
        </>
      )}
    </section>
  )
}

const cell: React.CSSProperties = { borderBottom: '1px solid var(--border)', padding: '4px 8px', textAlign: 'left', verticalAlign: 'top' }

export default AuditLogPage
