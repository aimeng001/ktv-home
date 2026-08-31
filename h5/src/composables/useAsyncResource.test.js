import { describe, expect, it, vi } from 'vitest'
import { useAsyncResource } from './useAsyncResource'

describe('useAsyncResource', () => {
  it('distinguishes a successful empty value from an error', async () => {
    const resource = useAsyncResource(vi.fn().mockResolvedValue([]), [])

    await resource.load()

    expect(resource.status.value).toBe('success')
    expect(resource.data.value).toEqual([])
    expect(resource.error.value).toBeNull()
  })

  it('preserves the last value and exposes a failed refresh', async () => {
    const loader = vi.fn()
      .mockResolvedValueOnce([{ id: 1 }])
      .mockRejectedValueOnce(new Error('网络断开'))
    const resource = useAsyncResource(loader, [])

    await resource.load()
    await resource.load()

    expect(resource.status.value).toBe('error')
    expect(resource.data.value).toEqual([{ id: 1 }])
    expect(resource.error.value.message).toBe('网络断开')
  })

  it('ignores a late response from an older refresh', async () => {
    let resolveFirst
    let resolveSecond
    const loader = vi.fn()
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve }))
    const resource = useAsyncResource(loader, [])

    const first = resource.load()
    const second = resource.load()
    resolveSecond([{ id: 2 }])
    await second
    resolveFirst([{ id: 1 }])
    await first

    expect(resource.status.value).toBe('success')
    expect(resource.data.value).toEqual([{ id: 2 }])
  })
})
