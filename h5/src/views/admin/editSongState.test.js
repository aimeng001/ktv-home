import { describe, expect, it } from 'vitest'
import { buildSongEditPayload } from './editSongState'

describe('buildSongEditPayload', () => {
  it('trims text fields and splits tags on ASCII, Chinese, and whitespace separators', () => {
    expect(buildSongEditPayload({
      title: '  Song title  ',
      artist: '  Singer  ',
      language: '  中文  ',
      tags: 'live, duet，classic\tnew'
    })).toEqual({
      title: 'Song title',
      artist: 'Singer',
      language: '中文',
      tags: ['live', 'duet', 'classic', 'new']
    })
  })

  it('uses empty text and null language for an omitted form', () => {
    expect(buildSongEditPayload()).toEqual({
      title: '',
      artist: '',
      language: null,
      tags: []
    })
  })

  it('omits empty tags and normalizes falsey field values', () => {
    expect(buildSongEditPayload({ title: 0, artist: false, language: '', tags: ' , ，  ' })).toEqual({
      title: '',
      artist: '',
      language: null,
      tags: []
    })
  })
})
