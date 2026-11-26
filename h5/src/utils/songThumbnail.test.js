import { describe, expect, it } from 'vitest'
import {
  rememberFailedThumbUrl,
  resolveSongArtistAvatarUrl,
  resolveSongThumbUrl,
  songAvatarInitial,
} from './songThumbnail'

describe('songThumbnail helpers', () => {
  it('prefers coverUrl then server artistAvatarUrl and never builds legacy /api/artists/{name}/avatar', () => {
    const song = {
      id: 1,
      title: '红日',
      artist: '李克勤',
      coverUrl: '/api/cover/1',
      artistAvatarUrl: '/api/artists/avatar?key=%E6%9D%8E%E5%85%8B%E5%8B%A4',
    }
    expect(resolveSongThumbUrl(song, new Set())).toBe('/api/cover/1')

    const failedCover = rememberFailedThumbUrl(new Set(), '/api/cover/1')
    expect(resolveSongThumbUrl(song, failedCover)).toBe('/api/artists/avatar?key=%E6%9D%8E%E5%85%8B%E5%8B%A4')

    const noAvatarSong = { id: 2, title: '无头像歌', artist: '某人', coverUrl: null, artistAvatarUrl: null }
    expect(resolveSongThumbUrl(noAvatarSong, new Set())).toBe('')
    expect(resolveSongArtistAvatarUrl(noAvatarSong, new Set())).toBe('')
  })

  it('falls back to selectedArtist avatar only when provided and not failed', () => {
    const song = { id: 3, title: '海阔天空', artist: 'Beyond', coverUrl: null, artistAvatarUrl: null }
    const artist = { artistKey: 'beyond', avatarUrl: '/api/artists/avatar?key=beyond' }

    expect(resolveSongThumbUrl(song, new Set(), { selectedArtist: artist, failedArtistKeys: new Set() }))
      .toBe('/api/artists/avatar?key=beyond')
    expect(resolveSongThumbUrl(song, new Set(), { selectedArtist: artist, failedArtistKeys: new Set(['beyond']) }))
      .toBe('')
  })

  it('resolves artist initial first and falls back cleanly', () => {
    expect(songAvatarInitial({ artist: '谭咏麟', title: '一生中最爱' })).toBe('谭')
    expect(songAvatarInitial({ artist: '', title: '一路生花' })).toBe('一')
    expect(songAvatarInitial(null)).toBe('音')
  })
})
