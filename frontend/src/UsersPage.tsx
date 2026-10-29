import { useCallback, useEffect, useState } from 'react'
import { api, type Role, type User } from './api'
import type { Camera } from './CameraDetail'

interface UserPage {
  content: User[]
}

/** Admin: create users, change role, enable or disable, and choose which cameras operators see. */
function UsersPage({ currentUserId }: { currentUserId: number }) {
  const [users, setUsers] = useState<User[]>([])
  const [cameras, setCameras] = useState<Camera[]>([])
  const [editing, setEditing] = useState<User | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const [page, cams] = await Promise.all([api<UserPage>('/api/users?size=100'), api<Camera[]>('/api/cameras')])
      setUsers(page.content)
      setCameras(cams)
    } catch (e) {
      setStatus(message(e))
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function update(user: User, change: Partial<Pick<User, 'role' | 'enabled'>>) {
    try {
      await api(`/api/users/${user.id}`, { method: 'PATCH', body: JSON.stringify(change) })
      setStatus(`Updated ${user.displayName}.`)
      load()
    } catch (e) {
      setStatus(message(e))
    }
  }

  return (
    <section>
      <h2>Users</h2>
      {status && <p role="status">{status}</p>}
      <div style={{ overflowX: 'auto' }}>
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <thead>
            <tr>{['Name', 'Email', 'Role', 'Status', 'Last login', ''].map((h) => <th key={h} style={cell}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td style={cell}>{u.displayName}{u.id === currentUserId ? ' (you)' : ''}</td>
                <td style={cell}>{u.email}</td>
                <td style={cell}>
                  <select aria-label={`Role of ${u.displayName}`} value={u.role}
                    onChange={(e) => update(u, { role: e.target.value as Role })}>
                    <option value="OPERATOR">Operator</option>
                    <option value="ADMIN">Admin</option>
                  </select>
                </td>
                <td style={cell}>
                  {u.enabled ? 'Active' : 'Disabled'}{' '}
                  <button type="button" onClick={() => update(u, { enabled: !u.enabled })}>
                    {u.enabled ? 'Disable' : 'Enable'}
                  </button>
                </td>
                <td style={cell}>{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString() : 'Never'}</td>
                <td style={cell}>
                  {u.role === 'OPERATOR' && <button type="button" onClick={() => setEditing(u)}>Cameras…</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editing && (
        <CameraAccess user={editing} cameras={cameras} onDone={(msg) => { setEditing(null); setStatus(msg) }} />
      )}
      <NewUser onCreated={(msg) => { setStatus(msg); load() }} />
    </section>
  )
}

function CameraAccess({ user, cameras, onDone }: { user: User; cameras: Camera[]; onDone: (msg: string | null) => void }) {
  const [selected, setSelected] = useState<Set<number> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<{ cameraIds: number[] }>(`/api/users/${user.id}/cameras`)
      .then((r) => setSelected(new Set(r.cameraIds)))
      .catch((e) => setError(message(e)))
  }, [user.id])

  async function save() {
    try {
      await api(`/api/users/${user.id}/cameras`, { method: 'PUT', body: JSON.stringify({ cameraIds: [...(selected ?? [])] }) })
      onDone(`Camera access saved for ${user.displayName}.`)
    } catch (e) {
      setError(message(e))
    }
  }

  function toggle(id: number) {
    const next = new Set(selected)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelected(next)
  }

  return (
    <section style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 16, margin: '16px 0' }}>
      <h3>Cameras {user.displayName} can see</h3>
      {error && <p role="alert">{error}</p>}
      {selected === null ? <p>Loading…</p> : cameras.length === 0 ? <p>No cameras exist yet.</p> : (
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {cameras.map((c) => (
            <li key={c.id}>
              <label>
                <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)} /> {c.name}
              </label>
            </li>
          ))}
        </ul>
      )}
      <button type="button" onClick={save} disabled={selected === null}>Save</button>{' '}
      <button type="button" onClick={() => onDone(null)}>Cancel</button>
    </section>
  )
}

function NewUser({ onCreated }: { onCreated: (msg: string) => void }) {
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>('OPERATOR')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    try {
      await api('/api/users', { method: 'POST', body: JSON.stringify({ email, displayName, password, role }) })
      setEmail('')
      setDisplayName('')
      setPassword('')
      setError(null)
      onCreated(`Created ${displayName}.`)
    } catch (err) {
      setError(message(err))
    }
  }

  return (
    <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 8, maxWidth: 360, marginTop: 24 }}>
      <h3>Add a user</h3>
      <label htmlFor="new-email">Email</label>
      <input id="new-email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
      <label htmlFor="new-name">Name</label>
      <input id="new-name" required maxLength={120} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
      <label htmlFor="new-password">Password (at least 12 characters)</label>
      <input id="new-password" type="password" autoComplete="new-password" required minLength={12} maxLength={72}
        value={password} onChange={(e) => setPassword(e.target.value)} />
      <label htmlFor="new-role">Role</label>
      <select id="new-role" value={role} onChange={(e) => setRole(e.target.value as Role)}>
        <option value="OPERATOR">Operator</option>
        <option value="ADMIN">Admin</option>
      </select>
      {error && <p role="alert">{error}</p>}
      <button type="submit">Create user</button>
    </form>
  )
}

const cell: React.CSSProperties = { borderBottom: '1px solid var(--border)', padding: '4px 8px', textAlign: 'left' }

function message(e: unknown): string {
  return e instanceof Error ? e.message : 'Something went wrong'
}

export default UsersPage
