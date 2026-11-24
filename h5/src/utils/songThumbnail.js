import { avatarUrlFor, rememberAvatarFailure } from '../views/artistAvatarState'

/**
 * Resolve the best thumbnail URL for a song card or queue/history row:
 * 1) explicit song cover (`/api/cover/{id}`)
 * 2) server-provided artist avatar (`song.artistAvatarUrl`)
 * 3) optional selected artist avatar when inside the singer detail view
 * Never synthesizes legacy non-existent `/api/artists/{name}/avatar` URLs.
 */
export function resolveSongThumbUrl(song, failedUrls, options = {}) {
  if (!song) return ''
  const coverUrl = normalizeUrl(song.coverUrl)
  if (coverUrl && !failedUrls?.has(coverUrl)) {
    return coverUrl
  }
  return resolveSongArtistAvatarUrl(song, failedUrls, options)
}

/** Resolve the artist avatar URL for a song, falling back to an explicit artist object if aligned. */
export function resolveSongArtistAvatarUrl(song, failedUrls, options = {}) {
  if (!song) return ''
  const artistAvatarUrl = normalizeUrl(song.artistAvatarUrl)
  if (artistAvatarUrl && !failedUrls?.has(artistAvatarUrl)) {
    return artistAvatarUrl
  }
  const { selectedArtist, failedArtistKeys } = options
  if (selectedArtist) {
    const candidate = avatarUrlFor(selectedArtist, failedArtistKeys)
    if (candidate && !failedUrls?.has(candidate)) {
      return candidate
    }
  }
  return ''
}

export function rememberFailedThumbUrl(failedUrls, url) {
  const clean = normalizeUrl(url)
  const next = new Set(failedUrls || [])
  if (clean) next.add(clean)
  return next
}

export function clearFailedArtistUrl(failedUrls, failedArtistKeys, artist, url) {
  const cleanUrl = normalizeUrl(url)
  if (cleanUrl && failedUrls?.has(cleanUrl)) {
    const nextUrls = new Set(failedUrls)
    nextUrls.delete(cleanUrl)
    failedUrls = nextUrls
  }
  const artistKey = artist?.artistKey || artist?.name || ''
  if (artistKey && failedArtistKeys?.has(artistKey)) {
    const nextKeys = new Set(failedArtistKeys)
    nextKeys.delete(artistKey)
    failedArtistKeys = nextKeys
  }
  return { failedUrls, failedArtistKeys }
}

export function songAvatarInitial(song) {
  const raw = (song?.artist || song?.title || '音').trim()
  return (raw || '音').slice(0, 1)
}

export function rememberArtistAvatarForFallback(failedArtistKeys, artist) {
  return rememberAvatarFailure(failedArtistKeys, artist)
}

function normalizeUrl(url) {
  return typeof url === 'string' ? url.trim() : ''
}
