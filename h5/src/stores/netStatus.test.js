// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useNetStatusStore } from './netStatus'

describe('netStatus store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    try { useNetStatusStore().stop() } catch { /* store already reset */ }
  })

  it('offline 事件把状态置为离线，online 事件恢复', () => {
    const net = useNetStatusStore()
    net.start()
    expect(net.online).toBe(true)

    window.dispatchEvent(new Event('offline'))
    expect(net.online).toBe(false)

    window.dispatchEvent(new Event('online'))
    expect(net.online).toBe(true)
  })

  it('start 幂等，重复调用不会重复绑定', () => {
    const net = useNetStatusStore()
    net.start()
    net.start()

    window.dispatchEvent(new Event('offline'))
    expect(net.online).toBe(false)
  })

  it('stop 之后事件不再影响状态', () => {
    const net = useNetStatusStore()
    net.start()
    net.stop()

    window.dispatchEvent(new Event('offline'))
    expect(net.online).toBe(true)
  })
})
