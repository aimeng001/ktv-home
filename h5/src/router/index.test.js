// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from './index'
import { useAdminAuthStore } from '../stores/adminAuth'

describe('application navigation guards', () => {
  beforeEach(async () => {
    localStorage.clear()
    setActivePinia(createPinia())
    await router.push('/')
  })

  it('allows immediate access to kiosk routes without a blocking entry form', async () => {
    await router.push('/search?q=晴天')
    expect(router.currentRoute.value.name).toBe('search')

    await router.push('/queue')
    expect(router.currentRoute.value.name).toBe('queue')
  })

  it('redirects an unauthenticated admin route to login with the original path', async () => {
    await router.push('/admin/settings?section=ai')

    expect(router.currentRoute.value.name).toBe('admin-login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/settings?section=ai')
  })

  it('redirects an authenticated admin away from login and allows the dashboard', async () => {
    useAdminAuthStore().setToken('test-admin-token')
    await router.push('/admin/login')

    expect(router.currentRoute.value.name).toBe('admin-dashboard')
  })
})
