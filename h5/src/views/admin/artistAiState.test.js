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
})
