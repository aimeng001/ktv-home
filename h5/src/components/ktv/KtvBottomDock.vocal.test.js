// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({ apiMock: {} }))
vi.mock('../../api/client', () => ({ default: apiMock }))

import KtvBottomDock from './KtvBottomDock.vue'
import { usePlayerStore } from '../../stores/player'

function vocalButton(root) {
  return root.querySelectorAll('.dock-left .dock-circle')[1]
}

function mountDock(pinia, onToggleVocal = () => {}) {
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp(KtvBottomDock, { onToggleVocal })
  app.use(pinia)
  app.mount(root)
  return { app, root }
}

describe('KtvBottomDock 原唱/伴唱按钮禁用语义', () => {
  let app
  let root
  let player
  let pinia

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore()
    player.nowPlaying = { song: { hasVocalTrack: false } }
    player.audioLayout = { layout: 'NORMAL_STEREO' }
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
    vi.clearAllMocks()
  })

  it('无伴唱音轨时按钮真正禁用', async () => {
    const mounted = mountDock(pinia)
    app = mounted.app
    root = mounted.root
    await nextTick()
    expect(vocalButton(root).disabled).toBe(true)
  })

  it('无伴唱音轨时按钮带 aria-disabled', async () => {
    const mounted = mountDock(pinia)
    app = mounted.app
    root = mounted.root
    await nextTick()
    expect(vocalButton(root).getAttribute('aria-disabled')).toBe('true')
  })

  it('禁用时 aria-label 说明原因', async () => {
    const mounted = mountDock(pinia)
    app = mounted.app
    root = mounted.root
    await nextTick()
    expect(vocalButton(root).getAttribute('aria-label')).toContain('无伴唱音轨')
  })

  it('禁用时 title 说明原因', async () => {
    const mounted = mountDock(pinia)
    app = mounted.app
    root = mounted.root
    await nextTick()
    expect(vocalButton(root).getAttribute('title')).toContain('无伴唱')
  })

  it('有伴唱音轨时按钮可用', async () => {
    player.nowPlaying = { song: { hasVocalTrack: true } }
    const mounted = mountDock(pinia)
    app = mounted.app
    root = mounted.root
    await nextTick()
    const btn = vocalButton(root)
    expect(btn.disabled).toBe(false)
    expect(btn.getAttribute('aria-label')).toContain('切换')
  })

  it('禁用状态下点击不触发 toggle-vocal', async () => {
    const spy = vi.fn()
    const mounted = mountDock(pinia, spy)
    app = mounted.app
    root = mounted.root
    await nextTick()
    vocalButton(root).click()
    await nextTick()
    expect(spy).not.toHaveBeenCalled()
  })
})
