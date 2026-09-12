import { describe, expect, it, vi } from 'vitest'
import { createSearchController } from './searchController'

function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
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
