/**
 * Load public playlists while keeping failure state separate from an empty
 * successful response.
 */
export async function loadPlaylists(client, { items, loading, error }) {
  loading.value = true
  error.value = ''
  try {
    items.value = await client.playlists()
    return items.value
  } catch (cause) {
    items.value = []
    error.value = cause?.message || '歌单加载失败，请稍后重试'
    return null
  } finally {
    loading.value = false
  }
}
