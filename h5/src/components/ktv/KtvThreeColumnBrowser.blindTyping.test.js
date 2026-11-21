// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    searchSongs: vi.fn(async () => []),
    browseSongPage: vi.fn(async () => ({ items: [], total: 0 })),
    ranking: vi.fn(async () => []),
    newSongs: vi.fn(async () => []),
    artists: vi.fn(async () => []),
    artistsPage: vi.fn(async () => ({ items: [], total: 0 })),
    tags: vi.fn(async () => []),
    languages: vi.fn(async () => []),
    favoriteIds: vi.fn(async () => []),
    favorites: vi.fn(async () => [])
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

describe('KtvThreeColumnBrowser 物理键盘盲打全局搜歌', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore()
    user.token = 'test-token'
    user.serverUserId = 1

    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root?.parentNode) root.parentNode.removeChild(root)
    vi.clearAllMocks()
  })

  it('在浏览器背景下直接按键 s 和 n，软键盘输入框同步显示 SN 并触发搜索', async () => {
    app = createApp(KtvThreeColumnBrowser, {
      initialTab: 'song_name'
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    // 模拟用户直接敲击物理键盘 's' 和 'n'
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', bubbles: true }))
    await nextTick()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'n', bubbles: true }))
    await nextTick()

    const input = root.querySelector('.kb-input-row input')
    expect(input).not.toBeNull()
    expect(input.value).toBe('SN')

    // 敲击退格键 Backspace
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true, cancelable: true }))
    await nextTick()
    expect(input.value).toBe('S')
  })
})
