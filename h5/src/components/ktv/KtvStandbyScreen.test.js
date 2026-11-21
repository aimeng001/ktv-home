// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import KtvStandbyScreen from './KtvStandbyScreen.vue'

describe('KtvStandbyScreen 沉浸式待机大屏与扫码系统', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
    vi.clearAllMocks()
  })

  it('展示待机大屏、扫码点歌二维码和曲库徽章', async () => {
    app = createApp(KtvStandbyScreen, {
      standbyLogoUrl: '/logo/custom.png'
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    const qrImg = root.querySelector('.standby-qr-img')
    expect(qrImg).not.toBeNull()
    expect(qrImg.getAttribute('src')).toContain('/api/qr')
    expect(root.textContent).toContain('扫码')
    expect(root.textContent).toContain('轻触')
  })

  it('点击待机大屏任意区域发出 wake 事件', async () => {
    const wakeSpy = vi.fn()
    app = createApp(KtvStandbyScreen, {
      onWake: wakeSpy
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    const screenEl = root.querySelector('.standby-screen')
    expect(screenEl).not.toBeNull()
    screenEl.click()
    expect(wakeSpy).toHaveBeenCalledTimes(1)
  })
})
