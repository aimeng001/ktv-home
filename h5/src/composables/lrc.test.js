import { describe, it, expect } from 'vitest'
import { parseLrc } from './lrc'

describe('parseLrc', () => {
  it('解析逐行 LRC', () => {
    const lines = parseLrc('[00:12.00]故事的小黄花\n[00:15.50]从出生那年就飘着')
    expect(lines).toHaveLength(2)
    expect(lines[0]).toEqual({ time: 12000, text: '故事的小黄花' })
    expect(lines[1].time).toBe(15500)
  })

  it('解析逐字增强 LRC', () => {
    const lines = parseLrc('[00:10.00]<00:10.00>晴<00:10.30>天')
    expect(lines).toHaveLength(1)
    expect(lines[0].text).toBe('晴天')
    expect(lines[0].time).toBe(10000)
    expect(lines[0].words).toEqual([
      { time: 10000, text: '晴' },
      { time: 10300, text: '天' }
    ])
  })

  it('一行多时间标签展开为多行', () => {
    const lines = parseLrc('[00:01.00][00:05.00]副歌')
    expect(lines).toHaveLength(2)
    expect(lines.map(l => l.time)).toEqual([1000, 5000])
  })

  it('忽略无时间标签行与空文本', () => {
    const lines = parseLrc('作词：某某\n[00:03.00]\n[00:04.00]有词')
    expect(lines).toHaveLength(1)
    expect(lines[0].text).toBe('有词')
  })

  it('按时间排序', () => {
    const lines = parseLrc('[00:20.00]后\n[00:05.00]先')
    expect(lines[0].text).toBe('先')
    expect(lines[1].text).toBe('后')
  })

  it('空输入返回空数组', () => {
    expect(parseLrc('')).toEqual([])
    expect(parseLrc(null)).toEqual([])
  })

  it('supports timestamps with optional and variable-width fractional seconds', () => {
    expect(parseLrc('[1:02]无小数\n[1:03.5]一位\n[1:04.050]三位').map(line => line.time))
      .toEqual([62_000, 63_500, 64_050])
  })

  it('filters empty word segments while retaining non-empty enhanced lyric words', () => {
    const [line] = parseLrc('[00:00.00]<00:00.00><00:01.00>歌词')

    expect(line.text).toBe('歌词')
    expect(line.words).toEqual([{ time: 1000, text: '歌词' }])
  })

  it('accepts enhanced word timestamps without fractional seconds', () => {
    expect(parseLrc('[00:00.00]<00:01>整秒')[0].words).toEqual([
      { time: 1000, text: '整秒' }
    ])
  })
})
