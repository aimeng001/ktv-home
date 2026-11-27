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

  it('deduplicates concurrent resumeDirect/resume calls and supports bounded timeout', async () => {
    let resolveResume
    const fakeCtx = {
      state: 'suspended',
      destination: {},
      createMediaElementSource: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createChannelSplitter: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createChannelMerger: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createGain: vi.fn(() => ({ gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() })),
      close: vi.fn(),
      resume: vi.fn(() => new Promise(resolve => { resolveResume = resolve })),
    }
    const router = createWebAudioChannelRouter({ AudioContextCtor: function() { return fakeCtx } })
    router.attach({ tagName: 'VIDEO' })

    const p1 = router.resumeDirect()
    const p2 = router.resume()
    expect(fakeCtx.resume).toHaveBeenCalledTimes(1)

    // Bounded timeout returns false immediately while suspended, without rejecting
    const timedOut = await router.resume(10)
    expect(timedOut).toBe(false)

    fakeCtx.state = 'running'
    resolveResume()
    await expect(p1).resolves.toBe(true)
    await expect(p2).resolves.toBe(true)
  })

  it('clears stale timed-out resume promise so subsequent user gesture invokes ctx.resume() again', async () => {
    let calls = 0
    let resolveSecond
    const fakeCtx = {
      state: 'suspended',
      destination: {},
      createMediaElementSource: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createChannelSplitter: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createChannelMerger: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createGain: vi.fn(() => ({ gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() })),
      close: vi.fn(),
      resume: vi.fn(() => {
        calls += 1
        if (calls === 1) {
          // Simulate Chromium non-gestured resume() promise that never settles.
          return new Promise(() => {})
        }
        return new Promise(resolve => { resolveSecond = resolve })
      }),
    }
    const router = createWebAudioChannelRouter({ AudioContextCtor: function() { return fakeCtx } })
    router.attach({ tagName: 'VIDEO' })

    const timedOut = await router.resume(10)
    expect(timedOut).toBe(false)
    expect(fakeCtx.resume).toHaveBeenCalledTimes(1)

    const pNext = router.resumeDirect({ force: true })
    expect(fakeCtx.resume).toHaveBeenCalledTimes(2)
    fakeCtx.state = 'running'
    resolveSecond()
    await expect(pNext).resolves.toBe(true)
    expect(router.isSuspended()).toBe(false)
  })

  it('primes AudioContext without activating graph early and reuses it on attach', () => {
    const createMediaElementSource = vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() }))
    const fakeCtx = {
      state: 'suspended',
      destination: {},
      createMediaElementSource,
      createChannelSplitter: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createChannelMerger: vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() })),
      createGain: vi.fn(() => ({ gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() })),
      close: vi.fn(),
      resume: vi.fn(() => {
        fakeCtx.state = 'running'
        return Promise.resolve()
      }),
    }
    let ctorCount = 0
    const router = createWebAudioChannelRouter({
      AudioContextCtor: function() {
        ctorCount += 1
        return fakeCtx
      },
    })

    expect(router.prime()).toBe(true)
    expect(ctorCount).toBe(1)
    expect(fakeCtx.resume).toHaveBeenCalledTimes(1)
    expect(router.isActive()).toBe(false)
    expect(createMediaElementSource).not.toHaveBeenCalled()

    expect(router.attach({ tagName: 'VIDEO' })).toBe(true)
    expect(ctorCount).toBe(1)
    expect(router.isActive()).toBe(true)
    expect(router.isSuspended()).toBe(false)
    expect(createMediaElementSource).toHaveBeenCalledTimes(1)
  })
})
