/**
 * Search request state machine shared by the search page and its tests.
 * It owns debounce cancellation and request invalidation so a response for an
 * old query cannot restore results after the user changes or clears the input.
 */
export function createSearchController(client, {
  delayMs = 300,
  schedule = (callback, delay) => setTimeout(callback, delay),
  cancel = timer => clearTimeout(timer),
  onState = () => {}
} = {}) {
  let timer = null
  let sequence = 0
  let pending = Promise.resolve()
  let current = { results: [], loading: false, error: '' }

  function publish(patch) {
    current = { ...current, ...patch }
    onState({ ...current })
  }

  function invalidate() {
    sequence += 1
    if (timer !== null) {
      cancel(timer)
      timer = null
    }
  }

  async function request(query, filter) {
    const requestSequence = ++sequence
    try {
      const data = await client.searchSongs(query, filter, 0)
      if (requestSequence !== sequence) return
      publish({ results: data, error: '', loading: false })
    } catch (error) {
      if (requestSequence !== sequence) return
      publish({ results: [], error: error?.message || '搜索请求失败', loading: false })
    }
  }

  function setQuery(query, filter = '') {
    invalidate()
    const value = String(query ?? '').trim()
    if (!value) {
      publish({ results: [], error: '', loading: false })
      return
    }
    publish({ loading: true, error: '' })
    timer = schedule(() => {
      timer = null
      pending = request(value, filter)
    }, delayMs)
  }

  function searchNow(query, filter = '') {
    invalidate()
    const value = String(query ?? '').trim()
    if (!value) {
      publish({ results: [], error: '', loading: false })
      return Promise.resolve()
    }
    publish({ loading: true, error: '' })
    pending = request(value, filter)
    return pending
  }

  function setFilter(filter, query) {
    return searchNow(query, filter)
  }

  function retry(query, filter = '') {
    return searchNow(query, filter)
  }

  function clear() {
    invalidate()
    publish({ results: [], error: '', loading: false })
  }

  function dispose() {
    invalidate()
  }

  return {
    setQuery,
    setFilter,
    retry,
    clear,
    dispose,
    state: () => ({ ...current }),
    waitForIdle: () => pending
  }
}
