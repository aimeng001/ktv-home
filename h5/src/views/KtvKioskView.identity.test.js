// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    registerUser: vi.fn(),
    standbyContent: vi.fn(() => Promise.resolve({}))
  }
}))

vi.mock('../api/client', () => ({ default: apiMock, makeControls: () => ({}) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ fullPath: '/', path: '/', name: 'home', query: {}, params: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  RouterLink: { render: () => null }
}))
vi.mock('../components/ktv/KtvBentoDashboard.vue', () => ({ default: { render: () => null } }))
vi.mock('../components/ktv/KtvThreeColumnBrowser.vue', () => ({ default: { render: () => null } }))
vi.mock('../components/ktv/KtvMediaSurface.vue', () => ({ default: { render: () => null } }))
vi.mock('../components/ktv/KtvQueueDrawer.vue', () => ({ default: { render: () => null } }))
vi.mock('../components/ktv/KtvModals.vue', () => ({ default: { render: () => null } }))

import KtvKioskView from './KtvKioskView.vue'
import { useUserStore } from '../stores/user'
import { usePlayerStore } from '../stores/player'

describe('KtvKioskView 身份初始化提示条', () => {
  let app
  let root
  let user
  let pinia

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore()
    user.ensureToken()
    user.setNickname('点歌人')

    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
    vi.clearAllMocks()
    localStorage.clear()
  })

  async function mountView() {
    app = createApp(KtvKioskView)
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()
  }

  function banner() {
    return root.querySelector('.identity-banner')
  }

  it('注册失败时渲染身份提示条', async () => {
    user.registrationError = 'server unavailable'
    await mountView()
    expect(banner()).not.toBeNull()
    expect(banner().textContent).toContain('身份')
  })

  it('提示条包含重试按钮', async () => {
    user.registrationError = 'server unavailable'
    await mountView()
    const retry = [...banner().querySelectorAll('button')].find(btn => (btn.textContent || '').includes('重试'))
    expect(retry).toBeTruthy()
  })

  it('点击重试成功后提示条消失', async () => {
    user.registrationError = 'server unavailable'
    apiMock.registerUser.mockResolvedValue({ id: 5, nickname: '点歌人' })
    await mountView()

    const retry = [...banner().querySelectorAll('button')].find(btn => (btn.textContent || '').includes('重试'))
    retry.click()
    await nextTick()
    await nextTick()
    await nextTick()

    expect(banner()).toBeNull()
    expect(user.isReady).toBe(true)
  })

  it('注册正常时不渲染提示条', async () => {
    user.markRegistrationSuccess({ id: 5, nickname: '点歌人' })
    await mountView()
    expect(banner()).toBeNull()
  })

  it('提示条是 kiosk-shell 的直接子元素', async () => {
    user.registrationError = 'server unavailable'
    await mountView()
    expect(banner().parentElement.classList.contains('ktv-kiosk-shell')).toBe(true)
  })
})
