import { describe, expect, it } from 'vitest'
import { currentLyricIndex, lyricViewState } from './lyricState'

describe('lyric view state', () => {
  it('does not present a lyric load failure as a song without lyrics', () => {
    expect(lyricViewState({ id: 1 }, [], '')).toBe('empty')
    expect(lyricViewState({ id: 1 }, [], '歌词服务不可用')).toBe('error')
  })

  it('distinguishes loaded lyrics from no current song', () => {
    expect(lyricViewState({ id: 1 }, [{ time: 0, text: '歌词' }])).toBe('lyrics')
    expect(lyricViewState(null, [])).toBe('idle')
  })

  it('does not highlight a line before its timestamp', () => {
    const lines = [
      { time: 10_000, text: '第一句' },
      { time: 20_000, text: '第二句' }
    ]

    expect(currentLyricIndex(lines, 5_000)).toBe(-1)
    expect(currentLyricIndex(lines, 10_000)).toBe(0)
    expect(currentLyricIndex(lines, 25_000)).toBe(1)
    expect(currentLyricIndex([], 5_000)).toBe(-1)
  })
})
