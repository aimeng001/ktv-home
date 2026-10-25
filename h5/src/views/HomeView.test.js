// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock, makeControlsMock, toastMock } = vi.hoisted(() => ({
  apiMock: {
    ranking: vi.fn(),
    newSongs: vi.fn()
  },
  makeControlsMock: vi.fn(),
  toastMock: vi.fn()
}))

vi.mock('../api/client', () => ({ default: apiMock, makeControls: makeControlsMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../components/NowPlayingBar.vue', () => ({ default: { template: '<div data-testid="now-playing" />' } }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav data-testid="tabbar" />' } }))
vi.mock('../components/SongRow.vue', () => ({
  default: {
    props: ['song', 'rank', 'ordered'],
    emits: ['order'],
    template: '<button class="song-row" :data-id="song.id" :data-ordered="ordered" @click="$emit(\'order\', song)">{{ rank }}. {{ song.title }}</button>'
  }
}))

import HomeView from './HomeView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('HomeView', () => {
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
    apiMock.ranking.mockResolvedValue([])
    apiMock.newSongs.mockResolvedValue([])
    orderMock = vi.fn().mockResolvedValue({ list: [{}, {}] })
    makeControlsMock.mockReturnValue({ order: orderMock })

    pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore(pinia)
    user.nickname = '小明'
    user.clientToken = 'client-token'
    player = usePlayerStore(pinia)
    player.applyControlResponse = vi.fn()

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/home', name: 'home', component: HomeView },
        { path: '/search', name: 'search', component: { template: '<div>search</div>' } },
        { path: '/browse', name: 'browse', component: { template: '<div>browse</div>' } },
        { path: '/artist/:name', name: 'artist', component: { template: '<div>artist</div>' } },
        { path: '/playlists', name: 'playlists', component: { template: '<div>playlists</div>' } },
        { path: '/recent', name: 'recent-history', component: { template: '<div>recent</div>' } },
        { path: '/favorites', name: 'favorites', component: { template: '<div>favorites</div>' } }
      ]
    })
    await router.push('/home')
    await router.isReady()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('loads ranked songs and marks songs already in the shared queue', () => {
    apiMock.ranking.mockResolvedValueOnce([{ id: 2, title: '热门歌' }, { id: 3, title: '排队歌曲' }])
    player.queue = [{ song: { id: 3 } }]
    return mountHome().then(() => {
      expect(root.textContent).toContain('晚上好，小明')
      expect(root.querySelectorAll('.song-row')).toHaveLength(2)
      expect(root.querySelectorAll('.song-row')[0].textContent).toContain('1. 热门歌')
      expect(root.querySelectorAll('.song-row')[1].getAttribute('data-ordered')).toBe('true')
      expect(apiMock.newSongs).not.toHaveBeenCalled()
    })
  })

  it('shows loading until the initial ranking request settles', async () => {
    let resolveRanking
    apiMock.ranking.mockReturnValueOnce(new Promise(resolve => { resolveRanking = resolve }))
    await mountHome()

    expect(root.textContent).toContain('加载中…')
    resolveRanking([{ id: 7, title: '已加载歌曲' }])
    await flushUi()
    expect(root.textContent).toContain('已加载歌曲')
    expect(root.textContent).not.toContain('加载中…')
  })

  it('renders unknown offline and online television states', async () => {
    player.tvOnline = null
    await mountHome()

    const room = root.querySelector('.room')
    expect(room.textContent).toContain('电视状态未知')
    expect(room.classList.contains('unknown')).toBe(true)

    player.tvOnline = false
    await flushUi()
    expect(room.textContent).toContain('电视离线')
    expect(room.classList.contains('offline')).toBe(true)
    expect(room.classList.contains('unknown')).toBe(false)

    player.tvOnline = true
    await flushUi()
    expect(room.textContent).toContain('电视在线')
    expect(room.classList.contains('offline')).toBe(false)
    expect(room.classList.contains('unknown')).toBe(false)
  })

  it('falls back to new songs when the ranking is empty and shows the empty library state', async () => {
    apiMock.ranking.mockResolvedValueOnce([])
    apiMock.newSongs.mockResolvedValueOnce([])
    await mountHome()

    expect(apiMock.ranking).toHaveBeenCalledWith(30)
    expect(apiMock.newSongs).toHaveBeenCalledTimes(1)
    expect(root.textContent).toContain('曲库还没有歌，先去后台扫描入库')
  })

  it('renders recent songs when the empty ranking falls back to a populated library', async () => {
    apiMock.ranking.mockResolvedValueOnce([])
    apiMock.newSongs.mockResolvedValueOnce([{ id: 8, title: '刚入库的新歌' }])
    await mountHome()

    expect(apiMock.ranking).toHaveBeenCalledWith(30)
    expect(apiMock.newSongs).toHaveBeenCalledTimes(1)
    expect(root.querySelector('.song-row')?.textContent).toContain('刚入库的新歌')
  })

  it('shows a retryable error and reloads ranking after a failure', async () => {
    apiMock.ranking
      .mockRejectedValueOnce(new Error('排行服务不可用'))
      .mockResolvedValueOnce([{ id: 5, title: '恢复后的热门歌' }])
    await mountHome()

    expect(root.textContent).toContain('热门歌曲读取失败：排行服务不可用')
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.textContent).toContain('恢复后的热门歌')
    expect(apiMock.ranking).toHaveBeenCalledTimes(2)
  })

  it('uses the network fallback text when ranking fails without an error message', async () => {
    apiMock.ranking.mockRejectedValueOnce({})
    await mountHome()

    expect(root.textContent).toContain('热门歌曲读取失败：网络错误，请稍后重试')
  })

  it('orders a song, applies the returned queue snapshot, and reports its position', async () => {
    apiMock.ranking.mockResolvedValueOnce([{ id: 11, title: '点播成功' }])
    await mountHome()

    root.querySelector('.song-row').click()
    await flushUi()
    expect(makeControlsMock).toHaveBeenCalledWith('client-token')
    expect(orderMock).toHaveBeenCalledWith(11)
    expect(player.applyControlResponse).toHaveBeenCalledWith({ list: [{}, {}] })
    expect(toastMock).toHaveBeenCalledWith('已加入待播（第 2 位）')
  })

  it('keeps concurrent repeat clicks from sending duplicate order requests', async () => {
    let finishOrder
    apiMock.ranking.mockResolvedValueOnce([{ id: 15, title: '只点一次' }])
    orderMock.mockReturnValueOnce(new Promise(resolve => { finishOrder = resolve }))
    await mountHome()

    const row = root.querySelector('.song-row')
    row.click()
    row.click()
    await flushUi()
    expect(orderMock).toHaveBeenCalledTimes(1)
    finishOrder({ list: [{}] })
    await flushUi()
    expect(orderMock).toHaveBeenCalledTimes(1)
  })

  it('distinguishes already-queued errors from other order failures', async () => {
    apiMock.ranking.mockResolvedValueOnce([{ id: 19, title: '点歌异常' }])
    await mountHome()
    orderMock.mockRejectedValueOnce(Object.assign(new Error('已经在队列中'), { code: 'SONG_IN_QUEUE' }))

    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('已经在队列中')

    toastMock.mockClear()
    orderMock.mockRejectedValueOnce(new Error('服务器故障'))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('服务器故障')
  })

  it('uses separate fallback messages for message-less order failures', async () => {
    apiMock.ranking.mockResolvedValueOnce([{ id: 21, title: '空错误消息' }])
    await mountHome()

    orderMock.mockRejectedValueOnce(Object.assign(new Error(), { code: 'SONG_IN_QUEUE' }))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('这首歌已在队列中')

    toastMock.mockClear()
    orderMock.mockRejectedValueOnce(new Error())
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('点歌失败')
  })

  it('routes search and every visible category to its intended destination', async () => {
    await mountHome()
    root.querySelector('.search').click()
    await flushUi()
    expect(router.currentRoute.value.name).toBe('search')
    await router.push('/home')
    await flushUi()

    const expectedRoutes = [
      ['歌手', 'browse', { tab: 'artists' }],
      ['语种', 'browse', { tab: 'languages' }],
      ['分类', 'browse', { tab: 'tags' }],
      ['歌单', 'playlists', undefined],
      ['新歌', 'artist', { mode: 'all', sort: 'new' }],
      ['对唱', 'artist', { mode: 'vocalForm', value: '对唱' }],
      ['最近唱', 'recent-history', undefined],
      ['我的收藏', 'favorites', undefined]
    ]
    for (const [label, name, query] of expectedRoutes) {
      const button = [...root.querySelectorAll('.cat')].find(item => item.textContent.trim() === label)
      button.click()
      await flushUi()
      expect(router.currentRoute.value.name).toBe(name)
      if (query) expect(router.currentRoute.value.query).toMatchObject(query)
      await router.push('/home')
      await flushUi()
    }
  })

  async function mountHome() {
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    await router.push('/home')
    await router.isReady()
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
