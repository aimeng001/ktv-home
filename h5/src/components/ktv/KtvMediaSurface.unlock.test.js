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
    resumeDirect: vi.fn(),
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

function notAllowed() {
  return new DOMException('blocked', 'NotAllowedError')
}

describe('KtvMediaSurface 音频解锁失败处理', () => {
  let app
  let root
  let player
  let pinia
  let video

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
  })

  beforeEach(async () => {
    media.playImpl = () => Promise.reject(notAllowed())

    pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore()
    player.tvOnline = false
    player.webPlayerRole = 'ACTIVE'
    player.modeOverride = 'auto'
    player.state = 'playing'
    player.nowPlaying = { queueId: 1, song: { id: 1, title: '测试曲', artist: '测试' } }
    player.audioBlockedByAutoplay = true

    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(KtvMediaSurface, {})
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()
    video = root.querySelector('video')
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
    vi.clearAllMocks()
  })

  async function clickUnlock() {
    const btn = root.querySelector('.autoplay-unlock')
    expect(btn).not.toBeNull()
    btn.click()
    await nextTick()
    await nextTick()
  }

  it('解锁失败时保留阻断标志', async () => {
    await clickUnlock()
    expect(player.audioBlockedByAutoplay).toBe(true)
  })

  it('解锁失败时 videoEl.muted 保持 true', async () => {
    await clickUnlock()
    expect(video.muted).toBe(true)
  })

  it('解锁失败时给出提示', async () => {
    await clickUnlock()
    expect(toastSpy).toHaveBeenCalled()
    expect(toastSpy.mock.calls.at(-1)[0]).toContain('声音')
  })

  it('解锁失败后胶囊文案切换为失败态', async () => {
    await clickUnlock()
    expect(player.audioUnlockFailed).toBe(true)
    expect(root.querySelector('.autoplay-unlock').textContent).toContain('重试')
  })

  it('解锁成功时清除标志与失败态', async () => {
    media.playImpl = () => Promise.resolve()
    await clickUnlock()
    expect(player.audioBlockedByAutoplay).toBe(false)
    expect(player.audioUnlockFailed).toBe(false)
  })

  it('切歌后媒体加载成功可自愈', async () => {
    await clickUnlock()
    expect(player.audioUnlockFailed).toBe(true)

    media.playImpl = () => Promise.resolve()
    video.dispatchEvent(new Event('loadedmetadata'))
    await nextTick()
    await nextTick()
    expect(player.audioBlockedByAutoplay).toBe(false)
    expect(player.audioUnlockFailed).toBe(false)
  })
})

describe('KtvMediaSurface 解码失败时的上报兜底', () => {
  async function mountSurface() {
    const scopedPinia = createPinia()
    setActivePinia(scopedPinia)
    const scopedPlayer = usePlayerStore()
    scopedPlayer.tvOnline = false
    scopedPlayer.webPlayerRole = 'ACTIVE'
    scopedPlayer.modeOverride = 'auto'
    scopedPlayer.state = 'playing'
    scopedPlayer.playback = { sourceFileId: 9, kind: 'NATIVE', status: 'READY', streamUrl: '/api/stream/1' }
    scopedPlayer.nowPlaying = { queueId: 4, song: { id: 9, title: '测试曲', artist: '测试' } }

    const host = document.createElement('div')
    document.body.appendChild(host)
    const instance = createApp(KtvMediaSurface, {})
    instance.use(scopedPinia)
    instance.mount(host)
    await nextTick()
    await nextTick()
    return { instance, host, scopedPlayer }
  }

  it('上报没发出去时本地退到封面预览，不停在黑屏', async () => {
    const { instance, host, scopedPlayer } = await mountSurface()
    const reportSpy = vi.spyOn(scopedPlayer, 'reportPlayError').mockReturnValue(false)

    try {
      host.querySelector('video').dispatchEvent(new Event('error'))
      await nextTick()

      expect(reportSpy).toHaveBeenCalled()
      expect(host.querySelector('.poster-fallback')?.textContent || '')
        .toContain('已切换为封面与实时歌词预览')
    } finally {
      instance.unmount()
      host.remove()
    }
  })

  it('上报成功时交给服务端判定，本端不抢先切封面', async () => {
    const { instance, host, scopedPlayer } = await mountSurface()
    const reportSpy = vi.spyOn(scopedPlayer, 'reportPlayError').mockReturnValue(true)

    try {
      host.querySelector('video').dispatchEvent(new Event('error'))
      await nextTick()

      expect(reportSpy).toHaveBeenCalled()
      expect(host.querySelector('.poster-fallback')?.textContent || '')
        .not.toContain('已切换为封面与实时歌词预览')
    } finally {
      instance.unmount()
      host.remove()
    }
  })

  it("切换 vocalMode 时仅平移声道路由，不重载 video.src 且不改变流地址", async () => {
    const { instance, host, scopedPlayer } = await mountSurface()
    const videoEl = host.querySelector("video")
    const initialSrc = videoEl.getAttribute("src") || videoEl.src

    routerMock.setRouting.mockClear()
    scopedPlayer.audioLayout = { layout: "DUAL_TRACK", originalTrackIndex: 0, accompanimentTrackIndex: 1 }
    scopedPlayer.vocalMode = "original"
    await nextTick()
    await nextTick()

    expect(routerMock.setRouting).toHaveBeenCalledWith(scopedPlayer.audioLayout, "original")
    const currentSrc = videoEl.getAttribute("src") || videoEl.src
    expect(currentSrc).toBe(initialSrc)

    instance.unmount()
    host.remove()
  })

  it('点击舞台手势在 WebHost 模式下自动触发 resumeDirect 并尝试解锁', async () => {
    media.playImpl = () => Promise.resolve()
    const { instance, host, scopedPlayer } = await mountSurface()
    scopedPlayer.audioBlockedByAutoplay = true
    routerMock.resumeDirect.mockClear()

    const stage = host.querySelector('.media-stage')
    stage.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    await nextTick()

    expect(routerMock.resumeDirect).toHaveBeenCalled()
    expect(scopedPlayer.audioBlockedByAutoplay).toBe(false)

    instance.unmount()
    host.remove()
  })

  it('在 isTvConsole 模式下点击手势绝不出声，坚决防止回音串音', async () => {
    media.playImpl = () => Promise.resolve()
    const { instance, host, scopedPlayer } = await mountSurface()
    scopedPlayer.tvOnline = true
    scopedPlayer.webPlayerRole = 'PASSIVE'
    scopedPlayer.audioBlockedByAutoplay = true
    expect(scopedPlayer.isTvConsole).toBe(true)
    routerMock.resumeDirect.mockClear()

    const stage = host.querySelector('.media-stage')
    stage.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    await nextTick()

    expect(routerMock.resumeDirect).not.toHaveBeenCalled()
    expect(scopedPlayer.audioBlockedByAutoplay).toBe(true)

    instance.unmount()
    host.remove()
  })

  it('普通单音轨立体声歌曲原调下直接走原生音量直通，避免被 Web Audio 劫持挂起', async () => {
    media.playImpl = () => Promise.resolve()
    const { instance, host, scopedPlayer } = await mountSurface()
    scopedPlayer.audioLayout = { layout: 'NORMAL_STEREO' }
    scopedPlayer.pitchSemitones = 0
    scopedPlayer.volume = 80
    routerMock.attach.mockClear()

    await nextTick()
    await nextTick()

    const videoEl = host.querySelector('video')
    expect(videoEl.volume).toBeCloseTo(0.8, 2)

    instance.unmount()
    host.remove()
  })
})
