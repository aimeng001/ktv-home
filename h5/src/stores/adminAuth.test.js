// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { clearAdminToken, getAdminToken, useAdminAuthStore } from './adminAuth'

describe('useAdminAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  afterEach(() => vi.unstubAllGlobals())

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

  it('clears non-string and empty tokens from state and storage', () => {
    localStorage.setItem('home-ktv.admin.token', 'old-token')
    const auth = useAdminAuthStore()

    auth.setToken(42)
    expect(auth.token).toBe('')
    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem('home-ktv.admin.token')).toBeNull()

    auth.setToken('')
    expect(auth.token).toBe('')
  })

  it('supports environments without localStorage', () => {
    vi.stubGlobal('localStorage', undefined)

    expect(getAdminToken()).toBe('')
    expect(() => clearAdminToken()).not.toThrow()

    const auth = useAdminAuthStore()
    expect(auth.token).toBe('')
    auth.setToken('')
    auth.clearToken()
    expect(auth.token).toBe('')
  })
})
