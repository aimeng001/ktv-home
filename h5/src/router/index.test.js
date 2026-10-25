// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from './index'
import { useAdminAuthStore } from '../stores/adminAuth'
import { useUserStore } from '../stores/user'

describe('application navigation guards', () => {
  beforeEach(async () => {
    localStorage.clear()
    setActivePinia(createPinia())
    await router.push('/')
  })

  it('redirects an unregistered user to the entry page', async () => {
    await router.push('/search')

    expect(router.currentRoute.value.name).toBe('entry')
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

  it('allows public pages and registered users through the guard', async () => {
    await router.push('/admin/login')
    expect(router.currentRoute.value.name).toBe('admin-login')

    const user = useUserStore()
    user.register('测试用户')
    expect(user.markRegistrationSuccess({ id: 7, nickname: '测试用户' })).toBe(true)
    await router.push('/search')

    expect(router.currentRoute.value.name).toBe('search')
  })
})
