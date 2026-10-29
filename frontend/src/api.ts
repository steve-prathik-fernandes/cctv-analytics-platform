// Small fetch wrapper: adds the bearer token and turns API problem responses into errors.

export type Role = 'ADMIN' | 'OPERATOR'

export interface User {
  id: number
  email: string
  displayName: string
  role: Role
  enabled: boolean
  createdAt: string
  lastLoginAt: string | null
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  user: User
}

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

// sessionStorage keeps the token for this tab only and clears it when the tab closes.
const TOKEN_KEY = 'cctv.token'

export function getToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    // Storage unavailable (private mode); the user will need to log in again on reload.
  }
}

/** Called when the API rejects the token, so the app can return to the login screen. */
let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  // FormData sets its own multipart Content-Type with the boundary; only label JSON bodies.
  if (typeof init.body === 'string' && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  let res: Response
  try {
    res = await fetch(path, { ...init, headers })
  } catch {
    throw new ApiError(0, 'Backend not reachable. Is it running on port 8080?')
  }

  if (res.status === 401 && token) {
    setToken(null)
    onUnauthorized()
  }
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try {
      const problem = await res.json()
      if (problem.errors?.length) {
        message = problem.errors.map((e: { field: string; message: string }) => `${e.field} ${e.message}`).join(', ')
      } else if (problem.detail) {
        message = problem.detail
      }
    } catch {
      // Not JSON; keep the generic message.
    }
    throw new ApiError(res.status, message)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

/**
 * Resolves a clip or thumbnail endpoint to its short-lived signed link, which <img> and
 * <video> can load without the login token. The API answers with a redirect; fetch follows it,
 * so the final URL is the signed link. The body is not needed and is cancelled.
 */
export async function mediaUrl(path: string): Promise<string> {
  const token = getToken()
  let res: Response
  try {
    res = await fetch(path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  } catch {
    throw new ApiError(0, 'Backend not reachable. Is it running on port 8080?')
  }
  if (res.status === 401 && token) {
    setToken(null)
    onUnauthorized()
  }
  if (!res.ok) throw new ApiError(res.status, res.status === 404 ? 'Not available for this event' : `Request failed (${res.status})`)
  res.body?.cancel()
  return res.url
}
