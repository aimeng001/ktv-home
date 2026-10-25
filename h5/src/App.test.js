// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const { apiMock } = vi.hoisted(() => ({
  apiMock: { registerUser: vi.fn() }
}))

vi.mock('./api/client', () => ({ default: apiMock }))

import App from './App.vue'
import { useFavoritesStore } from './stores/favorites'
import { usePlayerStore } from './stores/player'
import { useUserStore } from './stores/user'

describe('App startup registration', () => {
  let app
  let root
  let router
  let user
  let player
  let favorites

  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.registerUser.mockResolvedValue({ id: 17, nickname: '服务端昵称' })
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
    localStorage.clear()
  })

  it.each([
    ['未注册身份', false, false],
    ['只有本地身份', true, false]
  ])('%s 时不启动网络会话', async (_label, registered, serverRegistered) => {
    await mountApp({ registered, serverRegistered })

    expect(apiMock.registerUser).not.toHaveBeenCalled()
    expect(player.connect).not.toHaveBeenCalled()
    expect(favorites.load).not.toHaveBeenCalled()
  })

  it('每次启动向服务端重新注册，再连接播放器并加载收藏', async () => {
    await mountApp({ registered: true, serverRegistered: true, favoritesLoadRejected: true })

    expect(apiMock.registerUser).toHaveBeenCalledWith('client-token', '本地昵称')
    expect(user.serverUserId).toBe(17)
    expect(user.nickname).toBe('服务端昵称')
    expect(player.connect).toHaveBeenCalledOnce()
    expect(favorites.load).toHaveBeenCalledWith('client-token')
    expect(user.registrationError).toBe('')

    app.unmount()
    app = null
    expect(player.disconnect).toHaveBeenCalledOnce()
  })

  it('服务端注册失败时清除写身份、断开播放器并返回入口页', async () => {
    apiMock.registerUser.mockRejectedValueOnce(new Error('server unavailable'))
    await mountApp({ registered: true, serverRegistered: true })

    expect(user.serverUserId).toBeNull()
    expect(localStorage.getItem('ktv_server_user_id')).toBeNull()
    expect(user.registrationError).toBe('server unavailable')
    expect(player.connect).not.toHaveBeenCalled()
    expect(player.disconnect).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('entry')
  })

  it('拒绝缺少有效用户身份的注册响应', async () => {
    apiMock.registerUser.mockResolvedValueOnce({ id: 0, nickname: '' })
    await mountApp({ registered: true, serverRegistered: true })

    expect(user.serverUserId).toBeNull()
    expect(user.isReady).toBe(false)
    expect(user.registrationError).toBe('服务端未返回有效用户身份')
    expect(player.connect).not.toHaveBeenCalled()
    expect(player.disconnect).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('entry')
  })

  async function mountApp({ registered, serverRegistered, favoritesLoadRejected = false }) {
    if (registered) {
      localStorage.setItem('ktv_client_token', 'client-token')
      localStorage.setItem('ktv_nickname', '本地昵称')
    }
    if (serverRegistered) localStorage.setItem('ktv_server_user_id', '12')

    const pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore(pinia)
    player = usePlayerStore(pinia)
    favorites = useFavoritesStore(pinia)
    player.connect = vi.fn()
    player.disconnect = vi.fn()
    favorites.load = favoritesLoadRejected
      ? vi.fn().mockRejectedValueOnce(new Error('favorite endpoint unavailable'))
      : vi.fn().mockResolvedValue()

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'entry', component: { template: '<div>entry</div>' } },
        { path: '/home', name: 'home', component: { template: '<div>home</div>' } }
      ]
    })
    await router.push('/home')
    await router.isReady()

    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(App)
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  }

  async function flushUi() {
    await Promise.resolve()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }
})
