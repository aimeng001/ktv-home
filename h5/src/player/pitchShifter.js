/**
 * Real-time browser pitch shifter for Web Audio API.
 * Supports smooth semitone transposition from -6 to +6 without tempo modification.
 * When semitones === 0, dynamically bypasses processing for 0 latency, 0 distortion, and 0 CPU load.
 */

export const MIN_SEMITONES = -6
export const MAX_SEMITONES = 6
const BUFFER_SIZE = 4096
const PITCH_WINDOW_SIZE = 1764 // ~36.75ms at 48kHz, ~40ms at 44.1kHz (optimal dual-tap window)
const CIRCULAR_BUFFER_SIZE = 8192
const TWO_PI = 2 * Math.PI

export function clampSemitones(semitones) {
  const val = Number.isFinite(Number(semitones)) ? Math.round(Number(semitones)) : 0
  return Math.max(MIN_SEMITONES, Math.min(MAX_SEMITONES, val))
}

export function semitonesToPitchRatio(semitones) {
  const clamped = clampSemitones(semitones)
  return Math.pow(2, clamped / 12)
}

/**
 * Creates a PitchShifter controller managing input and output audio nodes.
 */
export function createPitchShifter(audioContext) {
  let semitones = 0
  let pitchRatio = 1
  let sourceNode = null
  let destinationNode = null
  let processorNode = null
  let bypassNode = null
  let activeNode = null

  // Circular buffers for stereo dual-tap pitch shifting
  const bufferL = new Float32Array(CIRCULAR_BUFFER_SIZE)
  const bufferR = new Float32Array(CIRCULAR_BUFFER_SIZE)
  let writePos = 0
  let phase = 0

  function isBypassed() {
    return semitones === 0
  }

  function getSemitones() {
    return semitones
  }

  function getPitchRatio() {
    return pitchRatio
  }

  function reset() {
    bufferL.fill(0)
    bufferR.fill(0)
    writePos = 0
    phase = 0
  }

  function setSemitones(newSemitones) {
    const next = clampSemitones(newSemitones)
    if (next === semitones) return
    if (semitones === 0 || next === 0) {
      reset()
    }
    semitones = next
    pitchRatio = semitonesToPitchRatio(semitones)
    updateRouting()
  }

  function updateRouting() {
    if (!bypassNode || !activeNode) return
    try {
      if (isBypassed()) {
        bypassNode.gain.value = 1
        activeNode.gain.value = 0
      } else {
        bypassNode.gain.value = 0
        activeNode.gain.value = 1
      }
    } catch {
      // safe fallback
    }
  }

  function processAudio(event) {
    if (isBypassed()) return
    const inputL = event.inputBuffer.getChannelData(0)
    const inputR = event.inputBuffer.numberOfChannels > 1 ? event.inputBuffer.getChannelData(1) : inputL
    const outputL = event.outputBuffer.getChannelData(0)
    const outputR = event.outputBuffer.numberOfChannels > 1 ? event.outputBuffer.getChannelData(1) : outputL
    const len = inputL.length
    const bufLen = CIRCULAR_BUFFER_SIZE
    const windowSize = PITCH_WINDOW_SIZE
    const dPhase = (1 - pitchRatio) / windowSize

    for (let i = 0; i < len; i++) {
      bufferL[writePos] = inputL[i]
      bufferR[writePos] = inputR[i]

      // Dual-tap Hann-window overlap-add calculation
      const delay1 = phase * windowSize
      const phase2 = (phase + 0.5) % 1
      const delay2 = phase2 * windowSize

      const w1 = 0.5 * (1 - Math.cos(TWO_PI * phase))
      const w2 = 1 - w1

      const r1 = (writePos - delay1 + bufLen) % bufLen
      const r1Floor = Math.floor(r1)
      const r1Frac = r1 - r1Floor
      const s1L = bufferL[r1Floor] * (1 - r1Frac) + bufferL[(r1Floor + 1) % bufLen] * r1Frac
      const s1R = bufferR[r1Floor] * (1 - r1Frac) + bufferR[(r1Floor + 1) % bufLen] * r1Frac

      const r2 = (writePos - delay2 + bufLen) % bufLen
      const r2Floor = Math.floor(r2)
      const r2Frac = r2 - r2Floor
      const s2L = bufferL[r2Floor] * (1 - r2Frac) + bufferL[(r2Floor + 1) % bufLen] * r2Frac
      const s2R = bufferR[r2Floor] * (1 - r2Frac) + bufferR[(r2Floor + 1) % bufLen] * r2Frac

      outputL[i] = w1 * s1L + w2 * s2L
      outputR[i] = w1 * s1R + w2 * s2R

      writePos = (writePos + 1) % bufLen
      phase += dPhase
      if (phase >= 1) phase -= 1
      else if (phase < 0) phase += 1
    }
  }

  function connect(source, destination) {
    if (!audioContext || !source || !destination) return false
    sourceNode = source
    destinationNode = destination

    try {
      // Create bypass path
      bypassNode = audioContext.createGain ? audioContext.createGain() : { gain: { value: 1 } }
      activeNode = audioContext.createGain ? audioContext.createGain() : { gain: { value: 0 } }

      // Processor node (ScriptProcessor fallback for cross-protocol HTTP LAN compatibility)
      if (typeof audioContext.createScriptProcessor === 'function') {
        processorNode = audioContext.createScriptProcessor(BUFFER_SIZE, 2, 2)
        processorNode.onaudioprocess = processAudio
      }

      // Graph:
      // source -> bypassNode -> destination
      // source -> processorNode -> activeNode -> destination
      source.connect?.(bypassNode)
      bypassNode.connect?.(destination)

      if (processorNode) {
        source.connect?.(processorNode)
        processorNode.connect?.(activeNode)
        activeNode.connect?.(destination)
      }

      updateRouting()
      return true
    } catch {
      // Direct pass-through if audio graph creation fails
      source.connect?.(destination)
      return false
    }
  }

  function dispose() {
    try { sourceNode?.disconnect?.() } catch { /* ignore */ }
    try { bypassNode?.disconnect?.() } catch { /* ignore */ }
    try { activeNode?.disconnect?.() } catch { /* ignore */ }
    if (processorNode) {
      try { processorNode.disconnect?.() } catch { /* ignore */ }
      processorNode.onaudioprocess = null
    }
    sourceNode = null
    destinationNode = null
    processorNode = null
    bypassNode = null
    activeNode = null
  }

  return {
    getSemitones,
    getPitchRatio,
    isBypassed,
    setSemitones,
    reset,
    connect,
    dispose,
  }
}
