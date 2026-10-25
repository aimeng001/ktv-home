import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePlayerStore } from '../stores/player'
import { useQueuedSongIds } from './useQueuedSongIds'

describe('useQueuedSongIds', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('combines the playing song, server queue, and in-flight requests as numeric IDs', () => {
    const player = usePlayerStore()
    player.nowPlaying = { song: { id: '7' } }
    player.queue = [
      { song: { id: 7 } },
      { song: { id: '8' } },
      { song: {} },
      null
    ]

    const { orderedIds } = useQueuedSongIds(new Set(['9']))

    expect(orderedIds.value).toEqual(new Set([7, 8, 9]))
  })

  it('returns an empty set when the snapshot and in-flight list are absent', () => {
    const player = usePlayerStore()
    player.nowPlaying = null
    player.queue = null

    expect(useQueuedSongIds(null).orderedIds.value).toEqual(new Set())
  })
})
