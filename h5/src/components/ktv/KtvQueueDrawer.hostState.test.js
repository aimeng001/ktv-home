// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock, controlsMock } = vi.hoisted(() => ({
  apiMock: {
    roomHostStatus: vi.fn(),
    recentHistory: vi.fn(() => Promise.resolve([])),
    claimRoomHost: vi.fn(),
    releaseRoomHost: vi.fn(),
    repeatHistory: vi.fn()
  },
  controlsMock: {
    effect: vi.fn(),
    top: vi.fn(() => Promise.resolve()),
    cancel: vi.fn(() => Promise.resolve())
  }
}))

vi.mock('../../api/client', () => ({
  default: apiMock,
  makeControls: () => controlsMock
}))
vi.mock('../../composables/useToast', async () => {
  const { ref } = await import('vue')
  return { useToast: () => ({ message: ref(''), toast: vi.fn() }) }
})

import KtvQueueDrawer from './KtvQueueDrawer.vue'
import { usePlayerStore } from '../../stores/player'
import { useUserStore } from '../../stores/user'

function buttonByText(root, text) {
  return [...root.querySelectorAll('button')].find(btn => (btn.textContent || '').includes(text)) || null
}

describe('KtvQueueDrawer 房主状态错误态', () => {
  let app
  let root
  let player
  let pinia

  beforeEach(async () => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore()
    user.ensureToken()
    user.markRegistrationSuccess({ id: 9, nickname: '点歌人' })
    player = usePlayerStore()

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

  async function mountDrawer() {
    app = createApp(KtvQueueDrawer, { open: true })
    app.component('RouterLink', { render: () => h('a') })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()
    await nextTick()
  }

  it('房主状态拉取失败时显示错误提示与重试按钮', async () => {
    apiMock.roomHostStatus.mockRejectedValue(new Error('boom'))
    await mountDrawer()
    expect(root.textContent).toContain('房主状态')
    expect(buttonByText(root, '重试')).not.toBeNull()
  })

  it('失败时仍保留认领房主按钮', async () => {
    apiMock.roomHostStatus.mockRejectedValue(new Error('boom'))
    await mountDrawer()
    expect(buttonByText(root, '认领房主')).not.toBeNull()
  })

  it('点击重试会再次请求并恢复正确状态', async () => {
    apiMock.roomHostStatus
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ claimed: true, hostUserId: 7, hostNickname: '小明', revision: 3 })
    await mountDrawer()
    expect(root.textContent).toContain('房主状态')

    buttonByText(root, '重试').click()
    await nextTick()
    await nextTick()
    await nextTick()
    expect(root.textContent).toContain('房主：小明')
    expect(root.textContent).not.toContain('房主状态获取失败')
  })

  it('WS 推送房主变更后错误行自动清除', async () => {
    apiMock.roomHostStatus.mockRejectedValue(new Error('boom'))
    await mountDrawer()
    expect(root.textContent).toContain('房主状态')

    player.roomHost = { claimed: true, hostUserId: 7, hostNickname: '小明', revision: 5, isHost: true }
    await nextTick()
    await nextTick()
    expect(root.textContent).toContain('你是房主')
    expect(root.textContent).not.toContain('房主状态获取失败')
  })

  it('拉取成功时保持现有行为不回归', async () => {
    apiMock.roomHostStatus.mockResolvedValue({ claimed: true, hostUserId: 7, hostNickname: '小明', revision: 1 })
    await mountDrawer()
    expect(root.textContent).toContain('房主：小明')
    expect(root.textContent).not.toContain('房主状态获取失败')
  })

  it('正在播放卡片提供重唱与切歌按钮并能正确触发 emit', async () => {
    apiMock.roomHostStatus.mockResolvedValue({ claimed: true, hostUserId: 7, hostNickname: '小明', revision: 1 })
    player.nowPlaying = {
      queueId: 101,
      orderedByNick: '歌神',
      song: { id: 1, title: '海阔天空', artist: 'Beyond' }
    }
    const restartSpy = vi.fn()
    const nextSpy = vi.fn()

    if (app) app.unmount()
    app = createApp(KtvQueueDrawer, {
      open: true,
      onRestart: restartSpy,
      onNext: nextSpy
    })
    app.component('RouterLink', { render: () => h('a') })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()

    expect(root.textContent).toContain('海阔天空')
    const restartBtn = buttonByText(root, '重唱')
    const nextBtn = buttonByText(root, '切歌')
    expect(restartBtn).not.toBeNull()
    expect(nextBtn).not.toBeNull()

    restartBtn.click()
    expect(restartSpy).toHaveBeenCalledTimes(1)

    nextBtn.click()
    expect(nextSpy).toHaveBeenCalledTimes(1)
  })

  it('队列顶歌与删除具备防抖禁用态并在异常时强制复位', async () => {
    let resolveTop
    const pendingTop = new Promise(resolve => { resolveTop = resolve })
    controlsMock.top.mockImplementationOnce(() => pendingTop)

    player.queue = [
      { queueId: 201, orderedBy: 9, orderedByNick: '点歌人', song: { id: 2, title: '光辉岁月' } }
    ]
    await mountDrawer()

    const topBtn = buttonByText(root, '顶歌')
    expect(topBtn).not.toBeNull()
    expect(topBtn.disabled).toBe(false)

    topBtn.click()
    await nextTick()

    expect(topBtn.disabled).toBe(true)
    expect(topBtn.textContent).toContain('置顶中')

    resolveTop({})
    await nextTick()
    await nextTick()

    expect(topBtn.disabled).toBe(false)
    expect(topBtn.textContent).toContain('顶歌')
  })
})
