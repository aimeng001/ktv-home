/**
 * Real-time browser pitch shifter for Web Audio API.
 * Supports smooth semitone transposition from -6 to +6 without tempo modification.
 * When semitones === 0, dynamically bypasses processing for 0 latency, 0 distortion, and 0 CPU load.
 *
 * Dual-engine strategy:
 * - Secure contexts (HTTPS / localhost): prefers inline AudioWorkletNode (128-sample quantum).
 * - Non-secure LAN HTTP or AudioWorklet failure: automatically falls back to low-buffer ScriptProcessorNode (1024).
 */

const MIN_SEMITONES = -6
const MAX_SEMITONES = 6
const SCRIPT_BUFFER_SIZE = 1024 // ~21.3ms at 48kHz (down from 4096 = 85.3ms)
const PITCH_WINDOW_SIZE = 1024 // ~21.3ms at 48kHz (dual-tap overlap-add window)
const CIRCULAR_BUFFER_SIZE = 4096
const CIRCULAR_BUFFER_MASK = CIRCULAR_BUFFER_SIZE - 1
const TWO_PI = 2 * Math.PI
const HANN_LUT_SIZE = 2048
const HANN_LUT_MASK = HANN_LUT_SIZE - 1
const HANN_LUT = new Float32Array(HANN_LUT_SIZE)
for (let i = 0; i < HANN_LUT_SIZE; i++) {
  HANN_LUT[i] = 0.5 * (1 - Math.cos((TWO_PI * i) / HANN_LUT_SIZE))
}
const WORKLET_PROCESSOR_NAME = 'ktv-pitch-shifter-processor'
const workletRegistrationCache = typeof WeakMap !== 'undefined' ? new WeakMap() : null

const WORKLET_SOURCE = `
class KtvPitchShifterProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.bufLen = ${CIRCULAR_BUFFER_SIZE};
    this.bufMask = ${CIRCULAR_BUFFER_MASK};
    this.windowSize = ${PITCH_WINDOW_SIZE};
    this.bufferL = new Float32Array(this.bufLen);
    this.bufferR = new Float32Array(this.bufLen);
    this.writePos = 0;
    this.phase = 0;
    this.pitchRatio = 1;
    this.hannSize = ${HANN_LUT_SIZE};
    this.hannMask = ${HANN_LUT_MASK};
    this.hannLut = new Float32Array(this.hannSize);
    for (let i = 0; i < this.hannSize; i++) {
      this.hannLut[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / this.hannSize));
    }
    this.port.onmessage = (event) => {
      const data = event && event.data;
      if (!data) return;
      if (data.type === 'setPitchRatio' && Number.isFinite(data.pitchRatio)) {
        this.pitchRatio = data.pitchRatio;
      } else if (data.type === 'reset') {
        this.bufferL.fill(0);
        this.bufferR.fill(0);
        this.writePos = 0;
        this.phase = 0;
      }
    };
  }
  process(inputs, outputs) {
    const input = inputs[0];
    const output = outputs[0];
    if (!output || output.length === 0) return true;
    const outL = output[0];
    const outR = output.length > 1 ? output[1] : outL;
    if (!input || input.length === 0) {
      outL.fill(0);
      if (outR !== outL) outR.fill(0);
      return true;
    }
    const inL = input[0];
    const inR = input.length > 1 ? input[1] : inL;
    const len = inL.length;
    const dPhase = (1 - this.pitchRatio) / this.windowSize;
    const bufLen = this.bufLen;
    const bufMask = this.bufMask;
    const windowSize = this.windowSize;
    const bufferL = this.bufferL;
    const bufferR = this.bufferR;
    const hannLut = this.hannLut;
    let writePos = this.writePos;
    let phase = this.phase;
    for (let i = 0; i < len; i++) {
      bufferL[writePos] = inL[i];
      bufferR[writePos] = inR[i];
      const delay1 = phase * windowSize;
      const phase2Raw = phase + 0.5;
      const phase2 = phase2Raw >= 1 ? phase2Raw - 1 : phase2Raw;
      const delay2 = phase2 * windowSize;
      const w1 = hannLut[((phase * this.hannSize) | 0) & this.hannMask];
      const w2 = 1 - w1;
      const r1Raw = writePos - delay1 + bufLen;
      const r1Floor = (r1Raw | 0) & bufMask;
      const r1Next = (r1Floor + 1) & bufMask;
      const r1Frac = r1Raw - (r1Raw | 0);
      const s1L = bufferL[r1Floor] * (1 - r1Frac) + bufferL[r1Next] * r1Frac;
      const s1R = bufferR[r1Floor] * (1 - r1Frac) + bufferR[r1Next] * r1Frac;
      const r2Raw = writePos - delay2 + bufLen;
      const r2Floor = (r2Raw | 0) & bufMask;
      const r2Next = (r2Floor + 1) & bufMask;
      const r2Frac = r2Raw - (r2Raw | 0);
      const s2L = bufferL[r2Floor] * (1 - r2Frac) + bufferL[r2Next] * r2Frac;
      const s2R = bufferR[r2Floor] * (1 - r2Frac) + bufferR[r2Next] * r2Frac;
      outL[i] = w1 * s1L + w2 * s2L;
      outR[i] = w1 * s1R + w2 * s2R;
      writePos = (writePos + 1) & bufMask;
      phase += dPhase;
      if (phase >= 1) phase -= 1;
      else if (phase < 0) phase += 1;
    }
    this.writePos = writePos;
    this.phase = phase;
    return true;
  }
}
registerProcessor('${WORKLET_PROCESSOR_NAME}', KtvPitchShifterProcessor);
`

function clampSemitones(semitones) {
  const val = Number.isFinite(Number(semitones)) ? Math.round(Number(semitones)) : 0
  return Math.max(MIN_SEMITONES, Math.min(MAX_SEMITONES, val))
}

export function semitonesToPitchRatio(semitones) {
  const clamped = clampSemitones(semitones)
  return Math.pow(2, clamped / 12)
}

function canUseAudioWorklet(audioContext) {
  return Boolean(
    audioContext
    && audioContext.audioWorklet
    && typeof audioContext.audioWorklet.addModule === 'function'
    && typeof globalThis.AudioWorkletNode === 'function'
    && typeof globalThis.Blob === 'function'
    && typeof globalThis.URL?.createObjectURL === 'function',
  )
}

function ensureWorkletModule(audioContext) {
  const cached = workletRegistrationCache?.get(audioContext)
  if (cached) return cached
  const blob = new globalThis.Blob([WORKLET_SOURCE], { type: 'application/javascript' })
  const moduleUrl = globalThis.URL.createObjectURL(blob)
  const loadPromise = Promise.resolve(audioContext.audioWorklet.addModule(moduleUrl))
    .catch(err => {
      workletRegistrationCache?.delete(audioContext)
      throw err
    })
    .finally(() => {
      try {
        globalThis.URL?.revokeObjectURL?.(moduleUrl)
      } catch {
        // ignore URL cleanup error
      }
    })
  workletRegistrationCache?.set(audioContext, loadPromise)
  return loadPromise
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
  let workletNode = null
  let bypassNode = null
  let activeNode = null
  let engine = 'bypass'
  let activationToken = 0
  let workletFailed = false
  let disposed = false

  // Circular buffers for ScriptProcessor fallback stereo dual-tap pitch shifting
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

  function getEngine() {
    return engine
  }

  function reset() {
    bufferL.fill(0)
    bufferR.fill(0)
    writePos = 0
    phase = 0
    try {
      workletNode?.port?.postMessage?.({ type: 'reset' })
    } catch {
      // ignore worklet port error during teardown
    }
  }

  function attachScriptProcessor(token) {
    if (disposed || token !== activationToken || semitones === 0) return
    if (processorNode || workletNode || !audioContext || !sourceNode || !activeNode) return
    if (typeof audioContext.createScriptProcessor !== 'function') return
    processorNode = audioContext.createScriptProcessor(SCRIPT_BUFFER_SIZE, 2, 2)
    processorNode.onaudioprocess = processAudio
    sourceNode.connect?.(processorNode)
    processorNode.connect?.(activeNode)
    engine = 'script'
    updateRouting()
  }

  function ensureProcessorAttached() {
    if (disposed || workletNode || processorNode || !audioContext || !sourceNode || !activeNode) return
    const token = ++activationToken
    if (!workletFailed && canUseAudioWorklet(audioContext)) {
      ensureWorkletModule(audioContext)
        .then(() => {
          if (disposed || token !== activationToken || semitones === 0 || !sourceNode || !activeNode) {
            return
          }
          try {
            const node = new globalThis.AudioWorkletNode(audioContext, WORKLET_PROCESSOR_NAME, {
              numberOfInputs: 1,
              numberOfOutputs: 1,
              outputChannelCount: [2],
            })
            workletNode = node
            sourceNode.connect?.(node)
            node.connect?.(activeNode)
            node.port?.postMessage?.({ type: 'setPitchRatio', pitchRatio })
            engine = 'worklet'
            updateRouting()
          } catch {
            workletFailed = true
            workletNode = null
            attachScriptProcessor(token)
          }
        })
        .catch(() => {
          workletFailed = true
          if (!disposed && token === activationToken && semitones !== 0) {
            attachScriptProcessor(token)
          }
        })
      return
    }
    attachScriptProcessor(token)
  }

  function detachProcessor() {
    activationToken += 1
    if (workletNode) {
      try { sourceNode?.disconnect?.(workletNode) } catch { /* ignore */ }
      try { workletNode.disconnect?.() } catch { /* ignore */ }
      workletNode = null
    }
    if (processorNode) {
      try { sourceNode?.disconnect?.(processorNode) } catch { /* ignore */ }
      try { processorNode.disconnect?.() } catch { /* ignore */ }
      processorNode.onaudioprocess = null
      processorNode = null
    }
    engine = 'bypass'
  }

  function setSemitones(newSemitones) {
    const next = clampSemitones(newSemitones)
    if (next === semitones) return
    if (semitones === 0 || next === 0) {
      reset()
    }
    semitones = next
    pitchRatio = semitonesToPitchRatio(semitones)
    if (next === 0) {
      detachProcessor()
    } else if (workletNode) {
      try {
        workletNode.port?.postMessage?.({ type: 'setPitchRatio', pitchRatio })
      } catch {
        // ignore worklet port failure
      }
    } else {
      ensureProcessorAttached()
    }
    updateRouting()
  }

  function updateRouting() {
    if (!bypassNode || !activeNode) return
    try {
      const hasActiveProcessor = Boolean(workletNode || processorNode)
      if (isBypassed() || !hasActiveProcessor) {
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

      // Dual-tap Hann-window overlap-add calculation using precomputed LUT & bitmasks
      const delay1 = phase * windowSize
      const phase2Raw = phase + 0.5
      const phase2 = phase2Raw >= 1 ? phase2Raw - 1 : phase2Raw
      const delay2 = phase2 * windowSize

      const w1 = HANN_LUT[((phase * HANN_LUT_SIZE) | 0) & HANN_LUT_MASK]
      const w2 = 1 - w1

      const r1Raw = writePos - delay1 + bufLen
      const r1Floor = (r1Raw | 0) & CIRCULAR_BUFFER_MASK
      const r1Next = (r1Floor + 1) & CIRCULAR_BUFFER_MASK
      const r1Frac = r1Raw - (r1Raw | 0)
      const s1L = bufferL[r1Floor] * (1 - r1Frac) + bufferL[r1Next] * r1Frac
      const s1R = bufferR[r1Floor] * (1 - r1Frac) + bufferR[r1Next] * r1Frac

      const r2Raw = writePos - delay2 + bufLen
      const r2Floor = (r2Raw | 0) & CIRCULAR_BUFFER_MASK
      const r2Next = (r2Floor + 1) & CIRCULAR_BUFFER_MASK
      const r2Frac = r2Raw - (r2Raw | 0)
      const s2L = bufferL[r2Floor] * (1 - r2Frac) + bufferL[r2Next] * r2Frac
      const s2R = bufferR[r2Floor] * (1 - r2Frac) + bufferR[r2Next] * r2Frac

      outputL[i] = w1 * s1L + w2 * s2L
      outputR[i] = w1 * s1R + w2 * s2R

      writePos = (writePos + 1) & CIRCULAR_BUFFER_MASK
      phase += dPhase
      if (phase >= 1) phase -= 1
      else if (phase < 0) phase += 1
    }
  }

  function connect(source, destination) {
    if (!audioContext || !source || !destination) return false
    sourceNode = source
    destinationNode = destination
    disposed = false

    try {
      // Create bypass path
      bypassNode = audioContext.createGain ? audioContext.createGain() : { gain: { value: 1 } }
      activeNode = audioContext.createGain ? audioContext.createGain() : { gain: { value: 0 } }

      // Graph starts in 0-overhead bypass mode; worklet/script processor mounts lazily when semitones !== 0.
      source.connect?.(bypassNode)
      bypassNode.connect?.(destination)
      activeNode.connect?.(destination)

      if (semitones !== 0) {
        ensureProcessorAttached()
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
    disposed = true
    try { sourceNode?.disconnect?.() } catch { /* ignore */ }
    try { bypassNode?.disconnect?.() } catch { /* ignore */ }
    try { activeNode?.disconnect?.() } catch { /* ignore */ }
    detachProcessor()
    sourceNode = null
    destinationNode = null
    bypassNode = null
    activeNode = null
  }

  return {
    getSemitones,
    getPitchRatio,
    getEngine,
    isBypassed,
    setSemitones,
    reset,
    connect,
    dispose,
  }
}
