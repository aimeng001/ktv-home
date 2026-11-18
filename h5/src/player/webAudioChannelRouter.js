/**
 * Zero-latency browser audio channel router backed by Web Audio API.
 * For DUAL_CHANNEL files, routes Left-only or Right-only audio cleanly to both
 * stereo output speakers without reloading or seeking the `<video>` element.
 */

export function resolveActiveChannelIndex(audioLayout, vocalMode) {
  if (!audioLayout || audioLayout.layout !== 'DUAL_CHANNEL') return null
  const ch = vocalMode === 'original'
    ? (audioLayout.originalChannel || 'LEFT')
    : (audioLayout.accompanimentChannel || 'RIGHT')
  return String(ch).toUpperCase() === 'RIGHT' ? 1 : 0
}

export function createWebAudioChannelRouter({ AudioContextCtor } = {}) {
  let ctx = null
  let source = null
  let splitter = null
  let merger = null
  let gainNode = null
  let attachedElement = null
  let lastLayout = null
  let lastMode = 'accompaniment'
  let lastVolume = 1
  let lastMuted = false

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
    dispose()
    try {
      ctx = new Ctor()
      source = ctx.createMediaElementSource(mediaElement)
      splitter = ctx.createChannelSplitter(2)
      merger = ctx.createChannelMerger(2)
      gainNode = ctx.createGain()
      source.connect(splitter)
      merger.connect(gainNode)
      gainNode.connect(ctx.destination)
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
    try {
      splitter.disconnect()
    } catch {
      // already disconnected
    }
    const monoIndex = resolveActiveChannelIndex(audioLayout, vocalMode)
    if (monoIndex === 0 || monoIndex === 1) {
      splitter.connect(merger, monoIndex, 0)
      splitter.connect(merger, monoIndex, 1)
    } else {
      splitter.connect(merger, 0, 0)
      splitter.connect(merger, 1, 1)
    }
    return true
  }

  function applyGain(volumePct, muted) {
    lastVolume = Number.isFinite(volumePct) ? Math.max(0, Math.min(100, volumePct)) : 60
    lastMuted = Boolean(muted)
    if (!gainNode) return
    gainNode.gain.value = lastMuted ? 0 : lastVolume / 100
  }

  async function resume() {
    if (ctx && ctx.state === 'suspended') {
      try {
        await ctx.resume()
      } catch {
        // user gesture may still be pending
      }
    }
  }

  function dispose() {
    try { source?.disconnect() } catch { /* ignore */ }
    try { splitter?.disconnect() } catch { /* ignore */ }
    try { merger?.disconnect() } catch { /* ignore */ }
    try { gainNode?.disconnect() } catch { /* ignore */ }
    if (ctx && typeof ctx.close === 'function') {
      try { ctx.close() } catch { /* ignore */ }
    }
    ctx = null
    source = null
    splitter = null
    merger = null
    gainNode = null
    attachedElement = null
  }

  return {
    attach(mediaElement) { return ensureGraph(mediaElement) },
    setRouting(audioLayout, vocalMode) { return applyRouting(audioLayout, vocalMode) },
    setGain(volumePct, muted) { applyGain(volumePct, muted) },
    resume,
    dispose,
    isActive() { return Boolean(ctx) },
    isSuspended() { return Boolean(ctx && ctx.state === 'suspended') },
  }
}
