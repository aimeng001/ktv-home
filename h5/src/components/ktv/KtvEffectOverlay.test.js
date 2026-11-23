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
})
