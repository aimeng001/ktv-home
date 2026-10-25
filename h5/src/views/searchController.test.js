import { describe, expect, it, vi } from 'vitest'
import { createSearchController } from './searchController'
import { searchViewState } from './searchState'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((r, j) => { resolve = r; reject = j })
  return { promise, resolve, reject }
}

const runImmediately = callback => {
  callback()
  return null
}

describe('search controller', () => {
  it('uses the public song search contract with query, filter and first page', async () => {
    const client = { searchSongs: vi.fn().mockResolvedValue([{ id: 1, title: '爱' }]) }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery(' 爱 ', 'MV')
    await controller.waitForIdle()

    expect(client.searchSongs).toHaveBeenCalledWith('爱', 'MV', 0, expect.objectContaining({
      signal: expect.any(Object)
    }))
    expect(controller.state().results).toEqual([{ id: 1, title: '爱' }])
    expect(controller.state().error).toBe('')
    expect(controller.state().loading).toBe(false)
  })

  it('reads paged response envelopes and treats a missing content array as empty', async () => {
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce({ content: [{ id: 1, title: '第一页' }] })
        .mockResolvedValueOnce({})
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    await controller.setFilter('', ' 周杰伦 ')
    expect(controller.state().results).toEqual([{ id: 1, title: '第一页' }])

    await controller.retry('林俊杰')
    expect(controller.state().results).toEqual([])
    expect(controller.state().hasMore).toBe(false)
  })

  it('clears blank queries through both debounced and immediate entry points', async () => {
    const client = { searchSongs: vi.fn().mockResolvedValue([{ id: 1 }]) }
    const controller = createSearchController(client, { schedule: runImmediately })
    await controller.retry('Song')

    controller.setQuery('   ')
    expect(controller.state()).toMatchObject({ results: [], page: 0, hasMore: false, loading: false })
    controller.setQuery(null)
    await controller.setFilter('MV', null)
    expect(controller.state()).toMatchObject({ results: [], page: 0, hasMore: false, loading: false })
    expect(client.searchSongs).toHaveBeenCalledOnce()
  })

  it('cancels pending debounce timers when replacing a query or disposing the view', () => {
    const timers = []
    const cancel = vi.fn()
    const controller = createSearchController({ searchSongs: vi.fn() }, {
      schedule: vi.fn(callback => {
        const timer = { callback }
        timers.push(timer)
        return timer
      }),
      cancel,
    })

    controller.setQuery('first')
    controller.setQuery('second')
    expect(cancel).toHaveBeenCalledWith(timers[0])
    controller.dispose()
    expect(cancel).toHaveBeenCalledWith(timers[1])
  })

  it('uses setFilter and retry as immediate searches with their supplied filters', async () => {
    const client = { searchSongs: vi.fn().mockResolvedValue([{ id: 2 }]) }
    const controller = createSearchController(client)

    await controller.setFilter('MV', ' Song ')
    await controller.retry('Song', 'KTV_VIDEO')

    expect(client.searchSongs.mock.calls.map(([query, filter, page]) => [query, filter, page]))
      .toEqual([['Song', 'MV', 0], ['Song', 'KTV_VIDEO', 0]])
  })

  it('uses the fallback message when a search rejects without a message', async () => {
    const controller = createSearchController({ searchSongs: vi.fn().mockRejectedValue({}) }, { schedule: runImmediately })

    controller.setQuery('Song')
    await controller.waitForIdle()

    expect(controller.state().error).toBe('搜索请求失败')
    expect(controller.state().loading).toBe(false)
  })

  it('invalidates an in-flight request when the query is cleared', async () => {
    const pending = deferred()
    const client = { searchSongs: vi.fn().mockReturnValue(pending.promise) }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('旧歌')
    const oldRequest = controller.waitForIdle()
    controller.clear()
    pending.resolve([{ id: 7, title: '旧结果' }])
    await oldRequest

    expect(controller.state().results).toEqual([])
    expect(controller.state().error).toBe('')
    expect(controller.state().loading).toBe(false)
  })

  it('ignores a late non-abort search failure after a newer query succeeds', async () => {
    const oldRequest = deferred()
    const client = {
      searchSongs: vi.fn()
        .mockReturnValueOnce(oldRequest.promise)
        .mockResolvedValueOnce([{ id: 8, title: '新结果' }])
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('旧歌')
    const oldIdle = controller.waitForIdle()
    controller.setQuery('新歌')
    await controller.waitForIdle()
    oldRequest.reject(new Error('旧请求迟到失败'))
    await oldIdle

    expect(controller.state().results).toEqual([{ id: 8, title: '新结果' }])
    expect(controller.state().error).toBe('')
  })

  it('supports loadMore to paginate results beyond the first page', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1, title: `Song ${i + 1}` }))
    const page1 = [{ id: 51, title: 'Song 51' }]
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce(page0)
        .mockResolvedValueOnce(page1)
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('周杰伦', '')
    await controller.waitForIdle()

    expect(client.searchSongs).toHaveBeenCalledWith('周杰伦', '', 0, expect.objectContaining({
      signal: expect.any(Object)
    }))
    expect(controller.state().results.length).toBe(50)
    expect(controller.state().hasMore).toBe(true)
    expect(controller.state().page).toBe(0)

    await controller.loadMore('周杰伦', '')

    expect(client.searchSongs).toHaveBeenCalledWith('周杰伦', '', 1, expect.objectContaining({
      signal: expect.any(Object)
    }))
    expect(controller.state().results.length).toBe(51)
    expect(controller.state().hasMore).toBe(false)
    expect(controller.state().page).toBe(1)
  })

  it('retains page zero and retries the failed page without restarting search', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1, title: `Song ${i + 1}` }))
    const page1 = [{ id: 51, title: 'Song 51' }]
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce(page0)
        .mockRejectedValueOnce(new Error('分页暂时不可用'))
        .mockResolvedValueOnce(page1)
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('周杰伦')
    await controller.waitForIdle()
    await controller.loadMore('周杰伦')

    expect(controller.state().results).toEqual(page0)
    expect(controller.state().page).toBe(0)
    expect(controller.state().hasMore).toBe(true)
    expect(controller.state().loadingMore).toBe(false)
    expect(controller.state().error).toBe('分页暂时不可用')
    expect(searchViewState('周杰伦', false, controller.state().error, controller.state().results)).toBe('results')

    await controller.loadMore('周杰伦')

    expect(client.searchSongs.mock.calls.map(call => call[2])).toEqual([0, 1, 1])
    expect(controller.state().results).toHaveLength(51)
    expect(controller.state().page).toBe(1)
    expect(controller.state().error).toBe('')
  })

  it('accepts a content envelope for a later page and uses empty content when absent', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1 }))
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce(page0)
        .mockResolvedValueOnce({ content: [{ id: 51 }] })
        .mockResolvedValueOnce(page0)
        .mockResolvedValueOnce({})
    }
    const controller = createSearchController(client, { schedule: runImmediately })
    controller.setQuery('Song')
    await controller.waitForIdle()

    await controller.loadMore('Song')
    expect(controller.state().results).toHaveLength(51)
    expect(controller.state().page).toBe(1)

    // Restore a page with more data so the final loadMore call is eligible.
    controller.setQuery('Song')
    await controller.waitForIdle()
    await controller.loadMore('Song')
    expect(controller.state().results).toEqual(page0)
    expect(controller.state().page).toBe(1)
    expect(controller.state().hasMore).toBe(false)
  })

  it('skips loadMore while loading, while another page loads, without more pages, or with a blank query', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1 }))
    const firstRequest = deferred()
    const pageRequest = deferred()
    const client = { searchSongs: vi.fn().mockReturnValueOnce(firstRequest.promise).mockReturnValueOnce(pageRequest.promise) }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('Song')
    expect(controller.state().loading).toBe(true)
    expect(controller.loadMore('Song')).toBeUndefined()
    firstRequest.resolve(page0)
    await controller.waitForIdle()

    expect(controller.loadMore(null)).toBeUndefined()
    expect(controller.loadMore('   ')).toBeUndefined()
    const loadingMore = controller.loadMore('Song')
    expect(controller.state().loadingMore).toBe(true)
    expect(controller.loadMore('Song')).toBeUndefined()
    pageRequest.resolve([{ id: 51 }])
    await loadingMore
    expect(controller.state().results).toHaveLength(51)
  })

  it('ignores a stale page response after a new search starts', async () => {
    const stalePage = deferred()
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1 }))
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce(page0)
        .mockReturnValueOnce(stalePage.promise)
        .mockResolvedValueOnce([{ id: 100 }])
    }
    const controller = createSearchController(client, { schedule: runImmediately })
    controller.setQuery('old')
    await controller.waitForIdle()
    const oldPage = controller.loadMore('old')
    await Promise.resolve()
    controller.setQuery('new')
    await controller.waitForIdle()
    stalePage.resolve([{ id: 51 }])
    await oldPage

    expect(controller.state().results).toEqual([{ id: 100 }])
    expect(controller.state().page).toBe(0)
  })

  it('does not expose an aborted loadMore request as an error', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1 }))
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce(page0)
        .mockImplementationOnce((_query, _filter, _page, { signal }) => new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(Object.assign(new Error('cancelled'), { name: 'AbortError' })), { once: true })
        }))
        .mockResolvedValueOnce([{ id: 100 }])
    }
    const controller = createSearchController(client, { schedule: runImmediately })
    controller.setQuery('old')
    await controller.waitForIdle()
    const oldPage = controller.loadMore('old')
    await Promise.resolve()
    controller.setQuery('new')
    await Promise.all([oldPage, controller.waitForIdle()])

    expect(controller.state().results).toEqual([{ id: 100 }])
    expect(controller.state().error).toBe('')
  })

  it('ignores a late page failure after a newer search has replaced it', async () => {
    const stalePage = deferred()
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1 }))
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce(page0)
        .mockReturnValueOnce(stalePage.promise)
        .mockResolvedValueOnce([{ id: 100 }])
    }
    const controller = createSearchController(client, { schedule: runImmediately })
    controller.setQuery('old')
    await controller.waitForIdle()
    const oldPage = controller.loadMore('old')
    await Promise.resolve()
    controller.setQuery('new')
    await controller.waitForIdle()
    stalePage.reject(new Error('old request failed late'))
    await oldPage

    expect(controller.state().results).toEqual([{ id: 100 }])
    expect(controller.state().error).toBe('')
  })

  it('uses the fallback message when a later page rejects without a message', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1 }))
    const client = { searchSongs: vi.fn().mockResolvedValueOnce(page0).mockRejectedValueOnce({}) }
    const controller = createSearchController(client, { schedule: runImmediately })
    controller.setQuery('Song')
    await controller.waitForIdle()

    await controller.loadMore('Song')

    expect(controller.state().error).toBe('加载更多失败')
    expect(controller.state().loadingMore).toBe(false)
  })

  it('waitForIdle waits for an in-flight loadMore request', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1, title: 'Song ' + (i + 1) }))
    const page1 = deferred()
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce(page0)
        .mockReturnValueOnce(page1.promise)
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('周杰伦', '')
    await controller.waitForIdle()
    const loadMorePromise = controller.loadMore('周杰伦', '')
    await Promise.resolve()

    let idleSettled = false
    const idle = controller.waitForIdle().then(() => { idleSettled = true })
    await Promise.resolve()
    expect(idleSettled).toBe(false)

    page1.resolve([{ id: 51, title: 'Song 51' }])
    await Promise.all([loadMorePromise, idle])
    expect(controller.state().results).toHaveLength(51)
  })

  it('aborts the previous in-flight search when query changes', async () => {
    const requests = []
    const client = {
      searchSongs: vi.fn((query, type, page, options = {}) => {
        requests.push({ query, signal: options.signal })
        return new Promise(() => {})
      })
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('周')
    expect(requests).toHaveLength(1)
    expect(requests[0].signal?.aborted).toBe(false)

    controller.setQuery('周杰伦')
    expect(requests[0].signal?.aborted).toBe(true)
    expect(requests).toHaveLength(2)
  })

  it('aborts in-flight request when query is cleared', async () => {
    const requests = []
    const client = {
      searchSongs: vi.fn((query, type, page, options = {}) => {
        requests.push({ query, signal: options.signal })
        return new Promise(() => {})
      })
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('周')
    expect(requests).toHaveLength(1)
    expect(requests[0].signal?.aborted).toBe(false)

    controller.clear()
    expect(requests[0].signal?.aborted).toBe(true)
  })

  it('aborts in-flight request when the view is disposed', async () => {
    const requests = []
    const client = {
      searchSongs: vi.fn((query, type, page, options = {}) => {
        requests.push({ query, signal: options.signal })
        return new Promise(() => {})
      })
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('周')
    expect(requests).toHaveLength(1)
    controller.dispose()

    expect(requests[0].signal?.aborted).toBe(true)
  })

  it('aborts in-flight loadMore request when query changes', async () => {
    const page0 = Array.from({ length: 50 }, (_, i) => ({ id: i + 1, title: `Song ${i + 1}` }))
    const requests = []
    const client = {
      searchSongs: vi.fn()
        .mockResolvedValueOnce(page0)
        .mockImplementationOnce((query, type, page, options = {}) => {
          requests.push({ query, page, signal: options.signal })
          return new Promise(() => {})
        })
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('周杰伦', '')
    await controller.waitForIdle()

    controller.loadMore('周杰伦', '')
    expect(requests).toHaveLength(1)
    expect(requests[0].signal?.aborted).toBe(false)

    controller.setQuery('林俊杰', '')
    expect(requests[0].signal?.aborted).toBe(true)
  })

  it('does not expose AbortError as a user-facing search failure', async () => {
    const abortError = () => Object.assign(new Error('cancelled'), { name: 'AbortError' })
    const client = {
      searchSongs: vi.fn((query, type, page, options = {}) => {
        if (query === '旧歌') {
          return new Promise((resolve, reject) => {
            options.signal.addEventListener('abort', () => reject(abortError()), { once: true })
          })
        }
        return Promise.resolve([{ id: 2, title: '新歌' }])
      })
    }
    const controller = createSearchController(client, { schedule: runImmediately })

    controller.setQuery('旧歌')
    const oldRequest = controller.waitForIdle()
    controller.setQuery('新歌')
    await Promise.all([oldRequest, controller.waitForIdle()])

    expect(controller.state().error).toBe('')
    expect(controller.state().results).toEqual([{ id: 2, title: '新歌' }])
  })
})
