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
})

describe('metadata polling delay', () => {
  it('backs off after consecutive failures and caps the delay', () => {
    expect(pollingDelay('ready', 0)).toBe(1500)
    expect(pollingDelay('stale', 1)).toBe(3000)
    expect(pollingDelay('stale', 4)).toBe(24000)
    expect(pollingDelay('stale', 10)).toBe(30000)
  })
})
