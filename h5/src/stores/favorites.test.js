import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../api/client', () => ({
  default: {
    favoriteIds: vi.fn(),
    addFavorite: vi.fn(),
    removeFavorite: vi.fn()
  }
}))

import api from '../api/client'
import { useFavoritesStore } from './favorites'

describe('useFavoritesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads favorite ids once per client', async () => {
    api.favoriteIds.mockResolvedValue([3, 8])
    const favorites = useFavoritesStore()

    await favorites.load('token-1')
    await favorites.load('token-1')

    expect(favorites.ids).toEqual([3, 8])
    expect(api.favoriteIds).toHaveBeenCalledTimes(1)
  })

  it('hydrate 用已有列表补齐 ids，不再发第二次全量请求', async () => {
    const favorites = useFavoritesStore()
    favorites.loadFailed = true

    favorites.hydrate([11, 12], 'token-1')

    expect(favorites.ids).toEqual([11, 12])
    expect(favorites.loadedFor).toBe('token-1')
    expect(favorites.loadFailed).toBe(false)
    expect(api.favoriteIds).not.toHaveBeenCalled()
  })

  it('load 失败时标记 loadFailed 并把错误抛给调用方', async () => {
    api.favoriteIds.mockRejectedValue(new Error('boom'))
    const favorites = useFavoritesStore()

    await expect(favorites.load('token-1')).rejects.toThrow('boom')

    expect(favorites.loadFailed).toBe(true)
    expect(favorites.loadedFor).toBe('')
  })

  it('adds and removes a favorite', async () => {
    api.addFavorite.mockResolvedValue({ favorite: true })
    api.removeFavorite.mockResolvedValue({ favorite: false })
    const favorites = useFavoritesStore()

    expect(await favorites.toggle(9, 'token-1')).toBe(true)
    expect(favorites.has(9)).toBe(true)
    expect(await favorites.toggle(9, 'token-1')).toBe(false)
    expect(favorites.has(9)).toBe(false)
  })
})
