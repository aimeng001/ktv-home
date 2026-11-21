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
})
