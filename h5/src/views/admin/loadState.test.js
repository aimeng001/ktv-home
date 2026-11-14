import { describe, expect, it } from 'vitest'
import { pollingDelay } from './loadState'

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
