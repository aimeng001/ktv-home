// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import KtvBentoDashboard from './KtvBentoDashboard.vue'
import { BENTO_TILES } from './ktvCatalogPresets'

describe('KtvBentoDashboard 3套歌手海报 3.5 秒垂直推拉轮播', () => {
  let app
  let root

  beforeEach(() => {
    vi.useFakeTimers()
    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
    app?.unmount()
    root?.remove()
    app = null
    root = null
  })

  it('每 3.5 秒同步切换歌手点歌与歌名点歌两块拼接图，并在页面隐藏时跳过切换', async () => {
    const singerCarousel = BENTO_TILES.find(t => t.area === 'singer').carousel
    const songCarousel = BENTO_TILES.find(t => t.area === 'songname').carousel

    app = createApp(KtvBentoDashboard)
    app.mount(root)
    await nextTick()

    const singerImg = () => root.querySelector('.area-singer .tile-art')
    const songImg = () => root.querySelector('.area-songname .tile-art')

    expect(singerImg()?.getAttribute('src')).toBe(singerCarousel[0])
    expect(songImg()?.getAttribute('src')).toBe(songCarousel[0])

    // 3400ms 未切换
    vi.advanceTimersByTime(3400)
    await nextTick()
    expect(singerImg()?.getAttribute('src')).toBe(singerCarousel[0])

    // 3500ms 触发第一轮切换并激活 vertical push leave/enter 过渡类
    vi.advanceTimersByTime(150)
    await nextTick()
    const singerImages = root.querySelectorAll('.area-singer .tile-art')
    const songImages = root.querySelectorAll('.area-songname .tile-art')
    expect(Array.from(singerImages).some(el => el.getAttribute('src') === singerCarousel[1])).toBe(true)
    expect(Array.from(songImages).some(el => el.getAttribute('src') === songCarousel[1])).toBe(true)
    expect(root.querySelector('.tile-photo-push-enter-active, .tile-photo-push-leave-active')).not.toBeNull()

    // 再过 3500ms 切到第 3 套图
    vi.advanceTimersByTime(3500)
    await nextTick()
    const thirdSet = root.querySelectorAll('.area-singer .tile-art')
    expect(Array.from(thirdSet).some(el => el.getAttribute('src') === singerCarousel[2])).toBe(true)
  })
})
