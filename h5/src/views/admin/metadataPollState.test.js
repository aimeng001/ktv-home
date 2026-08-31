import { describe, expect, it } from 'vitest'
import { pollingDelay } from './loadState'

describe('metadata polling state contract', () => {
  it('does not use the fast interval after a failed poll', () => {
    expect(pollingDelay('stale', 1)).toBeGreaterThan(pollingDelay('ready', 0))
  })
})
