// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { makeControlsMock, controlsMock, toastMock, confirmDialogMock } = vi.hoisted(() => ({
  makeControlsMock: vi.fn(),
  controlsMock: { play: vi.fn(), pause: vi.fn(), restart: vi.fn(), next: vi.fn() },
  toastMock: vi.fn(),
  confirmDialogMock: vi.fn()
}))

vi.mock('../api/client', () => ({ makeControls: makeControlsMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../composables/useDialog', () => ({ confirmDialog: confirmDialogMock }))

import LyricView from './LyricView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('LyricView', () => {
  let app
  let root
  let pinia
  let player

  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    for (const method of Object.values(controlsMock)) method.mockResolvedValue({})
    makeControlsMock.mockReturnValue(controlsMock)
    confirmDialogMock.mockResolvedValue(true)
    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore(pinia)
    user.clientToken = 'lyric-client'
    player = usePlayerStore(pinia)
    player.applyControlResponse = vi.fn()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
    vi.unstubAllGlobals()
  })

  it('shows the idle state without fetching when there is no current song', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await mount()
    expect(root.textContent).toContain('还没有歌曲播放')
    expect(root.querySelector('.topbar .t').textContent).toContain('暂无播放')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows an empty-lyrics state for a song that explicitly has no lyric file', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    player.nowPlaying = { queueId: 'q1', song: { id: 1, title: '无歌词歌曲', lyricType: 'none' } }
    await mount()
    expect(root.textContent).toContain('这首歌暂无歌词')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('loads LRC, highlights the current line and words, and updates the progress bar', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response('[00:01.00]第一句\n[00:02.00]<00:02.00>你<00:02.50>好'))
    vi.stubGlobal('fetch', fetchMock)
    player.nowPlaying = {
      queueId: 'q1',
      song: { id: 2, title: '歌词歌曲', artist: '歌手', durationMs: 10000, lyricType: 'LRC' }
    }
    player.positionMs = 2750
    await mount()

    expect(fetchMock).toHaveBeenCalledWith('/api/lyric/2', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(root.querySelectorAll('.ln')).toHaveLength(2)
    expect(root.querySelectorAll('.ln.cur')).toHaveLength(1)
    expect(root.querySelector('.ln.cur').textContent).toBe('你好')
    expect(root.querySelectorAll('.ln.cur .sung')).toHaveLength(2)
    expect(root.querySelector('.lines').style.transform).toBe('translateY(-44px)')
    expect(root.querySelector('.bar i').style.width).toBe('28%')
  })

  it('retries lyric failures and keeps HTTP errors distinct from empty lyrics', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValueOnce(response('[00:00.00]恢复的歌词'))
    vi.stubGlobal('fetch', fetchMock)
    player.nowPlaying = { queueId: 'q1', song: { id: 3, title: '故障歌曲', lyricType: 'LRC' } }
    await mount()
    expect(root.textContent).toContain('歌词加载失败：HTTP 503')

    root.querySelector('.retry').click()
    await flushUi()
    expect(root.textContent).toContain('恢复的歌词')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('uses fallback text for lyric and playback failures without messages', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error(''))
    vi.stubGlobal('fetch', fetchMock)
    player.nowPlaying = { queueId: 'q1', song: { id: 30, title: '缺少错误文本', lyricType: 'LRC' } }
    await mount()
    expect(root.querySelector('[role="alert"]').textContent).toContain('网络错误，请稍后重试')

    controlsMock.play.mockRejectedValueOnce(new Error(''))
    root.querySelector('.c.big').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('播放控制失败')

    controlsMock.restart.mockRejectedValueOnce(new Error(''))
    root.querySelector('.mini .c:first-child').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('重唱失败')

    controlsMock.next.mockRejectedValueOnce(new Error(''))
    root.querySelector('.mini .c:last-child').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('切歌失败')
  })

  it('aborts the old song request and renders only the newest song response', async () => {
    let finishOld
    const fetchMock = vi.fn()
      .mockReturnValueOnce(new Promise(resolve => { finishOld = resolve }))
      .mockResolvedValueOnce(response('[00:00.00]新歌歌词'))
    vi.stubGlobal('fetch', fetchMock)
    player.nowPlaying = { queueId: 'q1', song: { id: 4, title: '旧歌', lyricType: 'LRC' } }
    await mount()

    player.nowPlaying = { queueId: 'q2', song: { id: 5, title: '新歌', lyricType: 'LRC' } }
    await flushUi()
    finishOld(response('[00:00.00]旧歌歌词'))
    await flushUi()

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/lyric/4', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/lyric/5', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(root.textContent).toContain('新歌歌词')
    expect(root.textContent).not.toContain('旧歌歌词')
  })

  it('toggles controls, confirms skipping, and reports control failures', async () => {
    player.nowPlaying = { queueId: 'q1', song: { id: 6, title: '控制歌曲', lyricType: 'none' } }
    await mount()
    root.querySelector('.c.big').click()
    await flushUi()
    expect(controlsMock.play).toHaveBeenCalledOnce()

    player.state = 'playing'
    root.querySelector('.c.big').click()
    await flushUi()
    expect(controlsMock.pause).toHaveBeenCalledOnce()

    controlsMock.restart.mockRejectedValueOnce(new Error('重唱控制失败'))
    root.querySelector('.mini .c:first-child').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('重唱控制失败')

    confirmDialogMock.mockResolvedValueOnce(false)
    root.querySelector('.mini .c:last-child').click()
    await flushUi()
    expect(controlsMock.next).not.toHaveBeenCalled()

    confirmDialogMock.mockResolvedValueOnce(true)
    controlsMock.next.mockRejectedValueOnce(new Error('切歌控制失败'))
    root.querySelector('.mini .c:last-child').click()
    await flushUi()
    expect(controlsMock.next).toHaveBeenCalledOnce()
    expect(toastMock).toHaveBeenLastCalledWith('切歌控制失败')
  })

  it('runs successful restart and next controls without showing an error toast', async () => {
    player.nowPlaying = { queueId: 'q1', song: { id: 61, title: '正常控制歌曲', lyricType: 'none' } }
    await mount()

    root.querySelector('.mini .c:first-child').click()
    await flushUi()
    root.querySelector('.mini .c:last-child').click()
    await flushUi()

    expect(controlsMock.restart).toHaveBeenCalledOnce()
    expect(confirmDialogMock).toHaveBeenCalledWith('将切掉《正常控制歌曲》。', {
      title: '确认切歌', tone: 'warning'
    })
    expect(controlsMock.next).toHaveBeenCalledOnce()
    expect(toastMock).not.toHaveBeenCalled()
  })

  it('uses an empty title for the idle skip confirmation and stops when canceled', async () => {
    confirmDialogMock.mockResolvedValueOnce(false)
    await mount()

    root.querySelector('.mini .c:last-child').click()
    await flushUi()

    expect(confirmDialogMock).toHaveBeenCalledWith('将切掉《》。', {
      title: '确认切歌', tone: 'warning'
    })
    expect(controlsMock.next).not.toHaveBeenCalled()
  })

  async function mount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(LyricView)
    app.use(pinia)
    app.mount(root)
    await flushUi()
  }
})

function response(text) {
  return { ok: true, status: 200, text: async () => text }
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
