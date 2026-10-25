// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock, routeMock, routerMock } = vi.hoisted(() => ({
  apiMock: { adminAuthStatus: vi.fn(), adminLogin: vi.fn() },
  routeMock: { query: {} },
  routerMock: { replace: vi.fn() }
}))

vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock }))
vi.mock('../../api/client', () => ({ api: apiMock }))

import AdminLoginView from './AdminLoginView.vue'
import { useAdminAuthStore } from '../../stores/adminAuth'

describe('AdminLoginView', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    routeMock.query = {}
    apiMock.adminAuthStatus.mockResolvedValue({ configured: true, authenticated: false })
    apiMock.adminLogin.mockResolvedValue({ token: 'admin-token' })
    pinia = createPinia()
    setActivePinia(pinia)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('disables password submission when the server has no configured admin password', async () => {
    apiMock.adminAuthStatus.mockResolvedValue({ configured: false, authenticated: false })
    await mount()

    expect(root.textContent).toContain('服务端尚未设置 KTV_ADMIN_PASSWORD')
    expect(root.querySelector('#admin-password').disabled).toBe(true)
    expect(root.querySelector('button[type="submit"]').disabled).toBe(true)
    root.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()
    expect(apiMock.adminLogin).not.toHaveBeenCalled()
  })

  it.each([
    ['/admin/songs', '/admin/songs'],
    ['//attacker.example', '/admin'],
    ['/outside', '/admin']
  ])('redirects an already authenticated admin safely (%s)', async (redirect, expected) => {
    routeMock.query = { redirect }
    apiMock.adminAuthStatus.mockResolvedValue({ configured: true, authenticated: true })
    await mount()

    expect(routerMock.replace).toHaveBeenCalledWith(expected)
  })

  it('reports auth-status errors and permits retry after a failed login', async () => {
    apiMock.adminAuthStatus.mockRejectedValueOnce(new Error('认证服务暂不可用'))
    apiMock.adminLogin.mockRejectedValueOnce(new Error('密码错误'))
    await mount()
    expect(root.textContent).toContain('认证服务暂不可用')

    const input = root.querySelector('#admin-password')
    input.value = 'bad-password'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    root.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()

    expect(root.textContent).toContain('密码错误')
    expect(apiMock.adminLogin).toHaveBeenCalledWith('bad-password')
    expect(useAdminAuthStore(pinia).token).toBe('')
  })

  it('shows safe fallback messages when auth status and login errors have no message', async () => {
    apiMock.adminAuthStatus.mockRejectedValueOnce({})
    apiMock.adminLogin.mockRejectedValueOnce({})
    await mount()

    expect(root.textContent).toContain('无法读取管理员配置')
    const input = root.querySelector('#admin-password')
    input.value = 'bad-password'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    root.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()

    expect(root.textContent).toContain('登录失败，请检查密码')
    expect(root.querySelector('#admin-password').disabled).toBe(false)
    expect(useAdminAuthStore(pinia).token).toBe('')
  })

  it('stores the issued token and redirects to an allowed nested admin path', async () => {
    routeMock.query = { redirect: '/admin/metadata?tab=review' }
    await mount()
    const input = root.querySelector('#admin-password')
    input.value = 'correct-password'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    root.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()

    expect(useAdminAuthStore(pinia).token).toBe('admin-token')
    expect(localStorage.getItem('home-ktv.admin.token')).toBe('admin-token')
    expect(routerMock.replace).toHaveBeenCalledWith('/admin/metadata?tab=review')
  })

  async function mount() {
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(AdminLoginView)
    app.use(pinia)
    app.mount(root)
    await flushUi()
  }
})

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
