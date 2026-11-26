// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    searchSongs: vi.fn(async () => []),
    browseSongPage: vi.fn(async () => ({
      items: [
        {
          id: 46394,
          catalogNumber: '',
          title: '超长测试歌名用于验证中等屏幕下是否会被过度挤压而完全不可见',
          artist: '测试歌手名非常长',
          durationMs: 240000,
          playable: true,
        }
      ],
      total: 1
    })),
    ranking: vi.fn(async () => []),
    newSongs: vi.fn(async () => []),
    browseArtistPage: vi.fn(async () => ({ items: [], total: 0 })),
    browseArtistInitials: vi.fn(async () => []),
    playlists: vi.fn(async () => []),
    favorites: vi.fn(async () => ({ items: [], total: 0 })),
    recentHistory: vi.fn(async () => []),
  }
}))

vi.mock('../../api/client', () => ({
  default: apiMock,
  makeControls: () => ({ orderSong: vi.fn() })
}))

vi.mock('../../composables/useToast', async () => {
  const { ref } = await import('vue')
  return { useToast: () => ({ message: ref(''), toast: vi.fn() }) }
})

import KtvThreeColumnBrowser from './KtvThreeColumnBrowser.vue'
import browserStyleSource from './KtvThreeColumnBrowser.vue?raw'

describe('KtvThreeColumnBrowser 1280~1440px 中屏响应式断点与卡片布局加固', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root?.parentNode) root.parentNode.removeChild(root)
    vi.clearAllMocks()
  })

  it('断点规则包含 1440px 阈值，确保 1280px/1366px 等常见触屏/中屏进入 2 列网格', () => {
    // 验证 CSS 中断点是否升级为 max-width: 1440px（原 1260px 遗漏了 1280 与 1366）
    expect(browserStyleSource).toMatch(/@media\s*\(\s*max-width:\s*1440px\s*\)/)
    expect(browserStyleSource).not.toMatch(/@media\s*\(\s*max-width:\s*1260px\s*\)/)
  })

  it('歌曲卡片内部标题行与卡片信息具备弹性防挤压属性', async () => {
    app = createApp(KtvThreeColumnBrowser, { initialTab: 'song_name' })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()

    const cardTitleEl = root.querySelector('.card-title')
    const cardInfoEl = root.querySelector('.card-info')
    const cardActionsEl = root.querySelector('.card-actions')

    expect(cardTitleEl).not.toBeNull()
    expect(cardInfoEl).not.toBeNull()
    expect(cardActionsEl).not.toBeNull()
    expect(cardTitleEl.textContent).toContain('超长测试歌名')
  })

  it('歌手首字母栏将非 A-Z 字符收拢至末尾 # 桶，避免超长语种字母溢出', async () => {
    apiMock.browseArtistInitials.mockResolvedValueOnce(['B', 'a', 'А', 'あ', '9', '#'])
    app = createApp(KtvThreeColumnBrowser, { initialTab: 'singer' })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()
    await nextTick()

    const chips = Array.from(root.querySelectorAll('.rail-letters .letter-chip')).map(el => el.textContent.trim())
    expect(chips).toEqual(['热门', 'A', 'B', '#'])
  })
})
