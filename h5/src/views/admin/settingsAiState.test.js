import { describe, expect, it } from 'vitest'
import { isAiConfigured } from './settingsState'

describe('isAiConfigured helper', () => {
  it('returns true for keyless local AI configuration', () => {
    expect(isAiConfigured({
      enabled: true,
      baseUrl: 'http://192.168.1.5:11434/v1',
      bulkModel: 'qwen2.5',
      apiKeyConfigured: false
    })).toBe(true)
  })

  it('returns false when enabled is false or missing fields', () => {
    expect(isAiConfigured({
      enabled: false,
      baseUrl: 'https://api.deepseek.com/v1',
      bulkModel: 'deepseek-chat',
      apiKeyConfigured: true
    })).toBe(false)

    expect(isAiConfigured({
      enabled: true,
      baseUrl: '',
      bulkModel: 'deepseek-chat'
    })).toBe(false)

    expect(isAiConfigured({
      enabled: true,
      baseUrl: 'https://api.deepseek.com/v1',
      bulkModel: ''
    })).toBe(false)
  })
})
