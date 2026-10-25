import { describe, expect, it } from 'vitest'
import { createLatestRequest } from './latestRequest'

describe('latest request gate', () => {
  it('aborts the previous request when a newer one begins', () => {
    const requests = createLatestRequest()
    const previous = requests.begin()
    const current = requests.begin()

    expect(previous.signal.aborted).toBe(true)
    expect(current.signal.aborted).toBe(false)
    expect(requests.isCurrent(previous.id)).toBe(false)
    expect(requests.isCurrent(current.id)).toBe(true)
  })

  it('cancels the active request and allows a later request to become current', () => {
    const requests = createLatestRequest()
    requests.cancel()
    const first = requests.begin()

    requests.cancel()
    requests.cancel()

    expect(first.signal.aborted).toBe(true)
    expect(requests.isCurrent(first.id)).toBe(false)

    const next = requests.begin()
    expect(next.id).toBeGreaterThan(first.id)
    expect(next.signal.aborted).toBe(false)
    expect(requests.isCurrent(next.id)).toBe(true)
  })
})
