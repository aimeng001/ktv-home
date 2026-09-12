// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { clearAdminToken, useAdminAuthStore } from './adminAuth'

describe('useAdminAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('persists an admin token and can clear it', () => {
    const auth = useAdminAuthStore()

    auth.setToken('session-token')
    expect(auth.token).toBe('session-token')
    expect(auth.isAuthenticated).toBe(true)
    expect(localStorage.getItem('home-ktv.admin.token')).toBe('session-token')

    auth.clearToken()
    expect(auth.token).toBe('')
    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem('home-ktv.admin.token')).toBeNull()
  })

  it('clears a stale token without requiring a Pinia instance', () => {
    localStorage.setItem('home-ktv.admin.token', 'stale-token')

    clearAdminToken()

    expect(localStorage.getItem('home-ktv.admin.token')).toBeNull()
  })
})
