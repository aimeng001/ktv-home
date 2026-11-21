// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    favorites: vi.fn(),
    favoriteIds: vi.fn(),
    addFavorite: vi.fn(),
    removeFavorite: vi.fn(),
    songDetail: vi.fn()
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
import { useFavoritesStore } from '../../stores/favorites'

function textWith(root, needle) {
  return [...root.querySelectorAll('*')].some(el => (el.textContent || '').includes(needle))
}

describe('KtvThreeColumnBrowser 收藏状态同步失败', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore()
    user.ensureToken()
    user.markRegistrationSuccess({ id: 3, nickname: '点歌人' })

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

  async function mountBrowser() {
    app = createApp(KtvThreeColumnBrowser, { initialTab: 'favorites' })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
    await nextTick()
  }

  it('收藏状态同步失败时提示可重试', async () => {
    // 应用启动时（App.vue）同步收藏 ID 失败 → loadFailed 置位
    useFavoritesStore().loadFailed = true
    apiMock.favorites.mockRejectedValue(new Error('boom'))

    await mountBrowser()

    expect(textWith(root, '收藏状态')).toBe(true)
    expect(textWith(root, '重新同步')).toBe(true)
  })

  it('重试成功后提示消失', async () => {
    useFavoritesStore().loadFailed = true
    apiMock.favorites.mockRejectedValue(new Error('boom'))
    apiMock.favoriteIds.mockResolvedValue([3, 8])

    await mountBrowser()
    expect(textWith(root, '收藏状态')).toBe(true)

    const retry = [...root.querySelectorAll('button')].find(btn => (btn.textContent || '').includes('重新同步'))
    retry.click()
    await nextTick()
    await nextTick()
    await nextTick()
    expect(textWith(root, '收藏状态')).toBe(false)
  })

  it('收藏页加载只请求一次列表，不再额外拉一轮收藏 ID', async () => {
    apiMock.favorites.mockResolvedValue([
      { id: 11, title: '晴天' },
      { id: 12, title: '后来' }
    ])

    await mountBrowser()

    expect(apiMock.favorites).toHaveBeenCalledTimes(1)
    expect(apiMock.favoriteIds).not.toHaveBeenCalled()
    expect(useFavoritesStore().ids).toEqual([11, 12])
  })

  it('收藏列表本身失败时仍走原有错误提示', async () => {
    apiMock.favorites.mockRejectedValue(new Error())
    await mountBrowser()
    expect(textWith(root, '收藏读取失败')).toBe(true)
  })

  it('不可点歌曲把后端错误码翻成中文提示，而不是直接显示码', async () => {
    apiMock.favorites.mockResolvedValue([
      { id: 21, title: '还在探测', artist: '某歌手', playable: false, unavailableReason: 'SONG_NOT_READY' },
      { id: 22, title: '未知原因', artist: '某歌手', playable: false, unavailableReason: 'FUTURE_CODE' },
      { id: 23, title: '可点歌曲', artist: '某歌手', playable: true }
    ])

    await mountBrowser()

    const orderButtons = [...root.querySelectorAll('.order-btn')]
    expect(orderButtons.map(btn => btn.title)).toEqual([
      '正在扫描或媒体探测未完成',
      '当前不可点',
      ''
    ])
    expect(orderButtons.map(btn => btn.title).join(' ')).not.toContain('SONG_NOT_READY')
  })
})
