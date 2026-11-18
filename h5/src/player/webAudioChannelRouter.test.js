import { describe, expect, it, vi } from 'vitest'
import { createWebAudioChannelRouter, resolveActiveChannelIndex } from './webAudioChannelRouter'

describe('webAudioChannelRouter', () => {
  it('resolves left/right channel index for DUAL_CHANNEL and preserves reversed tracks', () => {
    const standard = { layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' }
    expect(resolveActiveChannelIndex(standard, 'original')).toBe(0)
    expect(resolveActiveChannelIndex(standard, 'accompaniment')).toBe(1)

    const reversed = { layout: 'DUAL_CHANNEL', originalChannel: 'RIGHT', accompanimentChannel: 'LEFT' }
    expect(resolveActiveChannelIndex(reversed, 'original')).toBe(1)
    expect(resolveActiveChannelIndex(reversed, 'accompaniment')).toBe(0)

    expect(resolveActiveChannelIndex({ layout: 'NORMAL_STEREO' }, 'accompaniment')).toBeNull()
  })

  it('connects splitter to mono-duplicated merger channels for DUAL_CHANNEL and stereo for NORMAL_STEREO', () => {
    const splitter = { connect: vi.fn(), disconnect: vi.fn() }
    const merger = { connect: vi.fn(), disconnect: vi.fn() }
    const gainNode = { gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() }
    const source = { connect: vi.fn(), disconnect: vi.fn() }
    const fakeCtx = {
      state: 'running',
      destination: {},
      createMediaElementSource: vi.fn(() => source),
      createChannelSplitter: vi.fn(() => splitter),
      createChannelMerger: vi.fn(() => merger),
      createGain: vi.fn(() => gainNode),
      close: vi.fn(),
      resume: vi.fn()
    }
    function FakeAudioContext() { return fakeCtx }

    const router = createWebAudioChannelRouter({ AudioContextCtor: FakeAudioContext })
    expect(router.attach({ tagName: 'VIDEO' })).toBe(true)
    expect(router.isActive()).toBe(true)

    splitter.connect.mockClear()
    router.setRouting({ layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' }, 'accompaniment')
    expect(splitter.connect).toHaveBeenNthCalledWith(1, merger, 1, 0)
    expect(splitter.connect).toHaveBeenNthCalledWith(2, merger, 1, 1)

    splitter.connect.mockClear()
    router.setRouting({ layout: 'NORMAL_STEREO' }, 'original')
    expect(splitter.connect).toHaveBeenNthCalledWith(1, merger, 0, 0)
    expect(splitter.connect).toHaveBeenNthCalledWith(2, merger, 1, 1)

    router.setGain(45, false)
    expect(gainNode.gain.value).toBeCloseTo(0.45)
    router.setGain(45, true)
    expect(gainNode.gain.value).toBe(0)

    expect(router.isSuspended()).toBe(false)
    fakeCtx.state = 'suspended'
    expect(router.isSuspended()).toBe(true)

    router.dispose()
    expect(router.isActive()).toBe(false)
    expect(router.isSuspended()).toBe(false)
    expect(fakeCtx.close).toHaveBeenCalledOnce()
  })
})
