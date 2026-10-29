import { useEffect, useState } from 'react'
import { mediaUrl } from './api'
import { label } from './format'

export interface EventItem {
  id: number
  eventType: string
  objectLabel: string
  trackId: number | null
  confidence: number | null
  startedAt: string
  endedAt: string | null
  zoneId: number | null
  thumbnailUrl: string | null
  clipUrl: string | null
}

/** One event's thumbnail and clip. Opening the clip is recorded in the audit log by the API. */
function EventPanel({ event, onClose }: { event: EventItem; onClose: () => void }) {
  const [thumb, setThumb] = useState<string | null>(null)
  const [clip, setClip] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const [t, c] = await Promise.all([
          event.thumbnailUrl ? mediaUrl(event.thumbnailUrl) : Promise.resolve(null),
          event.clipUrl ? mediaUrl(event.clipUrl) : Promise.resolve(null),
        ])
        if (!cancelled) {
          setThumb(t)
          setClip(c)
          setError(null)
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load the clip')
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [event])

  return (
    <section style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 16, margin: '16px 0' }}
      aria-label="Event details">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <h3 style={{ margin: 0 }}>
          {label(event.eventType)}: {event.objectLabel}
          {event.trackId != null ? `, track ${event.trackId}` : ''}
        </h3>
        <button type="button" onClick={onClose}>Close</button>
      </div>
      <p>
        {new Date(event.startedAt).toLocaleString()}
        {event.confidence != null ? ` · ${Math.round(event.confidence * 100)}% confidence` : ''}
      </p>
      {error && <p role="alert">{error}</p>}
      {!event.clipUrl && !event.thumbnailUrl && <p>No clip was saved for this event.</p>}
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        {clip && (
          // Signed links expire after a few minutes; reopening the event fetches a fresh one.
          <video src={clip} controls autoPlay muted playsInline style={{ maxWidth: '100%', width: 480, background: '#000' }}>
            Your browser cannot play this clip.
          </video>
        )}
        {thumb && !clip && <img src={thumb} alt={`${event.objectLabel} at the moment of highest confidence`} style={{ maxWidth: '100%', width: 480 }} />}
      </div>
    </section>
  )
}

export default EventPanel
