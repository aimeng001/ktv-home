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
    const analyserNode = {
      fftSize: 128,
      frequencyBinCount: 64,
      connect: vi.fn(),
      disconnect: vi.fn(),
      getByteFrequencyData: vi.fn(arr => { arr.fill(128) }),
    }
    const fakeCtx = {
      state: 'running',
      destination: {},
      createMediaElementSource: vi.fn(() => source),
      createChannelSplitter: vi.fn(() => splitter),
      createChannelMerger: vi.fn(() => merger),
      createGain: vi.fn(() => gainNode),
      createAnalyser: vi.fn(() => analyserNode),
      close: vi.fn(),
      resume: vi.fn()
    }
    function FakeAudioContext() { return fakeCtx }

    const router = createWebAudioChannelRouter({ AudioContextCtor: FakeAudioContext })
    expect(router.attach({ tagName: 'VIDEO' })).toBe(true)
    expect(router.isActive()).toBe(true)
    expect(fakeCtx.createAnalyser).toHaveBeenCalled()

    const freqData = router.getFrequencyData()
    expect(freqData).not.toBeNull()
    expect(freqData[0]).toBe(128)

    splitter.connect.mockClear()
    router.setRouting({ layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' }, 'accompaniment')
    expect(splitter.connect).toHaveBeenNthCalledWith(1, merger, 1, 0)
    expect(splitter.connect).toHaveBeenNthCalledWith(2, merger, 1, 1)

    splitter.connect.mockClear()
    splitter.disconnect.mockClear()
    router.setRouting({ layout: 'NORMAL_STEREO' }, 'original')
    expect(splitter.connect).toHaveBeenNthCalledWith(1, merger, 0, 0)
    expect(splitter.connect).toHaveBeenNthCalledWith(2, merger, 1, 1)

    // Identical target route must be a no-op without disconnecting active audio graph
    splitter.connect.mockClear()
    splitter.disconnect.mockClear()
    router.setRouting({ layout: 'NORMAL_STEREO' }, 'accompaniment')
    expect(splitter.disconnect).not.toHaveBeenCalled()
    expect(splitter.connect).not.toHaveBeenCalled()

    router.setGain(45, false)
    expect(gainNode.gain.value).toBeCloseTo(0.45)
    router.setGain(45, true)
    expect(gainNode.gain.value).toBe(0)

    expect(router.isSuspended()).toBe(false)
    fakeCtx.state = 'suspended'
    expect(router.isSuspended()).toBe(true)

    expect(router.getPitch()).toBe(0)
    router.setPitch(3)
    expect(router.getPitch()).toBe(3)
    router.resetPitch()
    expect(router.getPitch()).toBe(3)
    router.setPitch(0)
    expect(router.getPitch()).toBe(0)

    router.dispose()
    expect(router.isActive()).toBe(false)
    expect(router.isSuspended()).toBe(false)
    expect(fakeCtx.close).toHaveBeenCalledOnce()
  })

  it("resolves channel index for DUAL_TRACK web-merged streams", () => {
    const dualTrack = { layout: "DUAL_TRACK", originalTrackIndex: 0, accompanimentTrackIndex: 1 }
    expect(resolveActiveChannelIndex(dualTrack, "original")).toBe(0)
    expect(resolveActiveChannelIndex(dualTrack, "accompaniment")).toBe(1)

    const reversedTrack = { layout: "DUAL_TRACK", originalTrackIndex: 1, accompanimentTrackIndex: 0 }
    expect(resolveActiveChannelIndex(reversedTrack, "original")).toBe(1)
    expect(resolveActiveChannelIndex(reversedTrack, "accompaniment")).toBe(0)

    const threeTrackCustom = { layout: "DUAL_TRACK", originalTrackIndex: 0, accompanimentTrackIndex: 2 }
    expect(resolveActiveChannelIndex(threeTrackCustom, "original")).toBe(0)
    expect(resolveActiveChannelIndex(threeTrackCustom, "accompaniment")).toBe(1)
  })

  it('triggers synchronous resume via resumeDirect without unhandled promise rejections', () => {
    const fakeCtx = {
      state: 'suspended',
      destination: {},
      createMediaElementSource: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createChannelSplitter: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createChannelMerger: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createGain: vi.fn(() => ({ gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() })),
      close: vi.fn(),
      resume: vi.fn(() => Promise.reject(new Error('pending gesture')))
    }
    const router = createWebAudioChannelRouter({ AudioContextCtor: function() { return fakeCtx } })
    router.attach({ tagName: 'VIDEO' })
    expect(router.isSuspended()).toBe(true)

    expect(() => router.resumeDirect()).not.toThrow()
    expect(fakeCtx.resume).toHaveBeenCalled()
  })
})
