import { describe, expect, it } from 'vitest'
import { avatarUrlFor, rememberAvatarFailure } from './artistAvatarState'

describe('artist avatar fallback state', () => {
  it('uses the cached avatar until it fails, then exposes the initial fallback', () => {
    const artist = { artistKey: 'zhoujielun', avatarUrl: '/api/artists/avatar?key=zhoujielun' }

    expect(avatarUrlFor(artist, new Set())).toBe(artist.avatarUrl)
    const failed = rememberAvatarFailure(new Set(), artist)
    expect(avatarUrlFor(artist, failed)).toBe('')
  })

  it('does not manufacture an image request without a cached avatar URL', () => {
    expect(avatarUrlFor({ artistKey: 'unknown', avatarUrl: null }, new Set())).toBe('')
  })
})
