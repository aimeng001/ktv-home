// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock, toastMock, confirmDialogMock } = vi.hoisted(() => ({
  apiMock: { recentHistory: vi.fn(), repeatHistory: vi.fn() },
  toastMock: vi.fn(),
  confirmDialogMock: vi.fn()
}))

vi.mock('../api/client', () => ({ default: apiMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../composables/useDialog', () => ({ confirmDialog: confirmDialogMock }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav />' } }))

import RecentHistoryView from './RecentHistoryView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('RecentHistoryView', () => {
  let app
  let root
  let router
  let pinia
  let player

  beforeEach(async () => {
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.recentHistory.mockResolvedValue([])
    apiMock.repeatHistory.mockResolvedValue({ position: 4 })
    confirmDialogMock.mockResolvedValue(true)

    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore(pinia)
    user.clientToken = 'history-client'
    player = usePlayerStore(pinia)

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/recent', name: 'recent-history', component: RecentHistoryView }
      ]
    })
    await router.push('/recent')
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
    vi.useRealTimers()
  })

  it('loads the all-history empty state with the current client token', () => {
    expect(apiMock.recentHistory).toHaveBeenCalledWith('history-client', false)
    expect(root.textContent).toContain('今晚还没有演唱记录')
  })

  it('loads the mine-only empty state and sends the selected filter', async () => {
    clickText('.filters button', '我唱的')
    await flushUi()
    expect(apiMock.recentHistory).toHaveBeenLastCalledWith('history-client', true)
    expect(root.textContent).toContain('你还没有唱过歌曲')

    clickText('.filters button', '全部')
    await flushUi()
    expect(apiMock.recentHistory).toHaveBeenLastCalledWith('history-client', false)
    expect(root.textContent).toContain('今晚还没有演唱记录')
  })

  it('shows a retryable history error and renders rows after recovery', async () => {
    apiMock.recentHistory.mockRejectedValueOnce(new Error('历史服务不可用'))
    await remount()
    expect(root.textContent).toContain('演唱记录读取失败：历史服务不可用')

    apiMock.recentHistory.mockResolvedValueOnce([history(3, '回来的歌', Date.now(), '')])
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.textContent).toContain('回来的歌')
    expect(root.querySelector('.cover img')).toBeNull()
    expect(root.querySelector('.cover').textContent).toContain('🎵')
  })

  it('shows fallback text for history errors and safely formats missing timestamps', async () => {
    apiMock.recentHistory.mockRejectedValueOnce(new Error(''))
    await remount()
    expect(root.querySelector('.error-tip').textContent).toContain('网络错误，请稍后重试')

    apiMock.recentHistory.mockResolvedValueOnce([history(30, '时间未知的歌', 0, '')])
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.querySelector('.info em').textContent).toBe('小明 · ')
  })

  it('renders cover images, artist metadata, requester and both same-day and older dates', async () => {
    const today = new Date(Date.now())
    const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000)
    apiMock.recentHistory.mockResolvedValueOnce([
      history(4, '今天唱的歌', today, '/covers/today.jpg'),
      history(5, '昨天唱的歌', yesterday, '')
    ])
    await remount()

    expect(root.querySelectorAll('.row')).toHaveLength(2)
    expect(root.querySelector('.cover img').getAttribute('src')).toBe('/covers/today.jpg')
    expect(root.textContent).toContain('小歌手')
    expect(root.textContent).toContain('小明')
    expect(root.querySelectorAll('.info em')[0].textContent).toContain(today.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))
    expect(root.querySelectorAll('.info em')[1].textContent).toContain(yesterday.toLocaleDateString([], { month: 'numeric', day: 'numeric' }))
  })

  it('re-adds history successfully and prevents a second click while the request is pending', async () => {
    let finishRepeat
    apiMock.recentHistory.mockResolvedValueOnce([history(8, '待重唱', Date.now(), '')])
    apiMock.repeatHistory.mockReturnValueOnce(new Promise(resolve => { finishRepeat = resolve }))
    await remount()

    const repeat = root.querySelector('.row button')
    repeat.click()
    await flushUi()
    expect(repeat.disabled).toBe(true)
    expect(repeat.textContent).toBe('加入中')
    expect(apiMock.repeatHistory).toHaveBeenCalledWith(8, 'history-client')

    finishRepeat({ position: 2 })
    await flushUi()
    expect(repeat.disabled).toBe(false)
    expect(toastMock).toHaveBeenCalledWith('已加入队列，第 2 位')
  })

  it('asks before force-adding an already queued song and retries with the force flag', async () => {
    apiMock.recentHistory.mockResolvedValueOnce([history(9, '队列里的歌', Date.now(), '')])
    apiMock.repeatHistory
      .mockRejectedValueOnce(Object.assign(new Error('歌曲已在队列中'), { code: 'SONG_IN_QUEUE' }))
      .mockResolvedValueOnce({ position: 1 })
    await remount()
    root.querySelector('.row button').click()
    await flushUi()

    expect(confirmDialogMock).toHaveBeenCalledWith('歌曲已在队列中', { title: '歌曲仍在队列中' })
    expect(apiMock.repeatHistory).toHaveBeenNthCalledWith(2, 9, 'history-client', true)
    expect(toastMock).toHaveBeenCalledWith('已重复加入，第 1 位')
  })

  it('does not force-add when confirmation is declined and handles retry errors', async () => {
    apiMock.recentHistory.mockResolvedValueOnce([history(10, '不重复点歌', Date.now(), '')])
    apiMock.repeatHistory.mockRejectedValueOnce(Object.assign(new Error('已排队'), { code: 'SONG_IN_QUEUE' }))
    confirmDialogMock.mockResolvedValueOnce(false)
    await remount()
    root.querySelector('.row button').click()
    await flushUi()
    expect(apiMock.repeatHistory).toHaveBeenCalledTimes(1)

    apiMock.repeatHistory.mockRejectedValueOnce(Object.assign(new Error('已排队'), { code: 'SONG_IN_QUEUE' }))
    confirmDialogMock.mockResolvedValueOnce(true)
    apiMock.repeatHistory.mockRejectedValueOnce(new Error('强制重唱失败'))
    root.querySelector('.row button').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('强制重唱失败')
  })

  it('reports ordinary repeat failures and reloads when the history revision changes', async () => {
    apiMock.recentHistory.mockResolvedValueOnce([history(11, '重试失败歌', Date.now(), '')])
    await remount()
    apiMock.repeatHistory.mockRejectedValueOnce(new Error('再唱失败'))
    root.querySelector('.row button').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('再唱失败')

    apiMock.recentHistory.mockResolvedValueOnce([history(12, '快照更新歌', Date.now(), '')])
    player.historyRevision += 1
    await flushUi()
    expect(apiMock.recentHistory).toHaveBeenLastCalledWith('history-client', false)
    expect(root.textContent).toContain('快照更新歌')
  })

  it('uses a fallback message when repeat and force-repeat failures have no message', async () => {
    apiMock.recentHistory.mockResolvedValueOnce([history(13, '空错误信息', Date.now(), '')])
    await remount()

    apiMock.repeatHistory.mockRejectedValueOnce(new Error(''))
    root.querySelector('.row button').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('再唱失败')

    apiMock.repeatHistory
      .mockRejectedValueOnce(Object.assign(new Error(''), { code: 'SONG_IN_QUEUE' }))
      .mockRejectedValueOnce(new Error(''))
    confirmDialogMock.mockResolvedValueOnce(true)
    root.querySelector('.row button').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('再唱失败')
    expect(root.querySelector('.row button').disabled).toBe(false)
  })

  async function remount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(router)
    app.use(pinia)
    app.mount(root)
    await flushUi()
  }
})

function history(historyId, title, playedAt, coverUrl) {
  return {
    historyId,
    playedByNick: '小明',
    playedAt,
    song: { id: historyId + 100, title, artist: '小歌手', coverUrl }
  }
}

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
