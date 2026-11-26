/**
 * Bounded LRU + TTL cache for H5 catalog search and browse requests.
 * Speeds up backspacing, tab switching, and pagination while avoiding stale reads.
 */
export function createSearchCache({ maxEntries = 80, ttlMs = 45_000, now = () => Date.now() } = {}) {
  const limit = Math.max(1, Number(maxEntries) || 80)
  const ttl = Math.max(0, Number(ttlMs) || 45_000)
  const store = new Map()

  function get(key) {
    if (!key || !store.has(key)) return undefined
    const entry = store.get(key)
    if (ttl > 0 && now() - entry.cachedAt >= ttl) {
      store.delete(key)
      return undefined
    }
    // Refresh LRU order
    store.delete(key)
    store.set(key, entry)
    return entry.value
  }

  function set(key, value) {
    if (!key) return value
    if (store.has(key)) store.delete(key)
    store.set(key, { value, cachedAt: now() })
    if (store.size > limit) {
      const oldestKey = store.keys().next().value
      if (oldestKey !== undefined) store.delete(oldestKey)
    }
    return value
  }

  function clear() {
    store.clear()
  }

  return { get, set, clear }
}
