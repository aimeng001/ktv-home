// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock, makeControlsMock, controlsMock, toastMock, confirmDialogMock } = vi.hoisted(() => ({
  apiMock: { history: vi.fn(), roomHostStatus: vi.fn(), claimRoomHost: vi.fn(), releaseRoomHost: vi.fn() },
  makeControlsMock: vi.fn(),
  controlsMock: { order: vi.fn(), top: vi.fn(), cancel: vi.fn(), next: vi.fn(), shuffle: vi.fn() },
  toastMock: vi.fn(),
  confirmDialogMock: vi.fn()
}))

vi.mock('../api/client', () => ({ default: apiMock, makeControls: makeControlsMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../composables/useDialog', () => ({ confirmDialog: confirmDialogMock }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav />' } }))

import QueueView from './QueueView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('QueueView', () => {
  let app
  let root
  let router
  let pinia
  let player
  let user

  beforeEach(async () => {
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.history.mockResolvedValue([])
    apiMock.roomHostStatus.mockResolvedValue(null)
    apiMock.claimRoomHost.mockResolvedValue({ claimed: true, isHost: true, hostNickname: '小明', revision: 1 })
    apiMock.releaseRoomHost.mockResolvedValue({ claimed: false, isHost: false, revision: 2 })
    for (const method of Object.values(controlsMock)) method.mockResolvedValue({})
    makeControlsMock.mockReturnValue(controlsMock)
    confirmDialogMock.mockResolvedValue(true)

    pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore(pinia)
    user.clientToken = 'queue-client'
    player = usePlayerStore(pinia)
    player.applyControlResponse = vi.fn()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/queue', name: 'queue', component: QueueView },
        { path: '/', name: 'home', component: { template: '<div />' } }
      ]
    })
    await router.push('/queue')
    await router.isReady()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('shows the empty queue and keeps a missing host service non-blocking', async () => {
    apiMock.roomHostStatus.mockRejectedValueOnce(new Error('host unavailable'))
    await mount()
    expect(root.textContent).toContain('还没有待播歌曲')
    expect(root.textContent).toContain('尚未设置房主')
    expect(root.querySelector('.shuffle')).toBeNull()
    expect(root.querySelector('.offline')).toBeNull()
  })

  it('treats a non-array history response as an empty history', async () => {
    apiMock.history.mockResolvedValueOnce({ items: [{ id: 1, title: 'invalid shape' }] })

    await mount()

    expect(root.querySelector('.hist-hd')).toBeNull()
    expect(root.querySelector('.history-error')).toBeNull()
  })

  it('shows offline, current playback, and only grants queue actions to the matching server user', async () => {
    user.serverUserId = 3
    player.tvOnline = false
    player.nowPlaying = {
      queueId: 'now-1', orderedByNick: '小明',
      song: { id: 20, title: '正在唱', artist: '歌手甲', coverUrl: '/covers/current.jpg' }
    }
    player.queue = [
      { queueId: 'q-own', orderedBy: '3', orderedByNick: '小明', song: { id: 21, title: '我的歌', artist: '歌手甲' } },
      { queueId: 'q-other', orderedBy: 8, orderedByNick: '同名小明', song: { id: 22, title: '他人的歌', artist: '歌手乙' } }
    ]
    apiMock.roomHostStatus.mockResolvedValueOnce({ claimed: false, revision: 1 })
    await mount()

    expect(root.textContent).toContain('电视未连接，歌曲会先排队')
    expect(root.querySelector('.now').style.backgroundImage).toContain('current.jpg')
    expect(root.querySelector('.hd span').textContent).toBe('2 首待唱')
    expect(root.querySelectorAll('.qrow')[0].textContent).toContain('我的歌')
    expect(root.querySelectorAll('.qrow')[0].querySelectorAll('button')).toHaveLength(2)
    expect(root.querySelectorAll('.qrow')[1].textContent).toContain('他人的歌')
    expect(root.querySelectorAll('.qrow')[1].textContent).toContain('同名小明')
    expect(root.querySelectorAll('.qrow')[1].querySelector('.mine-tip').textContent).toBe('他人的歌')
  })

  it('preserves host state when legacy status and events omit a numeric revision', async () => {
    apiMock.roomHostStatus.mockResolvedValueOnce({ claimed: true, isHost: false, hostNickname: '旧版本房主' })
    await mount()

    expect(root.querySelector('.host-card strong').textContent).toBe('房主：旧版本房主')
    player.roomHost = { claimed: true, isHost: false, hostNickname: '旧版本新房主', revision: 'unknown' }
    await flushUi()

    expect(root.querySelector('.host-card strong').textContent).toBe('房主：旧版本新房主')
  })

  it('tops and removes an owned song after confirmation', async () => {
    user.serverUserId = 5
    player.queue = [{ queueId: 'queue-5', orderedBy: 5, song: { id: 50, title: '自己点的歌' } }]
    await mount()
    const row = root.querySelector('.qrow')

    row.querySelector('.chip.gold').click()
    await flushUi()
    expect(controlsMock.top).toHaveBeenCalledWith('queue-5')
    expect(toastMock).toHaveBeenCalledWith('已顶到下一首')

    row.querySelectorAll('.chip')[1].click()
    await flushUi()
    expect(confirmDialogMock).toHaveBeenCalledWith('将从队列删除《自己点的歌》。', { title: '删除已点歌曲', tone: 'warning' })
    expect(controlsMock.cancel).toHaveBeenCalledWith('queue-5')
    expect(toastMock).toHaveBeenLastCalledWith('已删除')
  })

  it('shows stable fallback messages for queue action failures without server messages', async () => {
    user.serverUserId = 5
    player.queue = [{ queueId: 'queue-5', orderedBy: 5, song: { id: 50, title: '自己的歌' } }]
    player.nowPlaying = { song: { id: 51, title: '当前歌曲' } }
    apiMock.history.mockResolvedValueOnce([
      { id: 60, title: '历史歌曲' },
      { id: 61, title: '另一首历史歌' }
    ])
    controlsMock.top.mockRejectedValueOnce({})
    controlsMock.cancel.mockRejectedValueOnce({})
    controlsMock.next.mockRejectedValueOnce({})
    controlsMock.order
      .mockRejectedValueOnce({ code: 'SONG_IN_QUEUE' })
      .mockRejectedValueOnce({})

    await mount()
    const row = root.querySelector('.qrow')
    row.querySelector('.chip.gold').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('操作失败')

    row.querySelectorAll('.chip')[1].click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('删除失败')

    root.querySelector('.cut').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('切歌失败')

    root.querySelector('.hist-hd').click()
    await flushUi()
    root.querySelector('.hist-chips button').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('已在队列中')

    root.querySelectorAll('.hist-chips button')[1].click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('点歌失败')
  })

  it('does not remove a song when delete confirmation is declined', async () => {
    user.serverUserId = 5
    player.queue = [{ queueId: 'queue-5', orderedBy: 5, song: { id: 50, title: '保留歌曲' } }]
    confirmDialogMock.mockResolvedValueOnce(false)
    await mount()
    root.querySelectorAll('.qrow .chip')[1].click()
    await flushUi()
    expect(controlsMock.cancel).not.toHaveBeenCalled()
  })

  it('confirms skip, skips on approval, and handles a failed skip', async () => {
    player.nowPlaying = { song: { id: 1, title: '当前歌曲' } }
    await mount()
    confirmDialogMock.mockResolvedValueOnce(false)
    root.querySelector('.cut').click()
    await flushUi()
    expect(controlsMock.next).not.toHaveBeenCalled()

    confirmDialogMock.mockResolvedValueOnce(true)
    controlsMock.next.mockRejectedValueOnce(new Error('切歌失败'))
    root.querySelector('.cut').click()
    await flushUi()
    expect(controlsMock.next).toHaveBeenCalledTimes(1)
    expect(toastMock).toHaveBeenCalledWith('切歌失败')
  })

  it('completes confirmed skip and shuffle actions and honors release cancellation', async () => {
    player.nowPlaying = { song: { id: 1, title: '当前歌曲' } }
    player.queue = [
      { queueId: 'q1', orderedBy: 1, song: { id: 2, title: '歌一' } },
      { queueId: 'q2', orderedBy: 2, song: { id: 3, title: '歌二' } }
    ]
    apiMock.roomHostStatus.mockResolvedValueOnce({ claimed: true, isHost: true, hostNickname: '房主', revision: 1 })
    await mount()

    root.querySelector('.cut').click()
    await flushUi()
    expect(controlsMock.next).toHaveBeenCalledTimes(1)
    expect(toastMock).toHaveBeenLastCalledWith('已切歌')

    confirmDialogMock.mockResolvedValueOnce(false)
    root.querySelector('.host-card .chip').click()
    await flushUi()
    expect(apiMock.releaseRoomHost).not.toHaveBeenCalled()

    root.querySelector('.shuffle').click()
    await flushUi()
    expect(controlsMock.shuffle).toHaveBeenCalledTimes(1)
    expect(toastMock).toHaveBeenLastCalledWith('队列已智能打散')
  })

  it('claims and releases host status with the current user token', async () => {
    apiMock.roomHostStatus.mockResolvedValueOnce({ claimed: false, revision: 1 })
    await mount()
    root.querySelector('.host-card .chip.gold').click()
    await flushUi()
    expect(apiMock.claimRoomHost).toHaveBeenCalledWith('queue-client')
    expect(root.querySelector('.host-card strong').textContent).toBe('你是房主')
    expect(toastMock).toHaveBeenCalledWith('你已成为房主')

    root.querySelector('.host-card .chip').click()
    await flushUi()
    expect(confirmDialogMock).toHaveBeenCalledWith('释放后其他人可认领房主身份。', { title: '释放房主身份' })
    expect(apiMock.releaseRoomHost).toHaveBeenCalledWith('queue-client')
    expect(root.querySelector('.host-card strong').textContent).toBe('尚未设置房主')
    expect(toastMock).toHaveBeenLastCalledWith('已释放房主身份')
  })

  it('refreshes host status after claim failure and preserves newer revisions over stale events', async () => {
    apiMock.roomHostStatus
      .mockResolvedValueOnce({ claimed: false, revision: 1 })
      .mockResolvedValueOnce({ claimed: true, isHost: false, hostNickname: '其他房主', revision: 4 })
    apiMock.claimRoomHost.mockRejectedValueOnce(new Error('认领失败'))
    await mount()
    root.querySelector('.host-card .chip.gold').click()
    await flushUi()
    expect(apiMock.roomHostStatus).toHaveBeenCalledTimes(2)
    expect(root.querySelector('.host-card strong').textContent).toBe('房主：其他房主')
    expect(toastMock).toHaveBeenCalledWith('认领失败')

    player.roomHost = { claimed: true, isHost: true, hostNickname: '旧房主', revision: 3 }
    await flushUi()
    expect(root.querySelector('.host-card strong').textContent).toBe('房主：其他房主')
  })

  it('keeps host state when status refresh also fails and reports a message-less release error', async () => {
    apiMock.roomHostStatus
      .mockResolvedValueOnce({ claimed: false, revision: 1 })
      .mockRejectedValueOnce({})
    apiMock.claimRoomHost.mockRejectedValueOnce({})
    apiMock.releaseRoomHost.mockRejectedValueOnce({})
    await mount()

    root.querySelector('.host-card .chip.gold').click()
    await flushUi()
    expect(root.querySelector('.host-card strong').textContent).toBe('尚未设置房主')
    expect(toastMock).toHaveBeenLastCalledWith('认领失败')

    player.roomHost = { claimed: true, isHost: true, hostNickname: '本机房主', revision: 2 }
    await flushUi()
    root.querySelector('.host-card .chip').click()
    await flushUi()

    expect(root.querySelector('.host-card strong').textContent).toBe('你是房主')
    expect(toastMock).toHaveBeenLastCalledWith('释放失败')
  })

  it('loads and expands history, reorders entries, and reloads on a new history revision', async () => {
    apiMock.history.mockResolvedValueOnce([{ id: 60, title: '听过的歌' }])
    await mount()
    expect(root.querySelector('.hist-hd').getAttribute('aria-expanded')).toBe('false')
    root.querySelector('.hist-hd').click()
    await flushUi()
    expect(root.querySelector('.hist-chips').textContent).toContain('听过的歌')
    root.querySelector('.hist-chips button').click()
    await flushUi()
    expect(controlsMock.order).toHaveBeenCalledWith(60)
    expect(toastMock).toHaveBeenCalledWith('已重新点歌')

    apiMock.history.mockResolvedValueOnce([{ id: 61, title: '更新历史' }])
    player.historyRevision += 1
    await flushUi()
    expect(root.querySelector('.hist-chips').textContent).toContain('更新历史')
  })

  it('shows a retryable history error and renders history after the retry', async () => {
    apiMock.history.mockRejectedValueOnce({})
    await mount()
    expect(root.textContent).toContain('已播历史读取失败：网络错误')

    apiMock.history.mockResolvedValueOnce([{ id: 63, title: '恢复历史' }])
    root.querySelector('.history-error button').click()
    await flushUi()
    expect(root.querySelector('.hist-hd')).not.toBeNull()
    root.querySelector('.hist-hd').click()
    await flushUi()
    expect(root.textContent).toContain('恢复历史')
  })

  it('ignores a late history response after a newer revision has loaded', async () => {
    let finishOldHistory
    apiMock.history.mockReturnValueOnce(new Promise(resolve => { finishOldHistory = resolve }))
    await mount()

    apiMock.history.mockResolvedValueOnce([{ id: 65, title: '新历史' }])
    player.historyRevision += 1
    await flushUi()
    finishOldHistory([{ id: 64, title: '迟到的旧历史' }])
    await flushUi()

    root.querySelector('.hist-hd').click()
    await flushUi()
    expect(root.textContent).toContain('新历史')
    expect(root.textContent).not.toContain('迟到的旧历史')
  })

  it('requires confirmation before shuffle and reports success or failure', async () => {
    player.queue = [
      { queueId: 'q1', orderedBy: 1, song: { id: 1, title: '歌一' } },
      { queueId: 'q2', orderedBy: 2, song: { id: 2, title: '歌二' } }
    ]
    await mount()
    confirmDialogMock.mockResolvedValueOnce(false)
    root.querySelector('.shuffle').click()
    await flushUi()
    expect(controlsMock.shuffle).not.toHaveBeenCalled()

    confirmDialogMock.mockResolvedValueOnce(true)
    controlsMock.shuffle
      .mockRejectedValueOnce(new Error('打散失败'))
      .mockRejectedValueOnce({})
    root.querySelector('.shuffle').click()
    await flushUi()
    expect(controlsMock.shuffle).toHaveBeenCalledTimes(1)
    expect(toastMock).toHaveBeenCalledWith('打散失败')

    root.querySelector('.shuffle').click()
    await flushUi()
    expect(controlsMock.shuffle).toHaveBeenCalledTimes(2)
    expect(toastMock).toHaveBeenCalledTimes(2)
    expect(toastMock).toHaveBeenLastCalledWith('打散失败')
  })

  async function mount() {
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
