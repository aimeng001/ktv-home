import { describe, expect, it, vi } from 'vitest'
import { useOrderLock } from './useOrderLock'

describe('useOrderLock', () => {
  it('allows single execution for a song and suppresses concurrent clicks', async () => {
    const { executeOrder, isOrdering } = useOrderLock()
    let resolveOrder
    const orderPromise = new Promise(resolve => {
      resolveOrder = resolve
    })

    const fn1 = vi.fn(() => orderPromise)
    const fn2 = vi.fn(() => Promise.resolve())

    const p1 = executeOrder(101, fn1)
    expect(isOrdering(101)).toBe(true)

    // Concurrent second click for same song
    const p2 = executeOrder(101, fn2)

    expect(fn1).toHaveBeenCalledTimes(1)
    expect(fn2).not.toHaveBeenCalled()

    const res2 = await p2
    expect(res2).toBe(false)

    resolveOrder()
    const res1 = await p1
    expect(res1).toBe(true)
    expect(isOrdering(101)).toBe(false)
  })

  it('allows subsequent ordering after previous operation completes', async () => {
    const { executeOrder, isOrdering } = useOrderLock()
    const fn = vi.fn(() => Promise.resolve())

    const res1 = await executeOrder(202, fn)
    expect(res1).toBe(true)
    expect(isOrdering(202)).toBe(false)

    const res2 = await executeOrder(202, fn)
    expect(res2).toBe(true)
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('releases lock even when orderFn throws an error', async () => {
    const { executeOrder, isOrdering } = useOrderLock()
    const failureFn = vi.fn(() => Promise.reject(new Error('Network failure')))

    await expect(executeOrder(303, failureFn)).rejects.toThrow('Network failure')
    expect(isOrdering(303)).toBe(false)
  })
})
