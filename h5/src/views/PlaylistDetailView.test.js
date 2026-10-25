// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock, makeControlsMock, toastMock } = vi.hoisted(() => ({
  apiMock: { playlistDetail: vi.fn(), orderPlaylist: vi.fn() },
  makeControlsMock: vi.fn(),
  toastMock: vi.fn()
}))

vi.mock('../api/client', () => ({ default: apiMock, makeControls: makeControlsMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav />' } }))
vi.mock('../components/SongRow.vue', () => ({
  default: {
    props: ['song', 'rank', 'ordered'],
    emits: ['order'],
    template: '<button class="song-row" :data-id="song.id" @click="$emit(\'order\', song)">{{ rank }}. {{ song.title }}</button>'
  }
}))

import PlaylistDetailView from './PlaylistDetailView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('PlaylistDetailView', () => {
  let app
  let root
  let router
  let pinia
  let player
  let orderMock

  beforeEach(async () => {
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.playlistDetail.mockResolvedValue(playlist(1, '测试歌单'))
    apiMock.orderPlaylist.mockResolvedValue({ ordered: 1, skipped: 0 })
    orderMock = vi.fn().mockResolvedValue({ list: [{}] })
    makeControlsMock.mockReturnValue({ order: orderMock })

    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore(pinia)
    user.clientToken = 'playlist-client'
    player = usePlayerStore(pinia)
    player.applySnapshot = vi.fn()
    player.applyControlResponse = vi.fn()

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/playlists/:id', name: 'playlist-detail', component: PlaylistDetailView },
        { path: '/', name: 'home', component: { template: '<div />' } }
      ]
    })
    await router.push('/playlists/1')
    await router.isReady()
    await mount()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
    Object.defineProperty(navigator, 'share', { configurable: true, value: undefined })
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
  })

  it('renders a public playlist and exposes an empty-list disabled state', async () => {
    expect(root.textContent).toContain('测试歌单')
    expect(root.textContent).toContain('🎶')
    expect(root.querySelector('.order-all').disabled).toBe(false)
    expect(apiMock.playlistDetail).toHaveBeenCalledWith('1', expect.objectContaining({ signal: expect.any(Object) }))

    apiMock.playlistDetail.mockResolvedValueOnce(playlist(2, '空歌单', []))
    await router.push('/playlists/2')
    await flushUi()
    expect(root.querySelector('.order-all').disabled).toBe(true)
    expect(root.querySelectorAll('.song-row')).toHaveLength(0)
  })

  it.each([
    ['儿童主题', '儿童', '🧸'],
    ['摇滚主题', '热血', '🎸'],
    ['情歌主题', '浪漫', '💞'],
    ['怀旧主题', '年代', '📻'],
    ['对唱主题', '合唱', '👥']
  ])('uses the %s cover icon', async (name, theme, icon) => {
    const data = playlist(20, name, [])
    data.theme = theme
    data.coverUrl = ''
    apiMock.playlistDetail.mockResolvedValueOnce(data)
    await router.push('/playlists/20')
    await flushUi()

    expect(root.querySelector('.cover').textContent).toContain(icon)
  })

  it('renders an explicit cover and fallback metadata when optional playlist fields are empty', async () => {
    const data = playlist(21, '', [])
    data.coverUrl = '/api/playlist-cover/21'
    data.aiGenerated = true
    data.description = ''
    apiMock.playlistDetail.mockResolvedValueOnce(data)
    await remount()

    expect(root.querySelector('.cover').style.backgroundImage).toContain('/api/playlist-cover/21')
    expect(root.querySelector('.ai')).not.toBeNull()
    expect(root.textContent).toContain('家庭欢唱精选歌单')
  })

  it('shows a missing-playlist state for both supported not-found responses', async () => {
    apiMock.playlistDetail.mockRejectedValueOnce(Object.assign(new Error('not found'), { status: 404 }))
    await router.push('/playlists/3')
    await flushUi()
    expect(root.textContent).toContain('歌单不存在或尚未公开')

    apiMock.playlistDetail.mockRejectedValueOnce(Object.assign(new Error('missing'), { code: 'PLAYLIST_NOT_FOUND' }))
    await router.push('/playlists/4')
    await flushUi()
    expect(root.textContent).toContain('歌单不存在或尚未公开')
  })

  it('keeps a load error visible and retries the same playlist', async () => {
    apiMock.playlistDetail.mockRejectedValueOnce(new Error('歌单暂不可读'))
    await router.push('/playlists/5')
    await flushUi()
    expect(root.textContent).toContain('歌单读取失败：歌单暂不可读')

    apiMock.playlistDetail.mockResolvedValueOnce(playlist(5, '重试歌单'))
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.textContent).toContain('重试歌单')
    expect(apiMock.playlistDetail).toHaveBeenLastCalledWith('5', expect.objectContaining({ signal: expect.any(Object) }))
  })

  it('uses the default load error when the rejected error has an empty message', async () => {
    apiMock.playlistDetail.mockRejectedValueOnce(new Error(''))
    await router.push('/playlists/6')
    await flushUi()

    expect(root.textContent).toContain('歌单读取失败：加载失败')
  })

  it('ignores an older request when route changes before it completes', async () => {
    let finishOld
    apiMock.playlistDetail.mockReset()
    apiMock.playlistDetail
      .mockReturnValueOnce(new Promise(resolve => { finishOld = resolve }))
      .mockResolvedValueOnce(playlist(9, '最新歌单'))
    await remount()

    await router.push('/playlists/9')
    await flushUi()
    finishOld(playlist(8, '过期歌单'))
    await flushUi()
    expect(root.textContent).toContain('最新歌单')
    expect(root.textContent).not.toContain('过期歌单')
  })

  it('does not surface an aborted detail request as a load error', async () => {
    let rejectOld
    apiMock.playlistDetail.mockReset()
    apiMock.playlistDetail
      .mockReturnValueOnce(new Promise((_, reject) => { rejectOld = reject }))
      .mockResolvedValueOnce(playlist(31, '当前歌单'))
    await remount()

    await router.push('/playlists/31')
    await flushUi()
    rejectOld(Object.assign(new Error('request aborted'), { name: 'AbortError' }))
    await flushUi()

    expect(root.textContent).toContain('当前歌单')
    expect(root.textContent).not.toContain('歌单读取失败')
  })

  it('orders one song and reports the returned queue position', async () => {
    apiMock.playlistDetail.mockResolvedValueOnce(playlist(1, '单曲点歌', [{ id: 14, title: '单曲' }]))
    await remount()
    root.querySelector('.song-row').click()
    await flushUi()

    expect(orderMock).toHaveBeenCalledWith(14)
    expect(player.applyControlResponse).toHaveBeenCalledWith({ list: [{}] })
    expect(toastMock).toHaveBeenCalledWith('已加入待播（第 1 位）')
  })

  it('handles single-song already-queued and ordinary failures', async () => {
    apiMock.playlistDetail.mockResolvedValueOnce(playlist(1, '异常点歌', [{ id: 16, title: '已点' }]))
    await remount()
    orderMock.mockRejectedValueOnce(Object.assign(new Error('已在队列中'), { code: 'SONG_IN_QUEUE' }))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('已在队列中')

    toastMock.mockClear()
    orderMock.mockRejectedValueOnce(new Error('点歌异常'))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('点歌异常')
  })

  it('uses separate fallback messages for single and bulk order failures without messages', async () => {
    apiMock.playlistDetail.mockResolvedValueOnce(playlist(1, '无错误文本', [{ id: 17, title: '歌曲' }]))
    await remount()

    orderMock.mockRejectedValueOnce(Object.assign(new Error(''), { code: 'SONG_IN_QUEUE' }))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('已在队列中')

    apiMock.orderPlaylist.mockRejectedValueOnce(new Error(''))
    root.querySelector('.order-all').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('整单点歌失败')
    expect(root.querySelector('.order-all').disabled).toBe(false)

    orderMock.mockRejectedValueOnce(new Error(''))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('点歌失败')
  })

  it('applies bulk-order snapshots and distinguishes skipped songs from a full queue', async () => {
    apiMock.playlistDetail.mockResolvedValueOnce(playlist(1, '整单点歌', [{ id: 1, title: 'A' }]))
    await remount()
    apiMock.orderPlaylist
      .mockResolvedValueOnce({ ordered: 3, skipped: 2, snapshot: { stateRevision: 7 } })
      .mockResolvedValueOnce({ ordered: 2, skipped: 0, queueFull: true })
      .mockResolvedValueOnce({ ordered: 1, skipped: 0 })

    const button = root.querySelector('.order-all')
    button.click()
    await flushUi()
    expect(apiMock.orderPlaylist).toHaveBeenNthCalledWith(1, 1, 'playlist-client')
    expect(player.applySnapshot).toHaveBeenCalledWith({ stateRevision: 7 })
    expect(toastMock).toHaveBeenCalledWith('已加入 3 首，跳过 2 首')

    button.click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('已加入 2 首，队列已满')

    button.click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('已加入 1 首歌曲')
  })

  it('shows bulk-order failures and always releases the busy state', async () => {
    apiMock.playlistDetail.mockResolvedValueOnce(playlist(1, '失败点歌', [{ id: 2, title: 'A' }]))
    await remount()
    apiMock.orderPlaylist.mockRejectedValueOnce(new Error('队列不可用'))
    root.querySelector('.order-all').click()
    await flushUi()

    expect(toastMock).toHaveBeenCalledWith('队列不可用')
    expect(root.querySelector('.order-all').disabled).toBe(false)
    expect(root.querySelector('.order-all').textContent).toContain('整单点歌')
  })

  it('uses web share when available and clipboard fallback otherwise', async () => {
    apiMock.playlistDetail.mockResolvedValueOnce(playlist(1, '可分享', [{ id: 2, title: 'A' }]))
    await remount()
    const shareButton = root.querySelector('.share')
    const share = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { configurable: true, value: share })
    shareButton.click()
    await flushUi()
    expect(share).toHaveBeenCalledWith(expect.objectContaining({ title: '可分享', url: location.href }))

    Object.defineProperty(navigator, 'share', { configurable: true, value: undefined })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    shareButton.click()
    await flushUi()
    expect(writeText).toHaveBeenCalledWith(location.href)
    expect(toastMock).toHaveBeenCalledWith('歌单链接已复制')
  })

  it('reports share cancellation silently and other share failures with a recovery hint', async () => {
    apiMock.playlistDetail.mockResolvedValueOnce(playlist(1, '分享异常', [{ id: 2, title: 'A' }]))
    await remount()
    const shareButton = root.querySelector('.share')
    Object.defineProperty(navigator, 'share', {
      configurable: true,
      value: vi.fn().mockRejectedValueOnce(Object.assign(new Error('cancelled'), { name: 'AbortError' }))
    })
    shareButton.click()
    await flushUi()
    expect(toastMock).not.toHaveBeenCalled()

    Object.defineProperty(navigator, 'share', {
      configurable: true,
      value: vi.fn().mockRejectedValueOnce(new Error('share failed'))
    })
    shareButton.click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('分享失败，请复制浏览器地址')
  })

  it('uses default title and description when sharing incomplete playlist metadata', async () => {
    const data = playlist(1, '', [])
    data.description = ''
    apiMock.playlistDetail.mockResolvedValueOnce(data)
    await remount()
    const share = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { configurable: true, value: share })

    root.querySelector('.share').click()
    await flushUi()

    expect(share).toHaveBeenCalledWith(expect.objectContaining({
      title: '家庭KTV主题歌单',
      text: '一起来唱歌吧',
      url: location.href
    }))
  })

  async function mount() {
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  }

  async function remount() {
    app?.unmount()
    root?.remove()
    await mount()
  }
})

function playlist(id, name, songs = [{ id: Number(id) + 10, title: '歌单曲目' }]) {
  return { id: Number(id), name, theme: '', description: '欢唱精选', songs, aiGenerated: false }
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
