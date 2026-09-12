import { computed } from 'vue'
import { usePlayerStore } from '../stores/player'

/**
 * Queue ownership/duplicate state is always derived from the server snapshot.
 * The optional in-flight set only keeps the button responsive while a POST is
 * pending; it is never persisted as queue truth.
 */
export function useQueuedSongIds(inflightIds) {
  const player = usePlayerStore()
  const orderedIds = computed(() => {
    const ids = new Set()
    const playingId = player.nowPlaying?.song?.id
    if (playingId != null) ids.add(Number(playingId))
    for (const item of player.queue || []) {
      const id = item?.song?.id
      if (id != null) ids.add(Number(id))
    }
    for (const id of inflightIds || []) ids.add(Number(id))
    return ids
  })
  return { orderedIds, player }
}
