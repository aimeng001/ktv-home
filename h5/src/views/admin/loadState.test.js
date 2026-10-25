import { describe, expect, it } from 'vitest'
import { requestState, pollingDelay } from './loadState'

describe('admin request state', () => {
  it('keeps the last successful value when a refresh fails', () => {
    const result = requestState({ status: 'rejected', reason: new Error('服务不可用') }, { total: 12 })

    expect(result).toEqual({
      status: 'error',
      value: { total: 12 },
      error: expect.any(Error)
    })
  })

  it('clears a previous error after a successful refresh', () => {
    expect(requestState({ status: 'fulfilled', value: { total: 13 } }, null)).toEqual({
      status: 'ready',
      value: { total: 13 },
      error: null
    })
  })

  it('uses the previous value or a safe fallback error for malformed failures', () => {
    const previous = { total: 5 }
    const result = requestState({ status: 'rejected' }, previous)

    expect(result.status).toBe('error')
    expect(result.value).toBe(previous)
    expect(result.error).toEqual(new Error('读取失败'))
    expect(requestState(null)).toMatchObject({ status: 'error', value: null, error: expect.any(Error) })
  })
})

describe('metadata polling delay', () => {
  it('backs off after consecutive failures and caps the delay', () => {
    expect(pollingDelay('ready', 0)).toBe(1500)
    expect(pollingDelay('stale', 1)).toBe(3000)
    expect(pollingDelay('stale', 4)).toBe(24000)
    expect(pollingDelay('stale', 10)).toBe(30000)
    expect(pollingDelay('stale', 0)).toBe(3000)
    expect(pollingDelay('stale', 'invalid')).toBe(3000)
  })
})
