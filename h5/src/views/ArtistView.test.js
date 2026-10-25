// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock, makeControlsMock, toastMock } = vi.hoisted(() => ({
  apiMock: { browseSongPage: vi.fn() },
  makeControlsMock: vi.fn(),
  toastMock: vi.fn()
}))

vi.mock('../api/client', () => ({ default: apiMock, makeControls: makeControlsMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav />' } }))
vi.mock('../components/SongRow.vue', () => ({
  default: {
    props: ['song', 'extra', 'ordered'],
    emits: ['order'],
    template: '<button class="song-row" :data-id="song.id" :data-extra="extra" @click="$emit(\'order\', song)">{{ song.title }}</button>'
  }
}))

import ArtistView from './ArtistView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('ArtistView', () => {
  let app
  let root
  let router
  let pinia
  let user
  let player
  let orderMock

  beforeEach(async () => {
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.browseSongPage.mockResolvedValue({ items: [], total: 0 })
    orderMock = vi.fn().mockResolvedValue({ list: [{}] })
    makeControlsMock.mockReturnValue({ order: orderMock })
    pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore(pinia)
    user.clientToken = 'artist-client'
    player = usePlayerStore(pinia)
    player.applyControlResponse = vi.fn()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/artist/:name', name: 'artist', component: ArtistView },
        { path: '/', name: 'home', component: { template: '<div />' } }
      ]
    })
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('loads artist songs with the initial hot sort and formats row durations', async () => {
    apiMock.browseSongPage.mockResolvedValueOnce({
      items: [{ id: 7, title: '十年', durationMs: 222000 }],
      total: 1
    })
    await mountAt('/artist/陈奕迅')

    expect(root.querySelector('.head h1').textContent).toBe('陈奕迅')
    expect(root.querySelector('.head p').textContent).toBe('1 首歌曲')
    expect(root.querySelector('.song-row').getAttribute('data-extra')).toBe('3:42')
    expect(apiMock.browseSongPage).toHaveBeenCalledWith({ sort: 'hot', page: 0, size: 50, artist: '陈奕迅' })
  })

  it('recognizes the all-songs route when the mode query is absent', async () => {
    await mountAt('/artist/all')

    expect(root.querySelector('.head h1').textContent).toBe('全部歌曲')
    expect(root.querySelector('.gender-filter')).not.toBeNull()
    expect(apiMock.browseSongPage).toHaveBeenCalledWith({ sort: 'hot', page: 0, size: 50 })
  })

  it('uses language, tag, and vocal-form filters from the route', async () => {
    await mountAt('/artist/all?mode=language&value=粤语&sort=title')
    expect(root.querySelector('.head h1').textContent).toBe('粤语歌曲')
    expect(apiMock.browseSongPage).toHaveBeenCalledWith({ sort: 'title', page: 0, size: 50, language: '粤语' })

    await router.push('/artist/all?mode=tag&value=摇滚')
    await flushUi()
    expect(root.querySelector('.head h1').textContent).toBe('摇滚')
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith({ sort: 'title', page: 0, size: 50, tag: '摇滚' })

    await router.push('/artist/all?mode=vocalForm&value=对唱')
    await flushUi()
    expect(root.querySelector('.head h1').textContent).toBe('对唱')
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith({ sort: 'title', page: 0, size: 50, vocalForm: '对唱' })
  })

  it('includes artist identity and optional gender when the matching route data is present', async () => {
    await mountAt('/artist/林忆莲?artistKey=artist%3A11&artistGender=女歌手')
    expect(apiMock.browseSongPage).toHaveBeenCalledWith({
      sort: 'hot', page: 0, size: 50, artist: '林忆莲', artistKey: 'artist:11', artistGender: '女歌手'
    })
  })

  it('shows an empty state and retries an initial page error', async () => {
    await mountAt('/artist/all?mode=all')
    expect(root.textContent).toContain('没有找到相关歌曲')

    apiMock.browseSongPage.mockRejectedValueOnce(new Error('曲库读取失败'))
    await router.push('/artist/all?mode=language&value=韩语')
    await flushUi()
    expect(root.textContent).toContain('歌曲读取失败：曲库读取失败')

    apiMock.browseSongPage.mockResolvedValueOnce({ items: [{ id: 3, title: '恢复歌曲' }], total: 1 })
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.textContent).toContain('恢复歌曲')
  })

  it('uses fallback text for initial and later-page failures without a message', async () => {
    await mountAt('/artist/all?mode=all')

    apiMock.browseSongPage.mockRejectedValueOnce({ message: '' })
    await router.push('/artist/陈奕迅')
    await flushUi()
    expect(root.textContent).toContain('歌曲读取失败：网络错误，请稍后重试')

    apiMock.browseSongPage.mockResolvedValueOnce({ items: [{ id: 7, title: '保留歌曲' }], total: 51 })
    await router.push('/artist/all?mode=all')
    await flushUi()
    apiMock.browseSongPage.mockRejectedValueOnce({ message: '' })
    root.querySelector('.load-more button').click()
    await flushUi()
    expect(root.textContent).toContain('后续歌曲读取失败：网络错误，请稍后重试')
    expect(root.textContent).toContain('保留歌曲')
  })

  it('changes sort and gender filters by reloading the first page', async () => {
    await mountAt('/artist/all?mode=all')
    clickText('.sorts button', '最新')
    await flushUi()
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith({ sort: 'new', page: 0, size: 50 })

    clickText('.gender-filter button', '男歌手')
    await flushUi()
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith({ sort: 'new', page: 0, size: 50, artistGender: '男歌手' })
  })

  it('appends a later page and removes pagination when the returned total is invalid', async () => {
    apiMock.browseSongPage
      .mockResolvedValueOnce({ items: [{ id: 1, title: '第一页' }], total: 51 })
      .mockResolvedValueOnce({ items: [{ id: 2, title: '第二页' }], total: Number.NaN })
    await mountAt('/artist/all?mode=all')

    root.querySelector('.load-more button').click()
    await flushUi()
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith({ sort: 'hot', page: 1, size: 50 })
    expect(root.querySelectorAll('.song-row')).toHaveLength(2)
    expect(root.querySelector('.load-more')).toBeNull()
  })

  it('treats a malformed page payload as an empty result', async () => {
    apiMock.browseSongPage.mockResolvedValueOnce({ items: null, total: Number.POSITIVE_INFINITY })
    await mountAt('/artist/all?mode=all')

    expect(root.textContent).toContain('没有找到相关歌曲')
    expect(root.querySelector('.song-row')).toBeNull()
    expect(root.querySelector('.load-more')).toBeNull()
  })

  it('keeps the first page when a later page fails and retries without resetting it', async () => {
    apiMock.browseSongPage
      .mockResolvedValueOnce({ items: [{ id: 5, title: '保留的第一页' }], total: 51 })
      .mockRejectedValueOnce(new Error('下一页失败'))
      .mockResolvedValueOnce({ items: [{ id: 6, title: '重试的下一页' }], total: 2 })
    await mountAt('/artist/all?mode=all')

    root.querySelector('.load-more button').click()
    await flushUi()
    expect(root.textContent).toContain('后续歌曲读取失败：下一页失败')
    expect(root.textContent).toContain('保留的第一页')
    root.querySelector('.tip.error-tip .retry').click()
    await flushUi()
    expect(root.querySelectorAll('.song-row')).toHaveLength(2)
    expect(root.textContent).toContain('重试的下一页')
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith({ sort: 'hot', page: 1, size: 50 })
  })

  it('does not append an obsolete page after the route changes', async () => {
    let finishOldPage
    apiMock.browseSongPage
      .mockResolvedValueOnce({ items: [{ id: 1, title: '旧路由第一页' }], total: 51 })
      .mockReturnValueOnce(new Promise(resolve => { finishOldPage = resolve }))
      .mockResolvedValueOnce({ items: [{ id: 3, title: '新路由歌曲' }], total: 1 })
    await mountAt('/artist/all?mode=all')

    root.querySelector('.load-more button').click()
    await flushUi()
    await router.push('/artist/张学友')
    await flushUi()
    finishOldPage({ items: [{ id: 2, title: '过期第二页' }], total: 51 })
    await flushUi()

    expect(root.querySelectorAll('.song-row')).toHaveLength(1)
    expect(root.textContent).toContain('新路由歌曲')
    expect(root.textContent).not.toContain('过期第二页')
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith({ sort: 'hot', page: 0, size: 50, artist: '张学友' })
  })

  it('orders a song and reports queue errors', async () => {
    apiMock.browseSongPage.mockResolvedValueOnce({ items: [{ id: 18, title: '待点歌曲' }], total: 1 })
    await mountAt('/artist/all?mode=all')
    orderMock.mockRejectedValueOnce(Object.assign(new Error('已在队列中'), { code: 'SONG_IN_QUEUE' }))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('已在队列中')

    toastMock.mockClear()
    orderMock.mockResolvedValueOnce({ list: [{}, {}] })
    root.querySelector('.song-row').click()
    await flushUi()
    expect(player.applyControlResponse).toHaveBeenCalledWith({ list: [{}, {}] })
    expect(toastMock).toHaveBeenCalledWith('已加入待播（第 2 位）')

    toastMock.mockClear()
    orderMock.mockRejectedValueOnce({ code: 'SONG_IN_QUEUE', message: '' })
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('已在队列中')

    toastMock.mockClear()
    orderMock.mockRejectedValueOnce({ message: '' })
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('点歌失败')
  })

  async function mountAt(path) {
    app?.unmount()
    root?.remove()
    await router.push(path)
    await router.isReady()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  }
})

function clickText(selector, text) {
  const button = [...document.querySelectorAll(selector)].find(element => element.textContent.trim() === text)
  if (!button) throw new Error(`Button not found: ${selector} / ${text}`)
  button.click()
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
