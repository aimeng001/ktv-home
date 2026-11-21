// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { createStandbyManager } from './useStandbyState'

describe('createStandbyManager 待机大屏状态协调器', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('初始状态为非待机，当无播放且超过 30 秒无操作时激活待机', () => {
    const isPlayingSong = ref(false)
    const manager = createStandbyManager({
      getIsPlayingSong: () => isPlayingSong.value,
      timeoutMs: 30000
    })

    expect(manager.isStandby.value).toBe(false)

    // 前进 29 秒，仍未进入待机
    vi.advanceTimersByTime(29000)
    expect(manager.isStandby.value).toBe(false)

    // 前进到 30 秒，激活待机
    vi.advanceTimersByTime(1100)
    expect(manager.isStandby.value).toBe(true)

    manager.dispose()
  })

  it('正在播放歌曲时绝不进入待机', () => {
    const isPlayingSong = ref(true)
    const manager = createStandbyManager({
      getIsPlayingSong: () => isPlayingSong.value,
      timeoutMs: 30000
    })

    vi.advanceTimersByTime(60000)
    expect(manager.isStandby.value).toBe(false)

    manager.dispose()
  })

  it('进入待机后，任意用户交互（点击/按键）立即唤醒并退出待机', () => {
    const isPlayingSong = ref(false)
    const manager = createStandbyManager({
      getIsPlayingSong: () => isPlayingSong.value,
      timeoutMs: 30000
    })

    vi.advanceTimersByTime(31000)
    expect(manager.isStandby.value).toBe(true)

    // 唤醒交互
    manager.wake()
    expect(manager.isStandby.value).toBe(false)

    manager.dispose()
  })

  it('待机中一旦歌曲开始播放，瞬间自动退出待机', () => {
    const isPlayingSong = ref(false)
    const manager = createStandbyManager({
      getIsPlayingSong: () => isPlayingSong.value,
      timeoutMs: 30000
    })

    vi.advanceTimersByTime(31000)
    expect(manager.isStandby.value).toBe(true)

    // 歌曲开唱
    isPlayingSong.value = true
    manager.onSongChange(true)
    expect(manager.isStandby.value).toBe(false)

    manager.dispose()
  })
})
