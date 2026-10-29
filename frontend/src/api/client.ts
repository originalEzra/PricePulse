import type { ApiEnvelope } from './types'

export const AUTH_STORAGE_KEY = 'pricepulse.auth'

export class ApiClientError extends Error {
  constructor(
    message: string,
    readonly code = 'REQUEST_FAILED',
    readonly status = 0,
  ) {
    super(message)
  }
}

function accessToken(): string | null {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as { accessToken?: string }).accessToken ?? null : null
  } catch {
    return null
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = accessToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiClientError('无法连接服务，请确认前端和后端都已启动后刷新页面', 'NETWORK_ERROR')
  }

  const envelope = (await response.json().catch(() => null)) as ApiEnvelope<T> | null
  if (!response.ok || !envelope?.success) {
    if (response.status === 401 && token && accessToken() === token) {
      localStorage.removeItem(AUTH_STORAGE_KEY)
      window.dispatchEvent(new Event('pricepulse:unauthorized'))
    }
    throw new ApiClientError(
      envelope?.error?.message ?? `请求失败（HTTP ${response.status}）`,
      envelope?.error?.code,
      response.status,
    )
  }
  return envelope.data
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}
