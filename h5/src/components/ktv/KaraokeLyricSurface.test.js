// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import KaraokeLyricSurface from './KaraokeLyricSurface.vue'

describe('KaraokeLyricSurface 双行卡拉OK平滑扫字歌词', () => {
  let app
  let root

  beforeEach(() => {
    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root?.parentNode) root.parentNode.removeChild(root)
  })

  it('空歌词时展示提示文字', async () => {
    app = createApp(KaraokeLyricSurface, {
      lyricLines: [],
      positionMs: 0,
      isPlaying: false
    })
    app.mount(root)
    await nextTick()

    expect(root.textContent).toContain('暂无歌词，享受现场伴奏')
  })

  it('空歌词时在播放超过 3.5 秒后自动隐藏提示文字，释放字幕安全区', async () => {
    app = createApp(KaraokeLyricSurface, {
      lyricLines: [],
      positionMs: 4000,
      isPlaying: true
    })
    app.mount(root)
    await nextTick()

    expect(root.textContent).not.toContain('暂无歌词，享受现场伴奏')
  })

  it('双行交替渲染：当前行与下一预备行同时可见', async () => {
    const lines = [
      { startMs: 1000, endMs: 5000, text: '第一句歌词唱响' },
      { startMs: 5500, endMs: 9000, text: '第二句歌词预备' },
      { startMs: 9500, endMs: 14000, text: '第三句歌词等待' }
    ]

    app = createApp(KaraokeLyricSurface, {
      lyricLines: lines,
      positionMs: 2000,
      isPlaying: true
    })
    app.mount(root)
    await nextTick()

    const activeLine = root.querySelector('.lyric-line.active')
    const nextLine = root.querySelector('.lyric-line.next')

    expect(activeLine).not.toBeNull()
    expect(activeLine.textContent).toContain('第一句歌词唱响')

    expect(nextLine).not.toBeNull()
    expect(nextLine.textContent).toContain('第二句歌词预备')
  })

  it('无逐字标签的传统 LRC 歌词，按起止时间线性计算扫光百分比', async () => {
    const lines = [
      { startMs: 1000, endMs: 5000, text: '匀速平滑推进中' },
      { startMs: 5000, endMs: 9000, text: '下一句待唱' }
    ]

    // 播放到 3000ms（耗时 2000ms / 总长 4000ms = 50%）
    app = createApp(KaraokeLyricSurface, {
      lyricLines: lines,
      positionMs: 3000,
      isPlaying: true
    })
    app.mount(root)
    await nextTick()

    const sweepEl = root.querySelector('.active .sweep-fill')
    expect(sweepEl).not.toBeNull()
    const style = sweepEl.getAttribute('style') || ''
    // 进度应为 50%
    expect(style).toContain('50%')
  })

  it('前奏首句开唱前 4 秒展示 4 颗倒计时圆点并随拍子点亮', async () => {
    const lines = [
      { startMs: 5000, endMs: 9000, text: '第一句开唱' },
      { startMs: 9500, endMs: 14000, text: '第二句' }
    ]

    // 播放到 1500ms（距离 5000ms 还剩 3500ms，触发倒计时第 1 颗点）
    app = createApp(KaraokeLyricSurface, {
      lyricLines: lines,
      positionMs: 1500,
      isPlaying: true
    })
    app.mount(root)
    await nextTick()

    const dotsContainer = root.querySelector('.karaoke-countdown')
    expect(dotsContainer).not.toBeNull()
    const activeDots = root.querySelectorAll('.countdown-dot.active')
    expect(activeDots.length).toBe(1)
  })

  it('播放中 Seek 离开倒计时区间时自动隐藏倒计时', async () => {
    const lines = [
      { startMs: 5000, endMs: 9000, text: '第一句开唱' }
    ]

    // 播放到 500ms（距离 5000ms 还有 4500ms，超出 4 秒窗口）
    app = createApp(KaraokeLyricSurface, {
      lyricLines: lines,
      positionMs: 500,
      isPlaying: true
    })
    app.mount(root)
    await nextTick()

    expect(root.querySelector('.karaoke-countdown')).toBeNull()
  })
})
