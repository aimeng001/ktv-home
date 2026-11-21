import { describe, expect, it } from 'vitest'
import { buildSongEditPayload } from './editSongState'

describe('buildSongEditPayload', () => {
  it('trims text fields and splits tags on ASCII, Chinese, and whitespace separators', () => {
    const payload = buildSongEditPayload({
      title: '  Song title  ',
      artist: '  Singer  ',
      language: '  中文  ',
      tags: 'live, duet，classic\tnew',
      // 拼音是后端派生字段，前端不提交；故意传入以锁定「不外泄」行为。
      pinyin: 'QING TIAN'
    })
    // 用 toStrictEqual 而非 toEqual：toEqual 会忽略值为 undefined 的键，
    // 将来若有人写回 `pinyin: form.pinyin`（undefined）也照样全绿，护栏就失效了。
    expect(payload).toStrictEqual({
      title: 'Song title',
      artist: 'Singer',
      language: '中文',
      tags: ['live', 'duet', 'classic', 'new']
    })
    expect('pinyin' in payload).toBe(false)
  })

  it('uses empty text and null language for an omitted form', () => {
    // toStrictEqual：条件性字段（vocalForm）若被改成赋 undefined 也会被抓出来。
    expect(buildSongEditPayload()).toStrictEqual({
      title: '',
      artist: '',
      language: null,
      tags: []
    })
  })

  it('omits empty tags and normalizes falsey field values', () => {
    expect(buildSongEditPayload({ title: 0, artist: false, language: '', tags: ' , ，  ' })).toStrictEqual({
      title: '',
      artist: '',
      language: null,
      tags: []
    })
  })
})
