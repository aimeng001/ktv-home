// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock, makeControlsMock, toastMock } = vi.hoisted(() => ({
  apiMock: { favorites: vi.fn(), favoriteIds: vi.fn() },
  makeControlsMock: vi.fn(),
  toastMock: vi.fn()
}))

vi.mock('../api/client', () => ({ default: apiMock, makeControls: makeControlsMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav />' } }))
vi.mock('../components/SongRow.vue', () => ({
  default: {
    props: ['song', 'ordered'],
    emits: ['order'],
    template: '<button class="song-row" :data-id="song.id" :data-ordered="ordered" @click="$emit(\'order\', song)">{{ song.title }}</button>'
  }
}))

import FavoritesView from './FavoritesView.vue'
import { useFavoritesStore } from '../stores/favorites'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('FavoritesView', () => {
  let app
  let root
  let router
  let pinia
  let favorites
  let player
  let orderMock

  beforeEach(async () => {
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.favorites.mockResolvedValue([])
    apiMock.favoriteIds.mockResolvedValue([])
    orderMock = vi.fn().mockResolvedValue({ list: [{}, {}] })
    makeControlsMock.mockReturnValue({ order: orderMock })

    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore(pinia)
    user.clientToken = 'favorites-client'
    favorites = useFavoritesStore(pinia)
    player = usePlayerStore(pinia)
    player.applyControlResponse = vi.fn()

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/favorites', name: 'favorites', component: FavoritesView }
      ]
    })
    await router.push('/favorites')
    await router.isReady()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('shows the successful empty state and synchronizes the current user favorites', () => {
    expect(root.textContent).toContain('还没有收藏歌曲')
    expect(apiMock.favorites).toHaveBeenCalledWith('favorites-client', 0, 100)
    expect(apiMock.favoriteIds).toHaveBeenCalledWith('favorites-client', 0, 100)
    expect(root.querySelector('header span').textContent).toBe('0 首')
  })

  it('loads all song pages before rendering and keeps the final partial page', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({ id: index + 1, title: `收藏歌${index + 1}` }))
    apiMock.favorites.mockReset()
    apiMock.favoriteIds.mockReset()
    apiMock.favorites.mockResolvedValueOnce(firstPage).mockResolvedValueOnce([{ id: 101, title: '最后一页' }])
    apiMock.favoriteIds.mockResolvedValueOnce(firstPage.map(song => song.id)).mockResolvedValueOnce([101])
    await remount()

    expect(apiMock.favorites.mock.calls.map(([, page, size]) => [page, size])).toEqual([[0, 100], [1, 100]])
    expect(root.querySelectorAll('.song-row')).toHaveLength(101)
    expect(root.textContent).toContain('最后一页')
    expect(favorites.ids).toHaveLength(101)
  })

  it('shows a retryable read error and loads songs after the retry succeeds', async () => {
    apiMock.favorites.mockRejectedValueOnce(new Error('收藏服务不可用'))
    await remount()
    expect(root.textContent).toContain('收藏读取失败：收藏服务不可用')

    apiMock.favorites.mockResolvedValueOnce([{ id: 8, title: '恢复的收藏' }])
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.querySelector('.song-row').textContent).toBe('恢复的收藏')
    expect(root.querySelector('header span').textContent).toBe('1 首')
  })

  it('shows fallback text when favorite loading fails without an error message', async () => {
    apiMock.favorites.mockRejectedValueOnce(new Error(''))
    await remount()

    expect(root.querySelector('.error-tip').textContent).toContain('网络错误，请稍后重试')

    apiMock.favorites.mockResolvedValueOnce([])
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.querySelector('.error-tip')).toBeNull()
    expect(root.textContent).toContain('还没有收藏歌曲')
  })

  it('removes rows when another view removes a favorite from the shared store', async () => {
    apiMock.favorites.mockResolvedValueOnce([
      { id: 1, title: '保留收藏' },
      { id: 2, title: '随后取消' }
    ])
    await remount()
    favorites.ids = [1, 2]
    await flushUi()

    favorites.ids = [1]
    await flushUi()
    expect(root.querySelectorAll('.song-row')).toHaveLength(1)
    expect(root.textContent).toContain('保留收藏')
    expect(root.textContent).not.toContain('随后取消')
  })

  it('orders a favorite and reports the queue position returned by the server', async () => {
    apiMock.favorites.mockResolvedValueOnce([{ id: 17, title: '收藏点歌' }])
    await remount()
    root.querySelector('.song-row').click()
    await flushUi()

    expect(orderMock).toHaveBeenCalledWith(17)
    expect(player.applyControlResponse).toHaveBeenCalledWith({ list: [{}, {}] })
    expect(toastMock).toHaveBeenCalledWith('已加入待播（第 2 位）')
  })

  it('uses queue-specific and generic fallback messages for failed favorite orders', async () => {
    apiMock.favorites.mockResolvedValueOnce([{ id: 18, title: '点歌失败' }])
    await remount()

    orderMock.mockRejectedValueOnce(Object.assign(new Error(''), { code: 'SONG_IN_QUEUE' }))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('已在队列中')

    orderMock.mockRejectedValueOnce(new Error(''))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('点歌失败')
  })

  async function remount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  }
})

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
