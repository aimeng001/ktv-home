// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    standbyContent: vi.fn(),
    registerUser: vi.fn()
  }
}))

vi.mock('../api/client', () => ({ default: apiMock }))

import EntryView from './EntryView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('EntryView', () => {
  let app
  let root
  let router
  let user
  let player

  beforeEach(async () => {
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.standbyContent.mockResolvedValue({
      welcomeText: '欢迎回家',
      subtitle: '第一行\n房间：测试厅'
    })
    apiMock.registerUser.mockResolvedValue({ id: 23, nickname: '测试用户' })

    const pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore(pinia)
    player = usePlayerStore(pinia)
    player.connect = vi.fn()

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'entry', component: EntryView },
        { path: '/home', name: 'home', component: { template: '<div>home</div>' } }
      ]
    })
    await router.push('/')
    await router.isReady()

    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('loads standby branding and subtitle while keeping the suggested nickname editable', () => {
    expect(root.querySelector('.title').textContent).toBe('欢迎回家')
    expect(root.querySelector('.room').textContent).toBe('房间：测试厅')
    expect(root.querySelector('input').value).toMatch(/^家人\d{4}$/)
    expect(root.textContent).toContain('本机记忆，下次免填')
  })

  it('keeps the default branding when the optional standby request fails', async () => {
    app?.unmount()
    root?.remove()
    apiMock.standbyContent.mockRejectedValueOnce(new Error('standby unavailable'))
    await mountEntry()

    expect(root.querySelector('.title').textContent).toBe('家庭KTV')
    expect(root.querySelector('.room').textContent).toBe('房间：客厅')
    expect(root.querySelector('[role="alert"]')).toBeNull()
  })

  it('uses the first subtitle line when no separate room line is provided', async () => {
    app?.unmount()
    root?.remove()
    apiMock.standbyContent.mockResolvedValueOnce({ welcomeText: '欢迎', subtitle: '仅有一行副标题' })
    await mountEntry()

    expect(root.querySelector('.title').textContent).toBe('欢迎')
    expect(root.querySelector('.room').textContent).toBe('仅有一行副标题')
  })

  it('registers once while a request is pending and enters with the server identity', async () => {
    let finishRegistration
    apiMock.registerUser.mockReturnValueOnce(new Promise(resolve => { finishRegistration = resolve }))
    const nickname = root.querySelector('input')
    nickname.value = '  阿明  '
    nickname.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()

    const enter = root.querySelector('.enter-btn')
    enter.click()
    await flushUi()
    expect(enter.disabled).toBe(true)
    expect(enter.textContent).toContain('正在连接')
    expect(apiMock.registerUser).toHaveBeenCalledTimes(1)
    expect(apiMock.registerUser).toHaveBeenCalledWith(user.clientToken, '阿明')

    enter.click()
    await flushUi()
    expect(apiMock.registerUser).toHaveBeenCalledTimes(1)

    // A synthetic duplicate click can still reach the handler while the button is disabled.
    enter.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    await flushUi()
    expect(apiMock.registerUser).toHaveBeenCalledTimes(1)

    finishRegistration({ id: 23, nickname: '阿明2' })
    await flushUi()
    expect(user.isReady).toBe(true)
    expect(user.nickname).toBe('阿明2')
    expect(player.connect).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('home')
  })

  it('stays on entry after a registration failure and allows a successful retry', async () => {
    apiMock.registerUser
      .mockRejectedValueOnce(new Error('服务暂不可用'))
      .mockResolvedValueOnce({ id: 24, nickname: '重试成功' })

    root.querySelector('.enter-btn').click()
    await flushUi()
    expect(root.querySelector('[role="alert"]').textContent).toContain('服务暂不可用')
    expect(root.querySelector('.enter-btn').disabled).toBe(false)
    expect(user.isReady).toBe(false)
    expect(player.connect).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('entry')

    root.querySelector('.retry').click()
    await flushUi()
    expect(apiMock.registerUser).toHaveBeenCalledTimes(2)
    expect(user.isReady).toBe(true)
    expect(player.connect).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('home')
  })

  it('rejects an incomplete server identity without opening the player connection', async () => {
    apiMock.registerUser.mockResolvedValueOnce({ id: 0, nickname: '' })

    root.querySelector('.enter-btn').click()
    await flushUi()

    expect(root.querySelector('[role="alert"]').textContent).toContain('服务端未返回有效用户身份')
    expect(user.isReady).toBe(false)
    expect(player.connect).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('entry')
  })

  it('shows a connection fallback when the user store provides no registration error', async () => {
    apiMock.registerUser.mockRejectedValueOnce(new Error('registration failed'))
    vi.spyOn(user, 'markRegistrationFailure').mockImplementation(() => {})

    root.querySelector('.enter-btn').click()
    await flushUi()

    expect(root.querySelector('[role="alert"]').textContent).toContain('无法连接点歌服务，请重试')
    expect(user.isReady).toBe(false)
    expect(player.connect).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('entry')
  })

  async function mountEntry() {
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'entry', component: EntryView },
        { path: '/home', name: 'home', component: { template: '<div>home</div>' } }
      ]
    })
    await router.push('/')
    await router.isReady()
    root = document.createElement('div')
    document.body.appendChild(root)
    const pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore(pinia)
    player = usePlayerStore(pinia)
    player.connect = vi.fn()
    app = createApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  }
})

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
