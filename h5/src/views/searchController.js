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
  let activeController = null
  let pending = Promise.resolve()
  let current = { results: [], loading: false, loadingMore: false, hasMore: false, page: 0, error: '' }

  function publish(patch) {
    current = { ...current, ...patch }
    onState({ ...current })
  }

  function track(promise) {
    const tracked = Promise.resolve(promise).finally(() => {
      if (pending === tracked) {
        pending = Promise.resolve()
      }
    })
    pending = tracked
    return tracked
  }

  function abortActive() {
    if (activeController) {
      activeController.abort()
      activeController = null
    }
  }

  function invalidate() {
    sequence += 1
    if (timer !== null) {
      cancel(timer)
      timer = null
    }
    abortActive()
  }

  async function request(query, filter) {
    const requestSequence = ++sequence
    abortActive()
    const controller = new AbortController()
    activeController = controller

    try {
      const data = await client.searchSongs(query, filter, 0, { signal: controller.signal })
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
      if (error?.name === 'AbortError') return
      if (requestSequence !== sequence) return
      publish({
        results: [],
        page: 0,
        hasMore: false,
        error: error?.message || '搜索请求失败',
        loading: false,
        loadingMore: false
      })
    } finally {
      if (activeController === controller) {
        activeController = null
      }
    }
  }

  function loadMore(query, filter = '') {
    if (current.loading || current.loadingMore || !current.hasMore) return
    const value = String(query ?? '').trim()
    if (!value) return
    return track((async () => {
      const requestSequence = sequence
      const nextPage = current.page + 1
      abortActive()
      const controller = new AbortController()
      activeController = controller

      publish({ loadingMore: true, error: '' })
      try {
        const data = await client.searchSongs(value, filter, nextPage, { signal: controller.signal })
        if (requestSequence !== sequence) return
        const list = Array.isArray(data) ? data : (data?.content || [])
        publish({
          results: [...current.results, ...list],
          page: nextPage,
          hasMore: list.length >= 50,
          loadingMore: false
        })
      } catch (error) {
        if (error?.name === 'AbortError') return
        if (requestSequence !== sequence) return
        publish({
          loadingMore: false,
          error: error?.message || '加载更多失败'
        })
      } finally {
        if (activeController === controller) {
          activeController = null
        }
      }
    })())
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
      track(request(value, filter))
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
    return track(request(value, filter))
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
