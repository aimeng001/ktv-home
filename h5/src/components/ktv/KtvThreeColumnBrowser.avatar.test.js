// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    browseSongPage: vi.fn(),
    newSongs: vi.fn(),
    ranking: vi.fn(),
    favorites: vi.fn(),
    favoriteIds: vi.fn(),
    recentHistory: vi.fn(),
    searchSongs: vi.fn(),
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
import { useUserStore } from '../../stores/user'

describe('KtvThreeColumnBrowser 非歌手页签头像渲染与降级', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore()
    user.ensureToken()
    user.markRegistrationSuccess({ id: 1, nickname: '点歌人' })

    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('热歌新歌与最近唱卡片读取 artistAvatarUrl，绝不构造失效的 /api/artists/{name}/avatar', async () => {
    apiMock.browseSongPage.mockResolvedValue({
      items: [
        {
          id: 1,
          catalogNumber: '00046394',
          title: 'I EASY',
          artist: '刘德华',
          mediaType: 'KTV_VIDEO',
          durationMs: 195000,
          coverUrl: null,
          artistAvatarUrl: '/api/artists/avatar?key=%E5%88%98%E5%BE%B7%E5%8D%8E',
          playable: true,
        },
        {
          id: 2,
          catalogNumber: '100001620',
          title: '一路生花',
          artist: '温奕心',
          mediaType: 'MV',
          durationMs: 256000,
          coverUrl: null,
          artistAvatarUrl: null,
          playable: true,
        }
      ],
      total: 2,
      page: 0,
      size: 18,
    })

    app = createApp(KtvThreeColumnBrowser, { initialTab: 'hot_new' })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    const thumbs = root.querySelectorAll('.song-card .card-thumb')
    expect(thumbs.length).toBe(2)

    const img0 = thumbs[0].querySelector('img')
    expect(img0?.getAttribute('src')).toBe('/api/artists/avatar?key=%E5%88%98%E5%BE%B7%E5%8D%8E')
    expect(thumbs[0].innerHTML).not.toContain('/api/artists/%E5%88%98%E5%BE%B7%E5%8D%8E/avatar')

    // 无头像 URL 的第二首展示歌手首字与莫兰迪底色
    expect(thumbs[1].querySelector('img')).toBeNull()
    expect(thumbs[1].textContent.trim()).toBe('温')
    expect(thumbs[1].getAttribute('style')).toBeTruthy()

    // 第一张图片触发加载错误后回退到莫兰迪首字
    img0.dispatchEvent(new Event('error'))
    await nextTick()
    expect(thumbs[0].querySelector('img')).toBeNull()
    expect(thumbs[0].textContent.trim()).toBe('刘')
  })

  it('最近唱页签同样展示歌手头像，缺失时回退为歌手首字', async () => {
    apiMock.recentHistory.mockResolvedValue([
      {
        historyId: 10,
        playedByNick: '家人',
        playedAt: '2026-11-25T00:00:00Z',
        song: {
          id: 9,
          title: '大鱼',
          artist: '周深',
          coverUrl: null,
          artistAvatarUrl: '/api/artists/avatar?key=%E5%91%A8%E6%B7%B1',
        }
      },
      {
        historyId: 11,
        playedByNick: '家人',
        playedAt: '2026-11-25T00:01:00Z',
        song: {
          id: 10,
          title: '小幸运',
          artist: '田馥甄',
          coverUrl: null,
          artistAvatarUrl: null,
        }
      }
    ])

    app = createApp(KtvThreeColumnBrowser, { initialTab: 'recent' })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    const thumbs = root.querySelectorAll('.song-card .card-thumb')
    expect(thumbs[0].querySelector('img')?.getAttribute('src')).toBe('/api/artists/avatar?key=%E5%91%A8%E6%B7%B1')
    expect(thumbs[1].querySelector('img')).toBeNull()
    expect(thumbs[1].textContent.trim()).toBe('田')
  })
})
