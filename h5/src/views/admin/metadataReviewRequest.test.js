import { describe, expect, it } from 'vitest'
import { createMetadataReviewRequestGate } from './metadataReviewRequest.js'

describe('metadata review request gate', () => {
  it('rejects a previous song response after close and reopen', () => {
    const gate = createMetadataReviewRequestGate()
    const requestA = gate.begin(41)

    gate.invalidate()
    const requestB = gate.begin(52)

    expect(gate.isCurrent(requestA, 52)).toBe(false)
    expect(gate.isCurrent(requestB, 52)).toBe(true)
    expect(gate.isCurrent(requestB, 41)).toBe(false)
  })

  it('rejects an older search response for the same song', () => {
    const gate = createMetadataReviewRequestGate()
    const firstSearch = gate.begin(41)
    const secondSearch = gate.begin(41)

    expect(gate.isCurrent(firstSearch, 41)).toBe(false)
    expect(gate.isCurrent(secondSearch, 41)).toBe(true)
  })

  it('invalidates a response when the review target changes', () => {
    const gate = createMetadataReviewRequestGate()
    const request = gate.begin('item:batch-1:41')

    expect(gate.isCurrent(request, 'item:batch-1:52')).toBe(false)
  })
})
