import { describe, expect, it, vi } from 'vitest'
import { loadAllFavoritePages } from './favoritesState'

describe('loadAllFavoritePages', () => {
  it('collects full pages until the first short page and forwards the page size', async () => {
    const loadPage = vi.fn()
      .mockResolvedValueOnce([{ id: 1 }, { id: 2 }])
      .mockResolvedValueOnce([{ id: 3 }])

    await expect(loadAllFavoritePages(loadPage, 2)).resolves.toEqual([
      { id: 1 }, { id: 2 }, { id: 3 }
    ])
    expect(loadPage.mock.calls).toEqual([[0, 2], [1, 2]])
  })

  it('treats a non-array response as an empty terminal page', async () => {
    const loadPage = vi.fn().mockResolvedValue({ items: [{ id: 1 }] })

    await expect(loadAllFavoritePages(loadPage)).resolves.toEqual([])
    expect(loadPage).toHaveBeenCalledOnce()
    expect(loadPage).toHaveBeenCalledWith(0, 100)
  })

  it('propagates a later page failure instead of returning a partial list', async () => {
    const failure = new Error('HTTP 503')
    const loadPage = vi.fn()
      .mockResolvedValueOnce([{ id: 1 }, { id: 2 }])
      .mockRejectedValueOnce(failure)

    await expect(loadAllFavoritePages(loadPage, 2)).rejects.toBe(failure)
    expect(loadPage.mock.calls).toEqual([[0, 2], [1, 2]])
  })
})
