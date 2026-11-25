// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({ apiMock: {} }))
vi.mock('../../api/client', () => ({ default: apiMock }))

import KtvBottomDock from './KtvBottomDock.vue'
import { usePlayerStore } from '../../stores/player'
import { VOLUME_STEP } from './ktvControlSteps'

describe('KtvBottomDock 音量步长', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    usePlayerStore().nowPlaying = { song: { hasVocalTrack: true } }
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
  })

  it('加减音量走同一个步长常量', async () => {
    const deltas = []
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(KtvBottomDock, { onVolumeDelta: delta => deltas.push(delta) })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    root.querySelector('button[aria-label="增加音量"]').click()
    root.querySelector('button[aria-label="减小音量"]').click()
    await nextTick()

    expect(deltas).toEqual([VOLUME_STEP, -VOLUME_STEP])
    expect(VOLUME_STEP).toBe(8)
  })

  it('底部 10 个 IKTV 按钮渲染白线 PNG 图标且音效按钮触发 open-mixer', async () => {
    let mixerOpened = false
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(KtvBottomDock, { onOpenMixer: () => { mixerOpened = true } })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    const buttons = root.querySelectorAll('.dock-circle')
    expect(buttons).toHaveLength(10)
    expect(root.querySelectorAll('.dock-circle .dock-png')).toHaveLength(10)

    const labels = Array.from(buttons).map(btn => btn.querySelector('span:last-child')?.textContent?.trim())
    expect(labels).toEqual(['播放', '伴唱', '重唱', '切歌', '音量', '音量', '降调', '升调', '音效', '已点'])

    buttons[8].click()
    await nextTick()
    expect(mixerOpened).toBe(true)
  })
})
