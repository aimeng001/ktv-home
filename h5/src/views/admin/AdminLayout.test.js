// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const { apiMock } = vi.hoisted(() => ({
  apiMock: { adminAuthStatus: vi.fn(), adminLogout: vi.fn(), releaseInfo: vi.fn() }
}))

vi.mock('../../api/client', () => ({ api: apiMock }))

import AdminLayout from './AdminLayout.vue'
import { useAdminAuthStore } from '../../stores/adminAuth'

const EmptyView = { template: '<div />' }
const READ_KEY = 'home-ktv.admin.releaseNoticeRead'
const DISMISSED_KEY = 'home-ktv.admin.releaseNoticeDismissed'

describe('AdminLayout', () => {
  let app
  let root
  let router
  let pinia
  let adminAuth

  beforeEach(async () => {
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
    apiMock.adminAuthStatus.mockResolvedValue({ authenticated: true })
    apiMock.adminLogout.mockResolvedValue(undefined)
    apiMock.releaseInfo.mockResolvedValue({
      version: '1.0.0',
      announcement: { enabled: false, id: '', title: '', message: '' },
      tv: { arm64V8a: { available: false }, armeabiV7a: { available: false } }
    })

    pinia = createPinia()
    setActivePinia(pinia)
    adminAuth = useAdminAuthStore(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/admin', name: 'admin-dashboard', component: EmptyView },
        { path: '/admin/source-library', name: 'admin-source-library', component: EmptyView },
        { path: '/admin/ktv-library', name: 'admin-ktv-library', component: EmptyView },
        { path: '/admin/artists', name: 'admin-artists', component: EmptyView },
        { path: '/admin/ai', name: 'admin-ai', component: EmptyView },
        { path: '/admin/settings', name: 'admin-settings', component: EmptyView },
        { path: '/admin/login', name: 'admin-login', component: EmptyView }
      ]
    })
    await router.push('/admin?tab=music')
    await router.isReady()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
    app = null
    root = null
    vi.restoreAllMocks()
  })

  it('renders navigation and the content slot without showing a disabled release notice', async () => {
    await mount('dashboard')

    expect(root.querySelector('.mi.on').textContent).toContain('仪表盘')
    expect(root.querySelectorAll('nav .mi')).toHaveLength(6)
    expect(root.querySelector('.page-slot').textContent).toBe('管理页面')
    expect(root.querySelector('[role="dialog"]')).toBeNull()
    expect(apiMock.adminAuthStatus).toHaveBeenCalledOnce()
    expect(apiMock.releaseInfo).toHaveBeenCalledOnce()
  })

  it('shows an available 64-bit release and remembers a session dismissal', async () => {
    apiMock.releaseInfo.mockResolvedValueOnce(releaseInfo({
      arm64V8a: { available: true, url: '/tv-arm64.apk', size: 1_048_576 },
      armeabiV7a: { available: false, url: '/tv-arm.apk', size: 0 }
    }))
    await mount('ktv')

    expect(root.querySelector('#release-notice-title').textContent).toBe('维护更新')
    expect(root.querySelector('.notice-kicker').textContent).toContain('2.1.0')
    expect(root.querySelectorAll('.package-link')).toHaveLength(1)
    expect(root.querySelector('.package-link').getAttribute('href')).toBe('/tv-arm64.apk')
    expect(root.querySelector('.package-link').textContent).toContain('1.0 MB')

    root.querySelector('.notice-close').click()
    await flushUi()
    expect(sessionStorage.getItem(DISMISSED_KEY)).toBe('release-2.1.0')
    expect(localStorage.getItem(READ_KEY)).toBeNull()

    await remount('ktv')
    expect(root.querySelector('[role="dialog"]')).toBeNull()
  })

  it('shows the 32-bit package, formats an invalid size, and persists a read notice', async () => {
    apiMock.releaseInfo.mockResolvedValueOnce(releaseInfo({
      arm64V8a: { available: false, url: '/tv-arm64.apk', size: 0 },
      armeabiV7a: { available: true, url: '/tv-arm.apk', size: Number.POSITIVE_INFINITY }
    }))
    await mount('settings')

    expect(root.querySelectorAll('.package-link')).toHaveLength(1)
    expect(root.querySelector('.package-link').getAttribute('href')).toBe('/tv-arm.apk')
    expect(root.querySelector('.package-link').textContent).toContain('大小未知')

    root.querySelector('.notice-primary').click()
    await flushUi()
    expect(localStorage.getItem(READ_KEY)).toBe('release-2.1.0')
    expect(root.querySelector('[role="dialog"]')).toBeNull()

    await remount('settings')
    expect(root.querySelector('[role="dialog"]')).toBeNull()
  })

  it('keeps the admin page available when release metadata is absent, disabled, or has no package', async () => {
    for (const info of [
      null,
      { ...releaseInfo(), announcement: { ...releaseInfo().announcement, enabled: false } },
      { ...releaseInfo(), announcement: { ...releaseInfo().announcement, id: '' } },
      { ...releaseInfo(), tv: { arm64V8a: { available: false }, armeabiV7a: { available: false } } }
    ]) {
      apiMock.releaseInfo.mockResolvedValueOnce(info)
      await mount('ai')
      expect(root.querySelector('[role="dialog"]')).toBeNull()
      expect(root.querySelector('.page-slot')).not.toBeNull()
      await unmount()
    }
  })

  it('clears an expired admin token and returns to login with the original route', async () => {
    adminAuth.setToken('expired-admin-token')
    apiMock.adminAuthStatus.mockResolvedValueOnce({ authenticated: false })

    await mount('source')

    expect(adminAuth.token).toBe('')
    expect(localStorage.getItem('home-ktv.admin.token')).toBeNull()
    expect(router.currentRoute.value.name).toBe('admin-login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin?tab=music')
  })

  it('does not redirect when status lookup fails, but still attempts release metadata', async () => {
    apiMock.adminAuthStatus.mockRejectedValueOnce(new Error('offline'))
    await mount('artists')

    expect(router.currentRoute.value.name).toBe('admin-dashboard')
    expect(apiMock.releaseInfo).toHaveBeenCalledOnce()
    expect(root.querySelector('.page-slot')).not.toBeNull()
  })

  it('handles the auth-required event and removes its listener on unmount', async () => {
    adminAuth.setToken('valid-admin-token')
    await mount('ai')

    window.dispatchEvent(new Event('home-ktv-admin-auth-required'))
    await flushUi()
    expect(adminAuth.token).toBe('')
    expect(router.currentRoute.value.name).toBe('admin-login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin?tab=music')

    await unmount()
    adminAuth.setToken('still-valid')
    await router.push('/admin?tab=music')
    window.dispatchEvent(new Event('home-ktv-admin-auth-required'))
    await flushUi()
    expect(adminAuth.token).toBe('still-valid')
    expect(router.currentRoute.value.name).toBe('admin-dashboard')
  })

  it.each([
    ['succeeds', false],
    ['fails', true]
  ])('completes local logout when the server request %s', async (_label, rejects) => {
    adminAuth.setToken('admin-token')
    if (rejects) apiMock.adminLogout.mockRejectedValueOnce(new Error('offline'))
    await mount('dashboard')

    root.querySelector('.logout').click()
    await flushUi()

    expect(apiMock.adminLogout).toHaveBeenCalledOnce()
    expect(adminAuth.token).toBe('')
    expect(localStorage.getItem('home-ktv.admin.token')).toBeNull()
    expect(router.currentRoute.value.name).toBe('admin-login')
  })

  it('keeps the shell mounted when release metadata cannot be loaded', async () => {
    apiMock.releaseInfo.mockRejectedValueOnce(new Error('offline'))
    await mount('settings')

    expect(root.querySelector('.page-slot')).not.toBeNull()
    expect(root.querySelector('[role="dialog"]')).toBeNull()
  })

  async function mount(active) {
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({
      render: () => h(AdminLayout, { active }, { default: () => h('div', { class: 'page-slot' }, '管理页面') })
    })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  }

  async function remount(active) {
    await unmount()
    await mount(active)
  }

  async function unmount() {
    app?.unmount()
    root?.remove()
    app = null
    root = null
    await flushUi()
  }
})

function releaseInfo({ arm64V8a, armeabiV7a } = {}) {
  return {
    version: '2.1.0',
    announcement: { enabled: true, id: 'release-2.1.0', title: '维护更新', message: '新增局域网播放优化' },
    tv: {
      arm64V8a: arm64V8a || { available: true, url: '/tv-arm64.apk', size: 1_048_576 },
      armeabiV7a: armeabiV7a || { available: false, url: '/tv-arm.apk', size: 0 }
    }
  }
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
