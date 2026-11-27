// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import KtvEffectOverlay from './KtvEffectOverlay.vue'
import api from '../../api/client'
import { usePlayerStore } from '../../stores/player'

vi.mock('../../api/client', () => ({
  default: {
    control: vi.fn().mockResolvedValue({ ok: true }),
  },
  makeControls: vi.fn().mockReturnValue({}),
}))

describe('KtvEffectOverlay 现场气氛互动组件', () => {
  let app
  let root
  let pinia
  let player

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore()
    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root?.parentNode) root.parentNode.removeChild(root)
    vi.clearAllMocks()
  })

  it('触发气氛效果时生成粒子节点与气氛横幅', async () => {
    let overlayRef
    const Host = {
      components: { KtvEffectOverlay },
      template: '<KtvEffectOverlay ref="overlay" />',
      mounted() {
        overlayRef = this.$refs.overlay
      }
    }
    app = createApp(Host)
    app.use(pinia)
    app.mount(root)
    await nextTick()

    expect(overlayRef).not.toBeNull()
    overlayRef.triggerEffect('applause')
    await nextTick()

    const banner = root.querySelector('.effect-banner')
    expect(banner).not.toBeNull()
    expect(banner.textContent).toContain('掌声')

    const particles = root.querySelectorAll('.effect-particle')
    expect(particles.length).toBeGreaterThan(0)
  })

  it('当 TV 在线时，仅展示视觉横幅与粒子，不发声且不调用 api.control 避免广播风暴', async () => {
    player.tvOnline = true
    let overlayRef
    const Host = {
      components: { KtvEffectOverlay },
      template: '<KtvEffectOverlay ref="overlay" />',
      mounted() {
        overlayRef = this.$refs.overlay
      }
    }
    app = createApp(Host)
    app.use(pinia)
    app.mount(root)
    await nextTick()

    overlayRef.triggerEffect('cheer')
    await nextTick()

    expect(api.control).not.toHaveBeenCalled()
    const banner = root.querySelector('.effect-banner')
    expect(banner).not.toBeNull()
    expect(banner.textContent).toContain('欢呼')
  })

  it('WEB_HOST 模式下音效增益随房间音量等比缩放，避免低音量夜唱爆音', async () => {
    const gains = []
    const originalAudioContext = window.AudioContext
    class FakeAudioContext {
      constructor() {
        this.sampleRate = 48000
        this.currentTime = 0
        this.state = 'running'
        this.destination = {}
      }
      resume() { return Promise.resolve() }
      close() { return Promise.resolve() }
      createBuffer(ch, len) { return { getChannelData: () => new Float32Array(len) } }
      createBufferSource() { return { buffer: null, connect: vi.fn(), start: vi.fn() } }
      createBiquadFilter() { return { type: '', frequency: { value: 0 }, connect: vi.fn() } }
      createOscillator() { return { type: '', frequency: { value: 0, setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() }, connect: vi.fn(), start: vi.fn(), stop: vi.fn() } }
      createGain() {
        return {
          gain: {
            setValueAtTime: (val) => gains.push(val),
            exponentialRampToValueAtTime: vi.fn(),
          },
          connect: vi.fn(),
        }
      }
    }
    window.AudioContext = FakeAudioContext
    try {
      player.tvOnline = false
      player.webPlayerRole = 'ACTIVE'
      player.muted = false
      player.volume = 20
      let overlayRef
      const Host = {
        components: { KtvEffectOverlay },
        template: '<KtvEffectOverlay ref="overlay" />',
        mounted() {
          overlayRef = this.$refs.overlay
        }
      }
      app = createApp(Host)
      app.use(pinia)
      app.mount(root)
      await nextTick()

      overlayRef.triggerEffect('applause')
      expect(gains).toHaveLength(1)
      expect(gains[0]).toBeCloseTo(0.25 * 0.2, 4)
    } finally {
      window.AudioContext = originalAudioContext
    }
  })

  it('互动音效 AudioContext 处于 suspended 且 resume 拒绝时不泄漏未捕获异常，用户点击后可再次唤醒', async () => {
    const resumeMock = vi.fn()
      .mockRejectedValueOnce(new Error('not allowed yet'))
      .mockResolvedValueOnce(undefined)
    const originalAudioContext = window.AudioContext
    class SuspendedAudioContext {
      constructor() {
        this.sampleRate = 48000
        this.currentTime = 0
        this.state = 'suspended'
        this.destination = {}
      }
      resume() { return resumeMock() }
      close() { return Promise.resolve() }
      createBuffer(ch, len) { return { getChannelData: () => new Float32Array(len) } }
      createBufferSource() { return { buffer: null, connect: vi.fn(), start: vi.fn() } }
      createBiquadFilter() { return { type: '', frequency: { value: 0 }, connect: vi.fn() } }
      createOscillator() { return { type: '', frequency: { value: 0, setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() }, connect: vi.fn(), start: vi.fn(), stop: vi.fn() } }
      createGain() {
        return {
          gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() },
          connect: vi.fn(),
        }
      }
    }
    window.AudioContext = SuspendedAudioContext
    try {
      player.tvOnline = false
      player.webPlayerRole = 'ACTIVE'
      player.muted = false
      let overlayRef
      const Host = {
        components: { KtvEffectOverlay },
        template: '<KtvEffectOverlay ref="overlay" />',
        mounted() {
          overlayRef = this.$refs.overlay
        }
      }
      app = createApp(Host)
      app.use(pinia)
      app.mount(root)
      await nextTick()

      overlayRef.triggerEffect('clap')
      await Promise.resolve()
      expect(resumeMock).toHaveBeenCalledTimes(1)

      window.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
      expect(resumeMock).toHaveBeenCalledTimes(2)
    } finally {
      window.AudioContext = originalAudioContext
    }
  })
})
