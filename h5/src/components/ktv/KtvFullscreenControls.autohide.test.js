// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock, routerMock, toastSpy, media } = vi.hoisted(() => ({
  apiMock: {},
  routerMock: {
    attach: vi.fn(),
    setRouting: vi.fn(),
    setGain: vi.fn(),
    setPitch: vi.fn(),
    resume: vi.fn(() => Promise.resolve()),
    isActive: vi.fn(() => false),
    isSuspended: () => false,
    dispose: vi.fn()
  },
  toastSpy: vi.fn(),
  media: { playImpl: () => Promise.resolve() }
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../player/webAudioChannelRouter', () => ({
  createWebAudioChannelRouter: () => routerMock
}))
vi.mock('../../player/streamSelection', () => ({
  buildWebStreamSpec: () => ({ url: '/api/stream/1', liveTranscode: false })
}))
vi.mock('../../composables/useToast', async () => {
  const { ref } = await import('vue')
  return { useToast: () => ({ message: ref(''), toast: toastSpy }) }
})

import KtvMediaSurface from './KtvMediaSurface.vue'
import { usePlayerStore } from '../../stores/player'

describe('KtvMediaSurface 全屏播控 3 秒自隐藏与跑马灯', () => {
  let app
  let root
  let player
  let pinia

  beforeAll(() => {
    Object.defineProperty(window.HTMLMediaElement.prototype, 'play', {
      configurable: true,
      writable: true,
      value: function play() { return media.playImpl() }
    })
    Object.defineProperty(window.HTMLMediaElement.prototype, 'pause', {
      configurable: true,
      writable: true,
      value: function pause() {}
    })
    Object.defineProperty(window.HTMLMediaElement.prototype, 'load', {
      configurable: true,
      writable: true,
      value: function load() {}
    })
  })

  beforeEach(() => {
    vi.useFakeTimers()
    pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore()
    player.tvOnline = false
    player.webPlayerRole = 'ACTIVE'
    player.modeOverride = 'auto'
    player.state = 'playing'
    player.nowPlaying = { queueId: 10, song: { id: 1, title: '海阔天空', artist: 'Beyond' } }
    player.queue = [{ queueId: 11, song: { id: 2, title: '光辉岁月', artist: 'Beyond' } }]

    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
    vi.clearAllMocks()
  })

  async function mountFullscreenSurface() {
    app = createApp(KtvMediaSurface, {
      fullscreen: true
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()
  }

  it('全屏下初始显示播控栏并在 3 秒无交互后自动隐藏', async () => {
    await mountFullscreenSurface()

    const bottomPanel = root.querySelector('.fs-bottom-panel')
    const topBar = root.querySelector('.fs-topbar')
    expect(bottomPanel).not.toBeNull()
    expect(topBar).not.toBeNull()
    expect(bottomPanel.classList.contains('hidden')).toBe(false)
    expect(topBar.classList.contains('hidden')).toBe(false)

    // 触发鼠标移动以启动 3000ms 计时
    const stage = root.querySelector('.media-stage')
    stage.dispatchEvent(new MouseEvent('mousemove'))
    await nextTick()

    // 前进 2900ms，仍然未隐藏
    vi.advanceTimersByTime(2900)
    await nextTick()
    expect(bottomPanel.classList.contains('hidden')).toBe(false)

    // 前进到 3000ms，自动隐藏
    vi.advanceTimersByTime(200)
    await nextTick()
    expect(bottomPanel.classList.contains('hidden')).toBe(true)
    expect(topBar.classList.contains('hidden')).toBe(true)
  })

  it('交互时能重新唤醒播控浮层并重置 3 秒计时器', async () => {
    await mountFullscreenSurface()
    const stage = root.querySelector('.media-stage')
    const bottomPanel = root.querySelector('.fs-bottom-panel')

    // 触发唤醒并等待其隐藏
    stage.dispatchEvent(new MouseEvent('mousemove'))
    vi.advanceTimersByTime(3100)
    await nextTick()
    expect(bottomPanel.classList.contains('hidden')).toBe(true)

    // 在 stage 移动鼠标重新唤出
    stage.dispatchEvent(new MouseEvent('mousemove'))
    await nextTick()
    expect(bottomPanel.classList.contains('hidden')).toBe(false)

    // 2 秒后持续在控制板上移动鼠标，重置计时器
    vi.advanceTimersByTime(2000)
    await nextTick()
    bottomPanel.dispatchEvent(new MouseEvent('mousemove'))
    await nextTick()

    // 再过 1.5 秒（距上一次已 3.5 秒，但距最近交互才 1.5 秒），依然处于显示状态
    vi.advanceTimersByTime(1500)
    await nextTick()
    expect(bottomPanel.classList.contains('hidden')).toBe(false)

    // 再过 2 秒（总共距最近交互 3.5 秒），平滑隐藏
    vi.advanceTimersByTime(2000)
    await nextTick()
    expect(bottomPanel.classList.contains('hidden')).toBe(true)
  })

  it('全屏切歌或变更下一首时通过顶栏走马灯展示并唤醒 3 秒，不再渲染重叠悬浮横幅', async () => {
    await mountFullscreenSurface()
    const topBar = root.querySelector('.fs-topbar')
    const stage = root.querySelector('.media-stage')

    // 先等待 OSD 自动隐藏
    stage.dispatchEvent(new MouseEvent('mousemove'))
    vi.advanceTimersByTime(3100)
    await nextTick()
    expect(topBar.classList.contains('hidden')).toBe(true)

    // 切换歌曲时自动唤醒顶栏，文案融合当前与下一首，且无独立 .fs-marquee-banner
    player.nowPlaying = { queueId: 20, song: { id: 3, title: '吻别', artist: '张学友' } }
    await nextTick()
    await nextTick()

    expect(root.querySelector('.fs-marquee-banner')).toBeNull()
    expect(topBar.classList.contains('hidden')).toBe(false)
    const marqueeText = root.querySelector('.fs-topbar .fs-now-text')?.textContent?.trim()
    expect(marqueeText).toContain('正在播放：吻别')
    expect(marqueeText).toContain('下一首：Beyond - 光辉岁月')

    // 3 秒后随控件一起平滑隐藏
    vi.advanceTimersByTime(3100)
    await nextTick()
    expect(topBar.classList.contains('hidden')).toBe(true)
  })

  it('公播模式下顶部走马灯显示随机公播前缀，底部控台按 IKTV 顺序排列 10 个大图标并触发音量/点歌事件', async () => {
    const emittedVolumes = []
    let openedBrowser = false
    let openedQueue = false
    let openedHome = false
    player.queue = []
    player.nowPlaying = { queueId: 30, autoPlay: true, song: { id: 9, title: '又十年', artist: '陈奕迅' } }
    app = createApp(KtvMediaSurface, {
      fullscreen: true,
      onVolumeDelta: delta => emittedVolumes.push(delta),
      onOpenBrowser: () => { openedBrowser = true },
      onOpenQueue: () => { openedQueue = true },
      onOpenHome: () => { openedHome = true }
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()

    const topBarText = root.querySelector('.fs-topbar .fs-now-text')?.textContent?.trim()
    expect(topBarText).toBe('随机公播：又十年')
    expect(root.querySelector('.fs-progress-row input[type="range"]')?.getAttribute('aria-label')).toBe('播放进度')

    const labels = Array.from(root.querySelectorAll('.fs-action-row .fs-act')).map(el => el.textContent.replace(/\d+/g, '').trim())
    expect(labels).toEqual(['暂停', '伴唱', '重唱', '切歌', '音量减', '音量加', '静音', '点歌台', '已点', '首页'])

    const btns = root.querySelectorAll('.fs-action-row .fs-act')
    btns[4].click()
    btns[5].click()
    btns[7].click()
    btns[8].click()
    btns[9].click()
    await nextTick()

    expect(emittedVolumes.length).toBe(2)
    expect(emittedVolumes[0]).toBeLessThan(0)
    expect(emittedVolumes[1]).toBeGreaterThan(0)
    expect(openedBrowser).toBe(true)
    expect(openedQueue).toBe(true)
    expect(openedHome).toBe(true)
  })
})
