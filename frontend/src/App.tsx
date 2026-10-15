import { useCallback, useEffect, useState } from 'react'
import './App.css'
import { api, getToken, setToken, setUnauthorizedHandler, type User } from './api'
import Login from './Login'

interface Camera {
  id: number
  name: string
  location: string | null
  createdAt: string
}

function App() {
  const [user, setUser] = useState<User | null>(null)
  // True until we know whether a saved token is still valid.
  const [checking, setChecking] = useState(() => getToken() !== null)

  const logout = useCallback(() => {
    setToken(null)
    setUser(null)
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(logout)
    if (!getToken()) return
    api<User>('/api/auth/me')
      .then(setUser)
      .catch(() => logout())
      .finally(() => setChecking(false))
  }, [logout])

  return (
    <main style={{ maxWidth: 640, margin: '0 auto', padding: '2rem 1rem', textAlign: 'left' }}>
      <h1>Smart CCTV Analytics Platform</h1>
      {checking ? (
        <p>Loading…</p>
      ) : user ? (
        <>
          <p style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
            <span>
              Signed in as <strong>{user.displayName}</strong> ({user.role.toLowerCase()})
            </span>
            <button type="button" onClick={logout}>Log out</button>
          </p>
          <Cameras isAdmin={user.role === 'ADMIN'} />
        </>
      ) : (
        <Login onLogin={setUser} />
      )}
    </main>
  )
}

function Cameras({ isAdmin }: { isAdmin: boolean }) {
  const [cameras, setCameras] = useState<Camera[]>([])
  const [name, setName] = useState('')
  const [location, setLocation] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)

  const load = useCallback(async () => {
    try {
      setCameras(await api<Camera[]>('/api/cameras'))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load cameras')
    } finally {
      setLoaded(true)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function addCamera(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    try {
      await api<Camera>('/api/cameras', { method: 'POST', body: JSON.stringify({ name, location }) })
      setName('')
      setLocation('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not add camera')
    }
  }

  return (
    <section>
      <h2>Cameras</h2>

      {error && <p role="alert">{error}</p>}

      {isAdmin && (
        <form onSubmit={addCamera} style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
          <input id="camera-name" placeholder="Camera name" value={name} onChange={(e) => setName(e.target.value)} />
          <input id="camera-location" placeholder="Location" value={location} onChange={(e) => setLocation(e.target.value)} />
          <button type="submit">Add camera</button>
        </form>
      )}

      {loaded && cameras.length === 0 && !error ? (
        <p>{isAdmin ? 'No cameras yet. Add one above.' : 'No cameras are assigned to you yet. Ask an admin for access.'}</p>
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
    </section>
  )
}

export default App
