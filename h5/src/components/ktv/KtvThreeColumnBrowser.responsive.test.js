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

describe('KtvThreeColumnBrowser 自适应网格与防挤压歌卡契约', () => {
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

  it('歌曲与歌手网格使用容器自适应 auto-fill 且单卡保底宽度不少于 330px/156px，严禁回退 4 列写死', () => {
    expect(browserStyleSource).toMatch(/\.song-grid\s*\{[\s\S]*?grid-template-columns:\s*repeat\(\s*auto-fill\s*,\s*minmax\(\s*min\(\s*330px\s*,\s*100%\s*\)\s*,\s*1fr\s*\)\s*\)/)
    expect(browserStyleSource).not.toMatch(/\.song-grid\s*\{[\s\S]*?grid-template-columns:\s*repeat\(\s*4\s*,/)
    expect(browserStyleSource).toMatch(/\.artist-grid\s*\{[\s\S]*?grid-template-columns:\s*repeat\(\s*auto-fill\s*,\s*minmax\(\s*min\(\s*156px\s*,\s*100%\s*\)\s*,\s*1fr\s*\)\s*\)/)
    expect(browserStyleSource).not.toMatch(/@media\s*\(\s*max-width:\s*1440px\s*\)/)
  })

  it('通用歌曲卡片采用双层排布：歌名独享上行，操作区位于下行 .card-bottom-row 不与歌名争宽', async () => {
    app = createApp(KtvThreeColumnBrowser, { initialTab: 'song_name' })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()

    const cardInfoEl = root.querySelector('.card-info')
    const titleRowEl = cardInfoEl?.querySelector('.card-title-row')
    const bottomRowEl = cardInfoEl?.querySelector('.card-bottom-row')
    const cardTitleEl = titleRowEl?.querySelector('.card-title')
    const cardSubEl = bottomRowEl?.querySelector('.card-sub')
    const cardActionsEl = bottomRowEl?.querySelector('.card-actions')

    expect(cardInfoEl).not.toBeNull()
    expect(titleRowEl).not.toBeNull()
    expect(bottomRowEl).not.toBeNull()
    expect(cardTitleEl).not.toBeNull()
    expect(cardSubEl).not.toBeNull()
    expect(cardActionsEl).not.toBeNull()
    expect(titleRowEl?.querySelector('.card-actions')).toBeNull()
    expect(cardTitleEl?.getAttribute('title')).toContain('超长测试歌名')
    expect(cardTitleEl?.textContent).toContain('超长测试歌名')
    expect(cardSubEl?.textContent?.trim()).toBe('测试歌手名非常长 · 04:00')
    expect(cardSubEl?.getAttribute('title')).toBe('测试歌手名非常长 · 04:00')
  })

  it('最近唱历史卡片同样使用双层结构与完整 title 悬浮提示，避免再唱按钮挤压歌名', async () => {
    apiMock.recentHistory.mockResolvedValueOnce([
      {
        historyId: 77,
        playedByNick: '客厅麦霸',
        playedAt: '2026-11-26T12:00:00Z',
        song: {
          id: 901,
          title: '海阔天空（经典粤语现场超长合唱版）',
          artist: 'Beyond',
        }
      }
    ])
    app = createApp(KtvThreeColumnBrowser, { initialTab: 'recent' })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()

    const titleRowEl = root.querySelector('.card-info .card-title-row')
    const bottomRowEl = root.querySelector('.card-info .card-bottom-row')
    const cardTitleEl = titleRowEl?.querySelector('.card-title')
    const cardSubEl = bottomRowEl?.querySelector('.card-sub')
    const actionBtn = bottomRowEl?.querySelector('.card-actions .order-btn')

    expect(cardTitleEl?.textContent?.trim()).toBe('海阔天空（经典粤语现场超长合唱版）')
    expect(cardTitleEl?.getAttribute('title')).toBe('海阔天空（经典粤语现场超长合唱版）')
    expect(cardSubEl?.textContent).toContain('Beyond · 客厅麦霸')
    expect(cardSubEl?.getAttribute('title')).toContain('Beyond · 客厅麦霸')
    expect(actionBtn?.textContent?.trim()).toBe('再唱')
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
