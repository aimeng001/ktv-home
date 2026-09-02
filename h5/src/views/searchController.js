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
  let current = { results: [], loading: false, loadingMore: false, hasMore: false, page: 0, error: '' }

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
      const list = Array.isArray(data) ? data : (data?.content || [])
      publish({
        results: list,
        page: 0,
        hasMore: list.length >= 50,
        error: '',
        loading: false,
        loadingMore: false
      })
    } catch (error) {
      if (requestSequence !== sequence) return
      publish({
        results: [],
        page: 0,
        hasMore: false,
        error: error?.message || '搜索请求失败',
        loading: false,
        loadingMore: false
      })
    }
  }

  async function loadMore(query, filter = '') {
    if (current.loading || current.loadingMore || !current.hasMore) return
    const value = String(query ?? '').trim()
    if (!value) return
    const requestSequence = sequence
    const nextPage = current.page + 1
    publish({ loadingMore: true, error: '' })
    try {
      const data = await client.searchSongs(value, filter, nextPage)
      if (requestSequence !== sequence) return
      const list = Array.isArray(data) ? data : (data?.content || [])
      publish({
        results: [...current.results, ...list],
        page: nextPage,
        hasMore: list.length >= 50,
        loadingMore: false
      })
    } catch (error) {
      if (requestSequence !== sequence) return
      publish({
        loadingMore: false,
        error: error?.message || '加载更多失败'
      })
    }
  }

  function setQuery(query, filter = '') {
    invalidate()
    const value = String(query ?? '').trim()
    if (!value) {
      publish({ results: [], page: 0, hasMore: false, error: '', loading: false, loadingMore: false })
      return
    }
    publish({ loading: true, error: '', hasMore: false })
    timer = schedule(() => {
      timer = null
      pending = request(value, filter)
    }, delayMs)
  }

  function searchNow(query, filter = '') {
    invalidate()
    const value = String(query ?? '').trim()
    if (!value) {
      publish({ results: [], page: 0, hasMore: false, error: '', loading: false, loadingMore: false })
      return Promise.resolve()
    }
    publish({ loading: true, error: '', hasMore: false })
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
    publish({ results: [], page: 0, hasMore: false, error: '', loading: false, loadingMore: false })
  }

  function dispose() {
    invalidate()
  }

  return {
    setQuery,
    setFilter,
    loadMore,
    retry,
    clear,
    dispose,
    state: () => ({ ...current }),
    waitForIdle: () => pending
  }
}
