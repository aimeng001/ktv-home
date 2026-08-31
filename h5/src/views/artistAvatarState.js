/** Return a cached avatar URL only while the current artist image is usable. */
export function avatarUrlFor(artist, failedKeys) {
  if (!artist?.avatarUrl) return ''
  const key = artist.artistKey || artist.name || ''
  return failedKeys?.has(key) ? '' : artist.avatarUrl
}

/** Keep image failures local to one artist so the letter fallback can render. */
export function rememberAvatarFailure(failedKeys, artist) {
  const key = artist?.artistKey || artist?.name || ''
  const next = new Set(failedKeys || [])
  if (key) next.add(key)
  return next
}
