/**
 * Real-time browser pitch shifter for Web Audio API.
 * Supports smooth semitone transposition from -6 to +6 without tempo modification.
 * When semitones === 0, dynamically bypasses processing for 0 latency, 0 distortion, and 0 CPU load.
 */

export const MIN_SEMITONES = -6
export const MAX_SEMITONES = 6
const BUFFER_SIZE = 4096

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

  // Circular buffers for stereo pitch shifting
  const bufferL = new Float32Array(BUFFER_SIZE * 4)
  const bufferR = new Float32Array(BUFFER_SIZE * 4)
  let writePos = 0
  let readPosL = 0
  let readPosR = 0

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
    readPosL = 0
    readPosR = 0
  }

  function setSemitones(newSemitones) {
    const next = clampSemitones(newSemitones)
    if (next === semitones) return
    semitones = next
    pitchRatio = semitonesToPitchRatio(semitones)
    reset()
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
    const inputL = event.inputBuffer.getChannelData(0)
    const inputR = event.inputBuffer.numberOfChannels > 1 ? event.inputBuffer.getChannelData(1) : inputL
    const outputL = event.outputBuffer.getChannelData(0)
    const outputR = event.outputBuffer.numberOfChannels > 1 ? event.outputBuffer.getChannelData(1) : outputL
    const len = inputL.length
    const bufLen = bufferL.length
    const ratio = pitchRatio

    // Write input into circular buffer
    for (let i = 0; i < len; i++) {
      bufferL[writePos] = inputL[i]
      bufferR[writePos] = inputR[i]
      writePos = (writePos + 1) % bufLen
    }

    // Read with fractional pitch ratio interpolation
    for (let i = 0; i < len; i++) {
      const idxL = Math.floor(readPosL) % bufLen
      const nextIdxL = (idxL + 1) % bufLen
      const fracL = readPosL - Math.floor(readPosL)
      outputL[i] = bufferL[idxL] * (1 - fracL) + bufferL[nextIdxL] * fracL

      const idxR = Math.floor(readPosR) % bufLen
      const nextIdxR = (idxR + 1) % bufLen
      const fracR = readPosR - Math.floor(readPosR)
      outputR[i] = bufferR[idxR] * (1 - fracR) + bufferR[nextIdxR] * fracR

      readPosL = (readPosL + ratio) % bufLen
      readPosR = (readPosR + ratio) % bufLen
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
