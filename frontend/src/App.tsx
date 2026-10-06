import { useEffect, useState } from 'react'
import './App.css'

interface Camera {
  id: number
  name: string
  location: string | null
  sourceUrl: string | null
}

function App() {
  const [cameras, setCameras] = useState<Camera[]>([])
  const [name, setName] = useState('')
  const [location, setLocation] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function load() {
    try {
      const res = await fetch('/api/cameras')
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      setCameras(await res.json())
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not reach the backend')
    }
  }

  useEffect(() => {
    load()
  }, [])

  async function addCamera(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    const res = await fetch('/api/cameras', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, location }),
    })
    if (!res.ok) {
      setError(`Could not add camera (${res.status})`)
      return
    }
    setName('')
    setLocation('')
    load()
  }

  return (
    <main style={{ maxWidth: 640, margin: '0 auto', padding: '2rem 1rem', textAlign: 'left' }}>
      <h1>Smart CCTV Analytics Platform</h1>
      <h2>Cameras</h2>

      {error && <p role="alert">Backend not reachable: {error}</p>}

      <form onSubmit={addCamera} style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        <input id="camera-name" placeholder="Camera name" value={name} onChange={(e) => setName(e.target.value)} />
        <input id="camera-location" placeholder="Location" value={location} onChange={(e) => setLocation(e.target.value)} />
        <button type="submit">Add camera</button>
      </form>

      {cameras.length === 0 && !error ? (
        <p>No cameras yet. Add one above.</p>
      ) : (
        <ul>
          {cameras.map((c) => (
            <li key={c.id}>
              {c.name}
              {c.location ? ` (${c.location})` : ''}
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}

export default App
