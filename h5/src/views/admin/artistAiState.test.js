import { describe, expect, it, vi } from 'vitest'
import { resolveAiConfiguration } from './artistAiState'

describe('artist AI configuration gate', () => {
  it('reports configuration read errors without redirecting to settings', async () => {
    const onError = vi.fn()
    const onRedirect = vi.fn()

    const result = await resolveAiConfiguration(
      () => Promise.reject(new Error('服务不可用')),
      { onError, onRedirect }
    )

    expect(result).toBeNull()
    expect(onError).toHaveBeenCalledWith('服务不可用')
    expect(onRedirect).not.toHaveBeenCalled()
  })

  it('uses a safe message when the configuration read fails without an error message', async () => {
    const onError = vi.fn()
    const onRedirect = vi.fn()

    await expect(resolveAiConfiguration(
      () => Promise.reject({}),
      { onError, onRedirect }
    )).resolves.toBeNull()

    expect(onError).toHaveBeenCalledWith('AI 配置读取失败')
    expect(onRedirect).not.toHaveBeenCalled()
  })

  it('uses a safe message when the configuration read rejects with null', async () => {
    const onError = vi.fn()
    const onRedirect = vi.fn()

    await expect(resolveAiConfiguration(
      () => Promise.reject(null),
      { onError, onRedirect }
    )).resolves.toBeNull()

    expect(onError).toHaveBeenCalledWith('AI 配置读取失败')
    expect(onRedirect).not.toHaveBeenCalled()
  })

  it('redirects only after a successful but incomplete configuration read', async () => {
    const onError = vi.fn()
    const onRedirect = vi.fn()

    await resolveAiConfiguration(
      () => Promise.resolve({ enabled: false }),
      { onError, onRedirect }
    )

    expect(onError).toHaveBeenCalledWith('批量 AI 分析需要先配置并启用 AI 模型。')
    expect(onRedirect).toHaveBeenCalledTimes(1)
  })

  it('accepts enabled keyless configuration', async () => {
    const onError = vi.fn()
    const onRedirect = vi.fn()

    const config = {
      enabled: true,
      baseUrl: 'http://192.168.1.5:11434/v1',
      bulkModel: 'qwen2.5',
      apiKeyConfigured: false
    }

    const result = await resolveAiConfiguration(
      () => Promise.resolve(config),
      { onError, onRedirect }
    )

    expect(result).toEqual(config)
    expect(onError).not.toHaveBeenCalled()
    expect(onRedirect).not.toHaveBeenCalled()
  })

  it('allows omitted optional callbacks for an incomplete configuration', async () => {
    await expect(resolveAiConfiguration(
      () => Promise.resolve({ enabled: false })
    )).resolves.toBeNull()
  })
})
