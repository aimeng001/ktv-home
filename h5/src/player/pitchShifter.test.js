// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPitchShifter, semitonesToPitchRatio } from './pitchShifter'

describe('pitchShifter 纯前端 Web Audio 变调器', () => {
  let mockContext
  let mockInputNode
  let mockOutputNode

  beforeEach(() => {
    mockInputNode = {
      connect: vi.fn(),
      disconnect: vi.fn()
    }
    mockOutputNode = {
      connect: vi.fn(),
      disconnect: vi.fn()
    }
    mockContext = {
      sampleRate: 44100,
      createGain: vi.fn(() => ({
        connect: vi.fn(),
        disconnect: vi.fn(),
        gain: { value: 1 }
      })),
      createScriptProcessor: vi.fn(() => ({
        connect: vi.fn(),
        disconnect: vi.fn(),
        onaudioprocess: null
      }))
    }
  })

  it('semitonesToPitchRatio 正确转换半音数为频率比率并做 ±6 边界截断', () => {
    expect(semitonesToPitchRatio(0)).toBe(1)
    expect(semitonesToPitchRatio(12)).toBe(semitonesToPitchRatio(6)) // 截断到 6
    expect(semitonesToPitchRatio(1)).toBeCloseTo(1.05946, 4)
    expect(semitonesToPitchRatio(-1)).toBeCloseTo(0.94387, 4)
    expect(semitonesToPitchRatio(6)).toBeCloseTo(1.41421, 4)
    expect(semitonesToPitchRatio(-6)).toBeCloseTo(0.70711, 4)
    expect(semitonesToPitchRatio(-10)).toBe(semitonesToPitchRatio(-6)) // 截断到 -6
  })

  it('初始为 0 半音（原调）时处于 bypass 直通状态', () => {
    const shifter = createPitchShifter(mockContext)
    expect(shifter.getSemitones()).toBe(0)
    expect(shifter.isBypassed()).toBe(true)
  })

  it('调用 setSemitones(2) 后脱离 bypass，更新变调参数', () => {
    const shifter = createPitchShifter(mockContext)
    shifter.setSemitones(2)
    expect(shifter.getSemitones()).toBe(2)
    expect(shifter.isBypassed()).toBe(false)
    expect(shifter.getPitchRatio()).toBeCloseTo(1.12246, 4)

    // 恢复原调
    shifter.setSemitones(0)
    expect(shifter.getSemitones()).toBe(0)
    expect(shifter.isBypassed()).toBe(true)
  })

  it('在无 AudioWorklet 环境下（HTTP 降级），使用 ScriptProcessorNode 正常构建', () => {
    const shifter = createPitchShifter(mockContext)
    shifter.connect(mockInputNode, mockOutputNode)
    expect(mockContext.createScriptProcessor).toHaveBeenCalled()
    shifter.dispose()
  })

  it('调用 reset() 清空缓冲区不报错', () => {
    const shifter = createPitchShifter(mockContext)
    expect(() => shifter.reset()).not.toThrow()
  })

  it('处于 bypass 状态时 processAudio 立即跳过计算，0 CPU 占用', () => {
    const shifter = createPitchShifter(mockContext)
    shifter.connect(mockInputNode, mockOutputNode)
    const scriptProcessor = mockContext.createScriptProcessor.mock.results[0].value
    const inputL = { length: 4096 }
    const event = {
      inputBuffer: {
        getChannelData: vi.fn(() => inputL),
        numberOfChannels: 1
      },
      outputBuffer: {
        getChannelData: vi.fn(() => ({ length: 4096 }))
      }
    }
    // 原调时调用
    scriptProcessor.onaudioprocess(event)
    expect(event.inputBuffer.getChannelData).not.toHaveBeenCalled()
  })
})
