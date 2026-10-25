import { describe, expect, it, vi } from 'vitest'
import { loadPlaylists } from './playlistLoader'

function state() {
  return {
    items: { value: [] },
    loading: { value: false },
    error: { value: '' }
  }
}

describe('playlist loader', () => {
  it('keeps a failed request distinct from a successful empty playlist', async () => {
    const current = state()
    const client = { playlists: vi.fn().mockRejectedValue(new Error('HTTP 503')) }

    await loadPlaylists(client, current)

    expect(current.items.value).toEqual([])
    expect(current.error.value).toBe('HTTP 503')
    expect(current.loading.value).toBe(false)

    client.playlists.mockResolvedValue([])
    await loadPlaylists(client, current)

    expect(current.items.value).toEqual([])
    expect(current.error.value).toBe('')
    expect(current.loading.value).toBe(false)
  })

  it('publishes a successful playlist response after a retry', async () => {
    const current = state()
    const playlists = [{ id: 1, name: '家庭精选' }]
    const client = { playlists: vi.fn().mockResolvedValue(playlists) }

    await loadPlaylists(client, current)

    expect(current.items.value).toEqual(playlists)
    expect(current.error.value).toBe('')
  })

  it('uses a fallback message when the failure does not contain one', async () => {
    const current = state()
    const client = { playlists: vi.fn().mockRejectedValue({}) }

    await expect(loadPlaylists(client, current)).resolves.toBeNull()

    expect(current.items.value).toEqual([])
    expect(current.error.value).toBe('歌单加载失败，请稍后重试')
    expect(current.loading.value).toBe(false)
  })

  it('uses a fallback message when the rejected value is null', async () => {
    const current = state()
    const client = { playlists: vi.fn().mockRejectedValue(null) }

    await expect(loadPlaylists(client, current)).resolves.toBeNull()

    expect(current.items.value).toEqual([])
    expect(current.error.value).toBe('歌单加载失败，请稍后重试')
    expect(current.loading.value).toBe(false)
  })

  it('uses a fallback message when the failure message is empty', async () => {
    const current = state()
    const client = { playlists: vi.fn().mockRejectedValue(new Error('')) }

    await expect(loadPlaylists(client, current)).resolves.toBeNull()

    expect(current.items.value).toEqual([])
    expect(current.error.value).toBe('歌单加载失败，请稍后重试')
    expect(current.loading.value).toBe(false)
  })

  it('keeps the loading state active until a pending request fails', async () => {
    const current = state()
    let rejectRequest
    const pendingRequest = new Promise((resolve, reject) => { rejectRequest = reject })
    const client = { playlists: vi.fn(() => pendingRequest) }

    const result = loadPlaylists(client, current)
    expect(current.loading.value).toBe(true)
    expect(current.error.value).toBe('')

    rejectRequest(new Error('HTTP 503'))
    await expect(result).resolves.toBeNull()
    expect(current.items.value).toEqual([])
    expect(current.error.value).toBe('HTTP 503')
    expect(current.loading.value).toBe(false)
  })
})
