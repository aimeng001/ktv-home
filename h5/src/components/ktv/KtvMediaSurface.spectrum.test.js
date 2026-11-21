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
    isSuspended: () => false,
    getFrequencyData: vi.fn(() => new Uint8Array([100, 150, 200])),
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
  buildWebStreamSpec: () => ({ url: '/api/stream/audio-1', isAudioOnly: true, liveTranscode: false })
}))
vi.mock('../../composables/useToast', async () => {
  const { ref } = await import('vue')
  return { useToast: () => ({ message: ref(''), toast: toastSpy }) }
})

import KtvMediaSurface from './KtvMediaSurface.vue'
import { usePlayerStore } from '../../stores/player'

describe('KtvMediaSurface 纯音频伴奏全屏频谱跳动与写真动效', () => {
  let app
  let root
  let player
  let pinia
  let rafSpy
  let cancelRafSpy

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
    HTMLCanvasElement.prototype.getContext = vi.fn(() => ({
      clearRect: vi.fn(),
      createLinearGradient: vi.fn(() => ({ addColorStop: vi.fn() })),
      beginPath: vi.fn(),
      roundRect: vi.fn(),
      rect: vi.fn(),
      fill: vi.fn()
    }))
  })

  beforeEach(async () => {
    rafSpy = vi.spyOn(window, 'requestAnimationFrame').mockImplementation(cb => setTimeout(cb, 16))
    cancelRafSpy = vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(id => clearTimeout(id))

    pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore()
    player.nowPlaying = {
      song: { id: 88, title: '纯音频伴奏', artist: '歌手A', coverUrl: '/api/cover/88', hasVocalTrack: true },
      orderedByNick: 'K歌达人'
    }
    player.state = 'playing'
    player.playback = { durationMs: 180000, currentPositionMs: 12000, isPlaying: true }

    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) {
      app.unmount()
      app = null
    }
    if (root && root.parentNode) {
      root.parentNode.removeChild(root)
      root = null
    }
    rafSpy.mockRestore()
    cancelRafSpy.mockRestore()
  })

  it('全屏播放纯音频歌曲时展示 1:1 动态频谱舞台与 Canvas 画布', async () => {
    app = createApp(KtvMediaSurface, { fullscreen: true })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    const spectrumStage = root.querySelector('.audio-spectrum-stage')
    expect(spectrumStage).not.toBeNull()

    const canvas = root.querySelector('.spectrum-canvas')
    expect(canvas).not.toBeNull()

    const kenBurnsBg = root.querySelector('.spectrum-bg-kenburns')
    expect(kenBurnsBg).not.toBeNull()
    expect(kenBurnsBg.style.backgroundImage).toContain('/api/cover/88')

    // 应该启动了 requestAnimationFrame
    expect(rafSpy).toHaveBeenCalled()
  })

  it('暂停播放或退出全屏时立即取消动画帧并清空画布，0 后台消耗', async () => {
    app = createApp(KtvMediaSurface, { fullscreen: true })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    expect(rafSpy).toHaveBeenCalled()

    // 模拟暂停播放
    player.state = 'paused'
    player.playback = { ...player.playback, isPlaying: false }
    await nextTick()

    expect(cancelRafSpy).toHaveBeenCalled()
  })
})
