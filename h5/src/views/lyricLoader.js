/**
 * Latest-request loader for lyrics. A new song aborts the previous fetch and
 * the sequence/current-song checks protect the view even when a fetch mock or
 * browser implementation still resolves the aborted request.
 */
export function createLyricLoader({
  fetchImpl = globalThis.fetch,
  parse = text => text,
  getCurrentSong = () => null,
  onReset = () => {},
  onSuccess = () => {},
  onError = () => {}
} = {}) {
  let sequence = 0
  let activeController = null

  function isCurrent(request) {
    return request.sequence === sequence && getCurrentSong()?.id === request.id
  }

  async function load(id) {
    const request = { id, sequence: ++sequence }
    activeController?.abort()
    const controller = new AbortController()
    activeController = controller
    onReset()

    const currentSong = getCurrentSong()
    if (!id || !currentSong || currentSong.id !== id || currentSong.lyricType === 'none') {
      return { status: 'skipped' }
    }

    try {
      const response = await fetchImpl('/api/lyric/' + id, { signal: controller.signal })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const parsed = parse(await response.text())
      if (!isCurrent(request)) return { status: 'stale' }
      onSuccess(parsed)
      return { status: 'loaded' }
    } catch (error) {
      if (error?.name === 'AbortError' || !isCurrent(request)) return { status: 'stale' }
      onError(error)
      return { status: 'error', error }
    }
  }

  function dispose() {
    sequence += 1
    activeController?.abort()
    activeController = null
  }

  return { load, dispose }
}
