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
})
