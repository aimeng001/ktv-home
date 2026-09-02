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

    expect(client.searchSongs).toHaveBeenCalledWith('爱', 'MV', 0)
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

    expect(client.searchSongs).toHaveBeenCalledWith('周杰伦', '', 0)
    expect(controller.state().results.length).toBe(50)
    expect(controller.state().hasMore).toBe(true)
    expect(controller.state().page).toBe(0)

    await controller.loadMore('周杰伦', '')

    expect(client.searchSongs).toHaveBeenCalledWith('周杰伦', '', 1)
    expect(controller.state().results.length).toBe(51)
    expect(controller.state().hasMore).toBe(false)
    expect(controller.state().page).toBe(1)
  })
})
