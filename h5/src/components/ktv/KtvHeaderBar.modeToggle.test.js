// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import KtvHeaderBar from './KtvHeaderBar.vue'
import { usePlayerStore } from '../../stores/player'

describe('KtvHeaderBar 顶部常驻播控目标切换胶囊', () => {
  let app
  let root
  let player
  let pinia

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore()

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
  })

  it('电视在线时呈现【📺 正在控制电视】胶囊且点击触发 toggle-mode', async () => {
    player.tvOnline = true
    player.modeOverride = 'auto'

    let emitted = false
    app = createApp(KtvHeaderBar, {
      onToggleMode: () => { emitted = true }
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    const modePill = root.querySelector('.mode-pill')
    expect(modePill).not.toBeNull()
    expect(modePill.classList.contains('tv-console')).toBe(true)
    expect(modePill.textContent).toContain('正在控制电视')

    modePill.click()
    expect(emitted).toBe(true)
  })

  it('无电视在线且本机为主屏时呈现【💻 本机大屏播放】胶囊', async () => {
    player.tvOnline = false
    player.webPlayerRole = 'ACTIVE'
    player.modeOverride = 'auto'

    app = createApp(KtvHeaderBar)
    app.use(pinia)
    app.mount(root)
    await nextTick()

    const modePill = root.querySelector('.mode-pill')
    expect(modePill).not.toBeNull()
    expect(modePill.classList.contains('web-host')).toBe(true)
    expect(modePill.textContent).toContain('本机大屏播放')
  })
})
