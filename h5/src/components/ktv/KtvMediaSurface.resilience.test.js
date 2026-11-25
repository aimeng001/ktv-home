// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock, routerMock } = vi.hoisted(() => ({
  apiMock: {},
  routerMock: {
    attach: vi.fn(),
    setRouting: vi.fn(),
    setGain: vi.fn(),
    setPitch: vi.fn(),
    resetPitch: vi.fn(),
    resume: vi.fn(() => Promise.resolve()),
    resumeDirect: vi.fn(),
    suspend: vi.fn(),
    isActive: vi.fn(() => false),
    isSuspended: () => false,
    dispose: vi.fn()
  }
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../player/webAudioChannelRouter', () => ({
  createWebAudioChannelRouter: () => routerMock
}))
vi.mock('../../player/streamSelection', () => ({
  buildWebStreamSpec: ({ playback, startSeconds = 0 } = {}) => {
    if (!playback || playback.status !== 'READY' || !playback.sourceFileId) {
      return { url: '', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: false }
    }
    const startParam = startSeconds > 0.5 ? `&start=${startSeconds.toFixed(3)}` : ''
    return {
      url: `/api/stream/${playback.sourceFileId}?transcode=true${startParam}`,
      liveTranscode: true,
      trackSwitchedByServer: false,
      isAudioOnly: false,
    }
  }
}))

import KtvMediaSurface from './KtvMediaSurface.vue'
import { usePlayerStore } from '../../stores/player'

describe('KtvMediaSurface live transcode resilience & reconnect guard', () => {
  let app
  let root
  let player
  let video
  let fakeTime = 0

  beforeAll(() => {
    Object.defineProperty(window.HTMLMediaElement.prototype, 'play', {
      configurable: true,
      writable: true,
      value: () => Promise.resolve()
    })
    Object.defineProperty(window.HTMLMediaElement.prototype, 'pause', {
      configurable: true,
      writable: true,
      value: () => {}
    })
    Object.defineProperty(window.HTMLMediaElement.prototype, 'load', {
      configurable: true,
      writable: true,
      value: () => {}
    })
  })

  beforeEach(async () => {
    vi.useFakeTimers()
    fakeTime = 0
    const pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore()
    player.connected = true
    player.tvOnline = false
    player.webPlayerActive = true
    player.webPlayerRole = 'ACTIVE'
    player.webPlayerGeneration = 3
    player.modeOverride = 'auto'
    player.state = 'playing'
    player.positionMs = 0
    player.playback = { sourceFileId: 88, kind: 'LIVE_TRANSCODE', status: 'READY', streamUrl: '/api/stream/88?transcode=true' }
    player.nowPlaying = { queueId: 10, song: { id: 88, title: 'RISING SUN', artist: '东方神起', durationMs: 319000 } }

    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(KtvMediaSurface, { fullscreen: true })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()
    video = root.querySelector('video')
    Object.defineProperty(video, 'currentTime', {
      configurable: true,
      get: () => fakeTime,
      set: (val) => { fakeTime = val }
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    if (app) app.unmount()
    if (root) root.remove()
    vi.clearAllMocks()
  })

  it('keeps live transcode stream attached when WebSocket briefly disconnects', async () => {
    expect(video.getAttribute('src')).toContain('/api/stream/88?transcode=true')

    player.wasWebHostBeforeDisconnect = true
    player.connected = false
    player.webPlayerRole = 'STANDBY'
    await nextTick()
    await nextTick()

    expect(video.getAttribute('src')).toContain('/api/stream/88?transcode=true')
  })

  it('recovers mid-stream live transcode EOF up to twice before allowing finished report', async () => {
    const finishedSpy = vi.spyOn(player, 'reportFinished').mockReturnValue(true)

    // Unexpected EOF at 45s of a 319s song -> first recovery
    fakeTime = 45
    video.dispatchEvent(new Event('ended'))
    await nextTick()
    await nextTick()
    expect(finishedSpy).not.toHaveBeenCalled()
    expect(video.getAttribute('src')).toContain('start=45.000')

    // Second unexpected EOF at +20s (=65s absolute) -> second recovery
    fakeTime = 20
    video.dispatchEvent(new Event('ended'))
    await nextTick()
    await nextTick()
    expect(finishedSpy).not.toHaveBeenCalled()
    expect(video.getAttribute('src')).toContain('start=65.000')

    // Third EOF exceeds MAX_LIVE_RECOVERY_ATTEMPTS (2) -> advances queue normally
    fakeTime = 15
    video.dispatchEvent(new Event('ended'))
    await nextTick()
    expect(finishedSpy).toHaveBeenCalledWith(10)
  })

  it('recovers stalled live transcode playback after 10s without progress', async () => {
    fakeTime = 112
    video.dispatchEvent(new Event('waiting'))
    await nextTick()

    vi.advanceTimersByTime(9999)
    await nextTick()
    expect(video.getAttribute('src')).not.toContain('start=112.000')

    vi.advanceTimersByTime(1)
    await nextTick()
    await nextTick()
    expect(video.getAttribute('src')).toContain('start=112.000')
  })

  it('prebuffers live transcode video until 1.2s buffered or 1.8s fallback before calling play', async () => {
    const playSpy = vi.spyOn(video, 'play')
    playSpy.mockClear()

    // Switch to another live transcode song; while buffered is empty, play() waits for prebuffer
    player.nowPlaying = { queueId: 11, song: { id: 89, title: '命运', artist: 'RAIN', durationMs: 255000 } }
    player.playback = { sourceFileId: 89, kind: 'LIVE_TRANSCODE', status: 'READY', streamUrl: '/api/stream/89?transcode=true' }
    await nextTick()
    await nextTick()

    expect(video.getAttribute('src')).toContain('/api/stream/89?transcode=true')
    expect(playSpy).not.toHaveBeenCalled()

    // Provide >= 1.2s buffered range and fire progress -> starts immediately
    Object.defineProperty(video, 'buffered', {
      configurable: true,
      get: () => ({
        length: 1,
        start: () => 0,
        end: () => 1.5,
      }),
    })
    video.dispatchEvent(new Event('progress'))
    await nextTick()
    expect(playSpy).toHaveBeenCalledTimes(1)

    // Subsequent song with no progress event still starts via 1.8s prebuffer timeout
    playSpy.mockClear()
    Object.defineProperty(video, 'buffered', {
      configurable: true,
      get: () => ({ length: 0, start: () => 0, end: () => 0 }),
    })
    player.nowPlaying = { queueId: 12, song: { id: 90, title: 'TIMEOUT TEST', artist: 'RAIN', durationMs: 200000 } }
    player.playback = { sourceFileId: 90, kind: 'LIVE_TRANSCODE', status: 'READY', streamUrl: '/api/stream/90?transcode=true' }
    await nextTick()
    await nextTick()
    expect(playSpy).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1800)
    await nextTick()
    expect(playSpy).toHaveBeenCalledTimes(1)
  })
})
