import { useCallback, useEffect, useState } from 'react'
import { api } from './api'
import CameraAdmin from './CameraAdmin'
import EventPanel, { type EventItem } from './EventPanel'
import { label } from './format'

export interface Camera {
  id: number
  name: string
  location: string | null
  createdAt: string
}

type JobStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'

interface Job {
  id: number
  videoId: number
  status: JobStatus
  eventCount: number | null
  error: string | null
}

interface UploadResult {
  video: { id: number; originalFilename: string; sizeBytes: number }
  job: Job
}

interface EventPage {
  content: EventItem[]
  totalElements: number
  page: number
  totalPages: number
}

const EVENT_TYPES = ['OBJECT_DETECTED', 'LOITERING', 'ZONE_ENTRY', 'AFTER_HOURS_VEHICLE']
const POLL_MS = 2000

function CameraDetail({ camera, isAdmin, onBack, onChanged }: {
  camera: Camera
  isAdmin: boolean
  onBack: () => void
  onChanged: (camera: Camera | null) => void
}) {
  const [events, setEvents] = useState<EventPage | null>(null)
  const [open, setOpen] = useState<EventItem | null>(null)
  const [eventType, setEventType] = useState('')
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const loadEvents = useCallback(async () => {
    const params = new URLSearchParams({ cameraId: String(camera.id), page: String(page), size: '20' })
    if (eventType) params.set('eventType', eventType)
    try {
      setEvents(await api<EventPage>(`/api/events?${params}`))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load events')
    }
  }, [camera.id, eventType, page])

  useEffect(() => {
    loadEvents()
  }, [loadEvents])

  return (
    <section>
      <p><button type="button" onClick={onBack}>← All cameras</button></p>
      <h2>{camera.name}</h2>
      {camera.location && <p>{camera.location}</p>}

      <Upload cameraId={camera.id} onDone={loadEvents} />

      <h3>Events</h3>
      <label htmlFor="event-type">Type </label>
      <select id="event-type" value={eventType} onChange={(e) => { setEventType(e.target.value); setPage(0) }}>
        <option value="">All</option>
        {EVENT_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
      </select>

      {error && <p role="alert">{error}</p>}
      {open && <EventPanel event={open} onClose={() => setOpen(null)} />}
      {events && events.totalElements === 0 && <p>No events yet. Upload footage to create some.</p>}
      {events && events.totalElements > 0 && (
        <>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ borderCollapse: 'collapse', width: '100%', marginTop: 8 }}>
              <thead>
                <tr>{['Time', 'Event', 'Object', 'Track', 'Duration', 'Confidence', ''].map((h) => (
                  <th key={h} style={cell}>{h}</th>
                ))}</tr>
              </thead>
              <tbody>
                {events.content.map((e) => (
                  <tr key={e.id}>
                    <td style={cell}>{new Date(e.startedAt).toLocaleString()}</td>
                    <td style={cell}>{label(e.eventType)}</td>
                    <td style={cell}>{e.objectLabel}</td>
                    <td style={cell}>{e.trackId ?? '–'}</td>
                    <td style={cell}>{duration(e.startedAt, e.endedAt)}</td>
                    <td style={cell}>{e.confidence == null ? '–' : `${Math.round(e.confidence * 100)}%`}</td>
                    <td style={cell}>
                      {(e.clipUrl || e.thumbnailUrl) && (
                        <button type="button" onClick={() => setOpen(e)} aria-label={`Play clip of event ${e.id}`}>Play</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {events.page + 1} of {events.totalPages} ({events.totalElements} events)</span>
            <button type="button" disabled={page + 1 >= events.totalPages} onClick={() => setPage(page + 1)}>Next</button>
          </p>
        </>
      )}

      {isAdmin && <CameraAdmin camera={camera} onChanged={onChanged} onDeleted={() => onChanged(null)} />}
    </section>
  )
}

function Upload({ cameraId, onDone }: { cameraId: number; onDone: () => void }) {
  const [file, setFile] = useState<File | null>(null)
  const [recordedAt, setRecordedAt] = useState('')
  const [job, setJob] = useState<Job | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const watchedJob = job && (job.status === 'QUEUED' || job.status === 'RUNNING') ? job.id : null

  // Poll the job while it is unfinished. Leaving the page or starting another upload cancels it.
  useEffect(() => {
    if (watchedJob === null) return
    let cancelled = false
    const timer = window.setTimeout(async () => {
      try {
        const next = await api<Job>(`/api/jobs/${watchedJob}`)
        if (cancelled) return
        setJob(next)
        if (next.status === 'DONE' || next.status === 'FAILED') onDone()
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Could not check the job')
      }
    }, POLL_MS)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [watchedJob, job, onDone])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!file || !recordedAt) return
    setBusy(true)
    setError(null)
    setJob(null)
    const form = new FormData()
    form.append('file', file)
    // datetime-local has no time zone; the browser's local zone is the camera's for now.
    form.append('recordedStartAt', new Date(recordedAt).toISOString())
    try {
      const result = await api<UploadResult>(`/api/cameras/${cameraId}/videos`, { method: 'POST', body: form })
      setJob(result.job)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 8, maxWidth: 420, margin: '16px 0' }}>
      <h3>Upload footage</h3>
      <label htmlFor="upload-file">MP4 file (up to 500 MB)</label>
      <input id="upload-file" type="file" accept="video/mp4,.mp4" required
        onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      <label htmlFor="upload-recorded">When the footage starts</label>
      <input id="upload-recorded" type="datetime-local" required value={recordedAt}
        onChange={(e) => setRecordedAt(e.target.value)} />
      <small>Date and time the recording begins, in your local time. Event times are based on it.</small>
      {/* Only disabled while uploading: if a field is missing, the browser points to it on submit. */}
      <button type="submit" disabled={busy}>{busy ? 'Uploading…' : 'Upload and process'}</button>
      {error && <p role="alert">{error}</p>}
      {job && <p role="status">{jobMessage(job)}</p>}
    </form>
  )
}

function jobMessage(job: Job): string {
  switch (job.status) {
    case 'QUEUED': return 'Uploaded. Waiting for the detection service…'
    case 'RUNNING': return 'Processing the footage…'
    case 'DONE': return `Done: ${job.eventCount} events found.`
    case 'FAILED': return `Processing failed: ${job.error ?? 'unknown error'}`
  }
}


function duration(start: string, end: string | null): string {
  if (!end) return '–'
  const seconds = (new Date(end).getTime() - new Date(start).getTime()) / 1000
  return `${seconds.toFixed(1)} s`
}

const cell: React.CSSProperties = { borderBottom: '1px solid var(--border)', padding: '4px 8px', textAlign: 'left' }

export default CameraDetail
