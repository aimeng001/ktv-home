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

  it('uses the artist name when a stable key is missing and tolerates no failure set', () => {
    const artist = { name: '周杰伦', avatarUrl: '/cached/avatar.jpg' }

    expect(avatarUrlFor(artist)).toBe(artist.avatarUrl)
    expect(avatarUrlFor(artist, new Set(['周杰伦']))).toBe('')
  })

  it('uses an empty lookup key when both the stable key and name are missing', () => {
    expect(avatarUrlFor({ avatarUrl: '/cached/avatar.jpg' }, new Set())).toBe('/cached/avatar.jpg')
  })

  it('copies failure state and ignores records without a usable artist key', () => {
    const original = new Set(['existing'])
    const next = rememberAvatarFailure(original, { name: '林俊杰' })

    expect(next).not.toBe(original)
    expect([...original]).toEqual(['existing'])
    expect([...next]).toEqual(['existing', '林俊杰'])
    expect([...rememberAvatarFailure(undefined, null)]).toEqual([])
  })
})
