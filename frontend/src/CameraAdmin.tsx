import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, mediaUrl } from './api'
import type { Camera } from './CameraDetail'
import { label } from './format'

interface Zone {
  id: number
  cameraId: number
  name: string
  polygon: [number, number][]
}

type RuleType = 'LOITERING' | 'ZONE_ENTRY' | 'AFTER_HOURS_VEHICLE'

interface Rule {
  id: number
  cameraId: number
  zoneId: number | null
  type: RuleType
  params: Record<string, unknown>
  enabled: boolean
}

const RULE_TYPES: RuleType[] = ['LOITERING', 'ZONE_ENTRY', 'AFTER_HOURS_VEHICLE']

/** Admin-only tools on a camera page: edit or delete the camera, draw zones, set rules. */
function CameraAdmin({ camera, onChanged, onDeleted }: {
  camera: Camera
  onChanged: (camera: Camera) => void
  onDeleted: () => void
}) {
  const [zones, setZones] = useState<Zone[]>([])
  const [error, setError] = useState<string | null>(null)

  const loadZones = useCallback(async () => {
    try {
      setZones(await api<Zone[]>(`/api/cameras/${camera.id}/zones`))
    } catch (e) {
      setError(message(e))
    }
  }, [camera.id])

  useEffect(() => {
    loadZones()
  }, [loadZones])

  return (
    <section style={{ borderTop: '1px solid var(--border)', marginTop: 32, paddingTop: 16 }}>
      <h3>Camera settings (admin)</h3>
      {error && <p role="alert">{error}</p>}
      <EditCamera camera={camera} onChanged={onChanged} onDeleted={onDeleted} />
      <ZoneEditor cameraId={camera.id} zones={zones} onChanged={loadZones} />
      <RulesPanel cameraId={camera.id} zones={zones} />
    </section>
  )
}

function EditCamera({ camera, onChanged, onDeleted }: {
  camera: Camera
  onChanged: (camera: Camera) => void
  onDeleted: () => void
}) {
  const [name, setName] = useState(camera.name)
  const [location, setLocation] = useState(camera.location ?? '')
  const [status, setStatus] = useState<string | null>(null)

  async function save(e: React.FormEvent) {
    e.preventDefault()
    try {
      const updated = await api<Camera>(`/api/cameras/${camera.id}`, {
        method: 'PUT', body: JSON.stringify({ name, location }),
      })
      onChanged(updated)
      setStatus('Saved.')
    } catch (err) {
      setStatus(message(err))
    }
  }

  async function remove() {
    if (!window.confirm(`Delete camera “${camera.name}”? Its zones and rules are deleted too.`)) return
    try {
      await api<void>(`/api/cameras/${camera.id}`, { method: 'DELETE' })
      onDeleted()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        if (!window.confirm('This camera has footage. Delete all its videos, events and clips as well? This cannot be undone.')) return
        try {
          await api<void>(`/api/cameras/${camera.id}?deleteFootage=true`, { method: 'DELETE' })
          onDeleted()
        } catch (err2) {
          setStatus(message(err2))
        }
      } else {
        setStatus(message(err))
      }
    }
  }

  return (
    <form onSubmit={save} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', marginBottom: 16 }}>
      <label htmlFor="edit-name">Name</label>
      <input id="edit-name" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="edit-location">Location</label>
      <input id="edit-location" maxLength={200} value={location} onChange={(e) => setLocation(e.target.value)} />
      <button type="submit">Save</button>
      <button type="button" onClick={remove}>Delete camera</button>
      {status && <span role="status">{status}</span>}
    </form>
  )
}

/**
 * Draw a zone by clicking points on a frame from this camera. Points are stored normalized to
 * 0..1, so the zone fits any resolution. A person counts as inside when their feet are.
 */
function ZoneEditor({ cameraId, zones, onChanged }: { cameraId: number; zones: Zone[]; onChanged: () => void }) {
  const [frame, setFrame] = useState<string | null>(null)
  const [points, setPoints] = useState<[number, number][]>([])
  const [name, setName] = useState('')
  const [status, setStatus] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    // The most recent event with a thumbnail gives a view of the scene to draw on.
    api<{ content: { thumbnailUrl: string | null }[] }>(`/api/events?cameraId=${cameraId}&size=20`)
      .then(async (page) => {
        const withThumb = page.content.find((e) => e.thumbnailUrl)
        if (withThumb?.thumbnailUrl) {
          const url = await mediaUrl(withThumb.thumbnailUrl)
          if (!cancelled) setFrame(url)
        }
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [cameraId])

  function addPoint(e: React.MouseEvent<SVGSVGElement>) {
    const box = e.currentTarget.getBoundingClientRect()
    const x = round((e.clientX - box.left) / box.width)
    const y = round((e.clientY - box.top) / box.height)
    setPoints([...points, [x, y]])
  }

  async function save() {
    if (points.length < 3 || !name.trim()) {
      setStatus('Click at least 3 points and give the zone a name.')
      return
    }
    try {
      await api(`/api/cameras/${cameraId}/zones`, { method: 'POST', body: JSON.stringify({ name, polygon: points }) })
      setPoints([])
      setName('')
      setStatus('Zone saved.')
      onChanged()
    } catch (err) {
      setStatus(message(err))
    }
  }

  async function remove(zone: Zone) {
    if (!window.confirm(`Delete zone “${zone.name}”? Rules that use it are deleted too.`)) return
    try {
      await api<void>(`/api/zones/${zone.id}`, { method: 'DELETE' })
      onChanged()
    } catch (err) {
      setStatus(message(err))
    }
  }

  return (
    <div style={{ marginBottom: 24 }}>
      <h4>Zones</h4>
      <p>Click on the frame to place the corners of a restricted zone, then name and save it.</p>
      <div style={{ position: 'relative', width: '100%', maxWidth: 640, aspectRatio: '16 / 9', background: '#3a3f47' }}>
        {frame ? (
          <img src={frame} alt="Recent frame from this camera" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'fill' }} />
        ) : (
          <p style={{ position: 'absolute', inset: 0, margin: 0, padding: 16, color: '#e6e8eb' }}>
            No frame yet. Upload footage to draw on a real view; the grey area stands for the full frame.
          </p>
        )}
        <svg viewBox="0 0 1 1" preserveAspectRatio="none" onClick={addPoint} role="img"
          aria-label="Zone drawing area" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', cursor: 'crosshair' }}>
          {zones.map((z) => (
            <polygon key={z.id} points={z.polygon.map((p) => p.join(',')).join(' ')}
              fill="rgba(47,111,176,0.3)" stroke="#2f6fb0" strokeWidth={0.004} />
          ))}
          {points.length > 0 && (
            <polygon points={points.map((p) => p.join(',')).join(' ')} fill="rgba(232,163,61,0.3)" stroke="#e8a33d" strokeWidth={0.004} />
          )}
          {points.map((p, i) => <circle key={i} cx={p[0]} cy={p[1]} r={0.008} fill="#e8a33d" />)}
        </svg>
      </div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
        <input aria-label="Zone name" placeholder="Zone name" maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        <button type="button" onClick={save}>Save zone ({points.length} points)</button>
        <button type="button" onClick={() => setPoints([])} disabled={points.length === 0}>Clear points</button>
      </div>
      {status && <p role="status">{status}</p>}
      {zones.length > 0 && (
        <ul>
          {zones.map((z) => (
            <li key={z.id}>
              {z.name} ({z.polygon.length} points){' '}
              <button type="button" onClick={() => remove(z)}>Delete</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function RulesPanel({ cameraId, zones }: { cameraId: number; zones: Zone[] }) {
  const [rules, setRules] = useState<Rule[]>([])
  const [type, setType] = useState<RuleType>('LOITERING')
  const [zoneId, setZoneId] = useState('')
  const [minSeconds, setMinSeconds] = useState('30')
  const [start, setStart] = useState('22:00')
  const [end, setEnd] = useState('06:00')
  const [status, setStatus] = useState<string | null>(null)
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone

  const load = useCallback(async () => {
    try {
      setRules(await api<Rule[]>(`/api/cameras/${cameraId}/rules`))
    } catch (e) {
      setStatus(message(e))
    }
  }, [cameraId])

  // Reload when zones change: deleting a zone deletes its rules.
  useEffect(() => {
    load()
  }, [load, zones])

  async function create(e: React.FormEvent) {
    e.preventDefault()
    const params: Record<string, unknown> =
      type === 'LOITERING' ? { minSeconds: Number(minSeconds) }
        : type === 'AFTER_HOURS_VEHICLE' ? { start, end, timezone } : {}
    try {
      await api(`/api/cameras/${cameraId}/rules`, {
        method: 'POST', body: JSON.stringify({ type, zoneId: zoneId ? Number(zoneId) : null, params }),
      })
      setStatus('Rule added. It applies to footage uploaded from now on.')
      load()
    } catch (err) {
      setStatus(message(err))
    }
  }

  async function toggle(rule: Rule) {
    try {
      await api(`/api/rules/${rule.id}`, {
        method: 'PUT',
        body: JSON.stringify({ type: rule.type, zoneId: rule.zoneId, params: rule.params, enabled: !rule.enabled }),
      })
      load()
    } catch (err) {
      setStatus(message(err))
    }
  }

  async function remove(rule: Rule) {
    try {
      await api<void>(`/api/rules/${rule.id}`, { method: 'DELETE' })
      load()
    } catch (err) {
      setStatus(message(err))
    }
  }

  const zoneName = (id: number | null) => (id == null ? 'whole frame' : zones.find((z) => z.id === id)?.name ?? `zone ${id}`)
  const needsZone = type !== 'AFTER_HOURS_VEHICLE'

  return (
    <div>
      <h4>Rules</h4>
      {rules.length === 0 ? <p>No rules yet.</p> : (
        <ul>
          {rules.map((r) => (
            <li key={r.id}>
              {label(r.type)} in {zoneName(r.zoneId)} {describe(r)} {r.enabled ? '' : '(off)'}{' '}
              <button type="button" onClick={() => toggle(r)}>{r.enabled ? 'Turn off' : 'Turn on'}</button>{' '}
              <button type="button" onClick={() => remove(r)}>Delete</button>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={create} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <select aria-label="Rule type" value={type} onChange={(e) => setType(e.target.value as RuleType)}>
          {RULE_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
        </select>
        <select aria-label="Zone" value={zoneId} onChange={(e) => setZoneId(e.target.value)} required={needsZone}>
          <option value="">{needsZone ? 'Choose a zone' : 'Whole frame'}</option>
          {zones.map((z) => <option key={z.id} value={z.id}>{z.name}</option>)}
        </select>
        {type === 'LOITERING' && (
          <label>Longer than <input type="number" min={1} step={1} value={minSeconds} required style={{ width: 80 }}
            onChange={(e) => setMinSeconds(e.target.value)} /> s</label>
        )}
        {type === 'AFTER_HOURS_VEHICLE' && (
          <>
            <label>From <input type="time" value={start} required onChange={(e) => setStart(e.target.value)} /></label>
            <label>to <input type="time" value={end} required onChange={(e) => setEnd(e.target.value)} /></label>
            <span>({timezone})</span>
          </>
        )}
        <button type="submit">Add rule</button>
      </form>
      {status && <p role="status">{status}</p>}
    </div>
  )
}

function describe(rule: Rule): string {
  if (rule.type === 'LOITERING') return `longer than ${rule.params.minSeconds} s`
  if (rule.type === 'AFTER_HOURS_VEHICLE') {
    return `from ${rule.params.start} to ${rule.params.end}${rule.params.timezone ? ` (${rule.params.timezone})` : ' (UTC)'}`
  }
  return ''
}

function round(v: number): number {
  return Math.min(1, Math.max(0, Math.round(v * 1000) / 1000))
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : 'Something went wrong'
}

export default CameraAdmin
