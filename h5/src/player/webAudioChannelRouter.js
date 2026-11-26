import { createPitchShifter } from './pitchShifter'

/**
 * Zero-latency browser audio channel router backed by Web Audio API.
 * For DUAL_CHANNEL files, routes Left-only or Right-only audio cleanly to both
 * stereo output speakers without reloading or seeking the `<video>` element.
 */

export function resolveActiveChannelIndex(audioLayout, vocalMode) {
  if (!audioLayout) return null
  if (audioLayout.layout === 'DUAL_CHANNEL') {
    const ch = vocalMode === 'original'
      ? (audioLayout.originalChannel || 'LEFT')
      : (audioLayout.accompanimentChannel || 'RIGHT')
    return String(ch).toUpperCase() === 'RIGHT' ? 1 : 0
  }
  if (audioLayout.layout === 'DUAL_TRACK') {
    const wantsOriginal = vocalMode === 'original'
    const orig = audioLayout.originalTrackIndex
    const acc = audioLayout.accompanimentTrackIndex
    // 含 3 轨及以上的曲目在后端 mergeTracks 管道已重排为 L=原唱(0) / R=伴奏(1)
    if ((Number.isInteger(orig) && orig >= 2) || (Number.isInteger(acc) && acc >= 2)) {
      return wantsOriginal ? 0 : 1
    }
    const targetTrack = wantsOriginal ? orig : acc
    if (targetTrack === 0 || targetTrack === 1) {
      return targetTrack
    }
    return wantsOriginal ? 0 : 1
  }
  return null
}

const elementSourceCache = typeof WeakMap !== 'undefined' ? new WeakMap() : null

export function createWebAudioChannelRouter({ AudioContextCtor } = {}) {
  let ctx = null
  let source = null
  let splitter = null
  let merger = null
  let gainNode = null
  let analyserNode = null
  let frequencyDataArray = null
  let pitchShifter = null
  let attachedElement = null
  let lastLayout = null
  let lastMode = 'accompaniment'
  let lastVolume = 1
  let lastMuted = false
  let connectedRouteKey = null
  let pendingResumePromise = null

  function resolveCtor() {
    if (AudioContextCtor) return AudioContextCtor
    return globalThis.AudioContext || globalThis.webkitAudioContext || null
  }

  function ensureGraph(mediaElement) {
    if (!mediaElement) return false
    const Ctor = resolveCtor()
    if (!Ctor) return false
    if (attachedElement === mediaElement && ctx && splitter && merger && gainNode) {
      return true
    }
    const cached = elementSourceCache?.get(mediaElement)
    dispose()
    connectedRouteKey = null
    try {
      if (cached && cached.ctx && cached.ctx.state !== 'closed' && cached.source) {
        ctx = cached.ctx
        source = cached.source
      } else {
        ctx = new Ctor()
        source = ctx.createMediaElementSource(mediaElement)
        elementSourceCache?.set(mediaElement, { ctx, source })
      }
      splitter = ctx.createChannelSplitter(2)
      merger = ctx.createChannelMerger(2)
      gainNode = ctx.createGain()
      pitchShifter = createPitchShifter(ctx)
      source.connect(splitter)
      pitchShifter.connect(merger, gainNode)
      if (typeof ctx.createAnalyser === 'function') {
        analyserNode = ctx.createAnalyser()
        analyserNode.fftSize = 128
        frequencyDataArray = new Uint8Array(analyserNode.frequencyBinCount || 64)
        gainNode.connect(analyserNode)
        analyserNode.connect(ctx.destination)
      } else {
        gainNode.connect(ctx.destination)
      }
      attachedElement = mediaElement
      applyRouting(lastLayout, lastMode)
      applyGain(lastVolume, lastMuted)
      return true
    } catch {
      dispose()
      return false
    }
  }

  function applyRouting(audioLayout, vocalMode) {
    lastLayout = audioLayout || null
    lastMode = vocalMode || 'accompaniment'
    if (!splitter || !merger) return false
    const monoIndex = resolveActiveChannelIndex(audioLayout, vocalMode)
    const nextRouteKey = (monoIndex === 0 || monoIndex === 1) ? `mono:${monoIndex}` : 'stereo'
    if (connectedRouteKey === nextRouteKey) return true
    try {
      splitter.disconnect()
    } catch {
      // already disconnected
    }
    if (monoIndex === 0 || monoIndex === 1) {
      splitter.connect(merger, monoIndex, 0)
      splitter.connect(merger, monoIndex, 1)
    } else {
      splitter.connect(merger, 0, 0)
      splitter.connect(merger, 1, 1)
    }
    connectedRouteKey = nextRouteKey
    return true
  }

  function applyGain(volumePct, muted) {
    lastVolume = Number.isFinite(volumePct) ? Math.max(0, Math.min(100, volumePct)) : 60
    lastMuted = Boolean(muted)
    if (!gainNode) return
    gainNode.gain.value = lastMuted ? 0 : lastVolume / 100
  }

  function resumeDirect() {
    if (!ctx) return Promise.resolve(false)
    if (ctx.state !== 'suspended') return Promise.resolve(true)
    if (pendingResumePromise) return pendingResumePromise
    if (typeof ctx.resume !== 'function') return Promise.resolve(false)
    try {
      const p = ctx.resume()
      if (p && typeof p.then === 'function') {
        pendingResumePromise = p.then(
          () => ctx?.state !== 'suspended',
          () => false,
        ).finally(() => {
          pendingResumePromise = null
        })
        return pendingResumePromise
      }
      return Promise.resolve(ctx.state !== 'suspended')
    } catch {
      pendingResumePromise = null
      return Promise.resolve(false)
    }
  }

  async function resume(timeoutMs = 0) {
    const p = resumeDirect()
    if (timeoutMs > 0) {
      let timer = null
      try {
        return await Promise.race([
          p,
          new Promise(resolve => {
            timer = setTimeout(() => resolve(ctx?.state !== 'suspended'), timeoutMs)
          }),
        ])
      } finally {
        if (timer) clearTimeout(timer)
      }
    }
    return await p
  }

  function suspend() {
    if (ctx && ctx.state === 'running' && typeof ctx.suspend === 'function') {
      try {
        const p = ctx.suspend()
        if (p && typeof p.catch === 'function') {
          p.catch(() => {})
        }
      } catch {
        // ignore suspend failure during teardown
      }
    }
  }

  function dispose() {
    const ownedByCache = attachedElement && elementSourceCache?.get(attachedElement)?.ctx === ctx
    try { source?.disconnect() } catch { /* ignore */ }
    try { splitter?.disconnect() } catch { /* ignore */ }
    try { merger?.disconnect() } catch { /* ignore */ }
    try { pitchShifter?.dispose() } catch { /* ignore */ }
    try { gainNode?.disconnect() } catch { /* ignore */ }
    try { analyserNode?.disconnect() } catch { /* ignore */ }
    if (ownedByCache && attachedElement) {
      elementSourceCache.delete(attachedElement)
    }
    if (ctx && typeof ctx.close === 'function') {
      try { ctx.close() } catch { /* ignore */ }
    }
    ctx = null
    source = null
    splitter = null
    merger = null
    pitchShifter = null
    gainNode = null
    analyserNode = null
    frequencyDataArray = null
    attachedElement = null
    connectedRouteKey = null
    pendingResumePromise = null
  }

  return {
    attach(mediaElement) { return ensureGraph(mediaElement) },
    setRouting(audioLayout, vocalMode) { return applyRouting(audioLayout, vocalMode) },
    setGain(volumePct, muted) { applyGain(volumePct, muted) },
    setPitch(semitones) { pitchShifter?.setSemitones(semitones) },
    getPitch() { return pitchShifter?.getSemitones() ?? 0 },
    resetPitch() { pitchShifter?.reset() },
    getFrequencyData() {
      if (!analyserNode || !frequencyDataArray) return null
      try {
        analyserNode.getByteFrequencyData(frequencyDataArray)
        return frequencyDataArray
      } catch {
        return null
      }
    },
    getAnalyser() { return analyserNode },
    resume,
    resumeDirect,
    suspend,
    dispose,
    isActive() { return Boolean(ctx) },
    isSuspended() { return Boolean(ctx && ctx.state === 'suspended') },
  }
}
