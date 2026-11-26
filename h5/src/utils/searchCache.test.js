import { describe, expect, it } from 'vitest'
import { createSearchCache } from './searchCache'

describe('createSearchCache', () => {
  it('stores entries, refreshes LRU order on get, and evicts oldest beyond maxEntries', () => {
    let nowMs = 1000
    const cache = createSearchCache({ maxEntries: 2, ttlMs: 10_000, now: () => nowMs })

    cache.set('k1', ['a'])
    cache.set('k2', ['b'])
    expect(cache.get('k1')).toEqual(['a']) // k1 refreshed; k2 is now oldest

    cache.set('k3', ['c'])
    expect(cache.get('k2')).toBeUndefined()
    expect(cache.get('k1')).toEqual(['a'])
    expect(cache.get('k3')).toEqual(['c'])
  })

  it('expires entries after ttlMs and supports clear()', () => {
    let nowMs = 1000
    const cache = createSearchCache({ maxEntries: 4, ttlMs: 500, now: () => nowMs })

    cache.set('q', [1, 2])
    expect(cache.get('q')).toEqual([1, 2])

    nowMs += 500
    expect(cache.get('q')).toBeUndefined()

    cache.set('q2', [3])
    cache.clear()
    expect(cache.get('q2')).toBeUndefined()
  })
})
