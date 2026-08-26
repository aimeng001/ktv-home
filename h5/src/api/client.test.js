import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body
  }
}

describe('api client admin authentication', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('sends the admin token only to admin endpoints', async () => {
    localStorage.setItem('home-ktv.admin.token', 'session-token')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }))

    await api.adminStatus()
    expect(fetchMock.mock.calls[0][1].headers['X-Admin-Token']).toBe('session-token')

    await api.searchSongs('周杰伦')
    expect(fetchMock.mock.calls[1][1].headers['X-Admin-Token']).toBeUndefined()
  })

  it('clears the token and notifies the app when the server requires login', async () => {
    localStorage.setItem('home-ktv.admin.token', 'expired-token')
    const required = vi.fn()
    window.addEventListener('home-ktv-admin-auth-required', required)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({
      code: 'ADMIN_AUTH_REQUIRED',
      message: '请先登录管理后台'
    }, 401))

    await expect(api.adminStatus()).rejects.toMatchObject({
      code: 'ADMIN_AUTH_REQUIRED',
      status: 401
    })
    expect(localStorage.getItem('home-ktv.admin.token')).toBeNull()
    expect(required).toHaveBeenCalledTimes(1)
    window.removeEventListener('home-ktv-admin-auth-required', required)
  })
})
