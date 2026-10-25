// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { makeControlsMock, controlsMock, toastMock, confirmDialogMock } = vi.hoisted(() => ({
  makeControlsMock: vi.fn(),
  controlsMock: {
    play: vi.fn(), pause: vi.fn(), restart: vi.fn(), next: vi.fn(), setVolume: vi.fn(),
    setVocal: vi.fn(), swapVocalTracks: vi.fn(), effect: vi.fn()
  },
  toastMock: vi.fn(),
  confirmDialogMock: vi.fn()
}))

vi.mock('../api/client', () => ({ makeControls: makeControlsMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../composables/useDialog', () => ({ confirmDialog: confirmDialogMock }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav />' } }))

import RemoteView from './RemoteView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('RemoteView', () => {
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
    user.clientToken = 'remote-client'
    player = usePlayerStore(pinia)
    player.applyControlResponse = vi.fn()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('shows unknown television, idle playback, and disables vocal controls without KTV audio', async () => {
    await mount()
    expect(root.querySelector('.online').textContent).toContain('电视状态未知')
    expect(root.querySelector('.online').classList.contains('unknown')).toBe(true)
    expect(root.querySelector('.now .t').textContent).toBe('暂无播放')
    expect(root.querySelector('.bar i').style.width).toBe('0%')
    expect(root.querySelector('.bigctl').getAttribute('aria-label')).toBe('播放')
    expect([...root.querySelectorAll('.seg button')].every(button => button.disabled)).toBe(true)
    expect(root.querySelector('.track-fix')).toBeNull()
  })

  it('renders current track progress, cover, television status, and dual-track controls', async () => {
    player.tvOnline = false
    player.nowPlaying = { song: { id: 5, title: '当前歌', artist: '歌手', coverUrl: '/covers/current.jpg', durationMs: 200000 } }
    player.positionMs = 50000
    player.audioLayout = { layout: 'DUAL_TRACK' }
    await mount()

    expect(root.querySelector('.online').textContent).toContain('电视离线')
    expect(root.querySelector('.online').classList.contains('offline')).toBe(true)
    expect(root.querySelector('.now .t').textContent).toContain('当前歌')
    expect(root.querySelector('.now .s').textContent).toContain('歌手')
    expect(root.querySelector('.cover').style.backgroundImage).toContain('current.jpg')
    expect(root.querySelector('.bar i').style.width).toBe('25%')
    expect([...root.querySelectorAll('.seg button')].every(button => !button.disabled)).toBe(true)
    expect(root.querySelector('.track-fix')).not.toBeNull()
  })

  it('toggles play and pause based on the mirrored player state', async () => {
    await mount()
    root.querySelector('.bigctl').click()
    await flushUi()
    expect(controlsMock.play).toHaveBeenCalledOnce()

    player.state = 'playing'
    await flushUi()
    expect(root.querySelector('.bigctl').getAttribute('aria-label')).toBe('暂停')
    root.querySelector('.bigctl').click()
    await flushUi()
    expect(controlsMock.pause).toHaveBeenCalledOnce()
  })

  it('reports playback and restart failures through the user toast', async () => {
    await mount()
    controlsMock.play.mockRejectedValueOnce(new Error('播放失败'))
    root.querySelector('.bigctl').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('播放失败')

    controlsMock.restart.mockRejectedValueOnce({})
    root.querySelector('.ctl').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('操作失败')
  })

  it('uses safe fallback messages for remote actions that fail without an error message', async () => {
    player.nowPlaying = { song: { id: 5, title: '远程控制歌', hasVocalTrack: true } }
    await mount()

    controlsMock.play.mockRejectedValueOnce({})
    root.querySelector('.bigctl').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('操作失败')

    controlsMock.restart.mockResolvedValueOnce({})
    root.querySelector('.ctl').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('重唱：从头播放')

    controlsMock.next.mockRejectedValueOnce({})
    clickText('.ctl', '切歌')
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('切歌失败')

    controlsMock.setVolume.mockRejectedValueOnce({})
    root.querySelector('[aria-label="提高音量"]').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('调音量失败')

    controlsMock.setVocal.mockRejectedValueOnce({})
    clickText('.seg button', '伴唱')
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('切换失败')

    controlsMock.swapVocalTracks.mockRejectedValueOnce({})
    root.querySelector('.track-fix').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('纠正失败')
  })

  it('requires confirmation to skip and handles successful or failed next actions', async () => {
    player.nowPlaying = { song: { title: '切歌目标' } }
    await mount()
    confirmDialogMock.mockResolvedValueOnce(false)
    clickText('button.ctl', '切歌')
    await flushUi()
    expect(controlsMock.next).not.toHaveBeenCalled()

    confirmDialogMock.mockResolvedValueOnce(true)
    root.querySelector('.ctl:last-child').click()
    await flushUi()
    expect(controlsMock.next).toHaveBeenCalledOnce()
    expect(toastMock).toHaveBeenCalledWith('已切歌')

    confirmDialogMock.mockResolvedValueOnce(true)
    controlsMock.next.mockRejectedValueOnce(new Error('无法切歌'))
    root.querySelector('.ctl:last-child').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('无法切歌')
  })

  it('uses an empty title for a missing current song and guards synthetic clicks on disabled vocal controls', async () => {
    await mount()
    confirmDialogMock.mockResolvedValueOnce(false)
    clickText('button.ctl', '切歌')
    await flushUi()
    expect(confirmDialogMock).toHaveBeenCalledWith('将切掉《》。', expect.objectContaining({ title: '确认切歌' }))
    expect(controlsMock.next).not.toHaveBeenCalled()

    const vocalButtons = [...root.querySelectorAll('.seg button')]
    expect(vocalButtons).toHaveLength(2)
    expect(vocalButtons.every(button => button.disabled)).toBe(true)
    vocalButtons[0].dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushUi()
    expect(controlsMock.setVocal).not.toHaveBeenCalled()
    expect(toastMock).toHaveBeenLastCalledWith('该版本无伴唱音轨')
  })

  it('adjusts volume in five-point steps, clamps boundaries, and reflects external volume changes', async () => {
    player.volume = 98
    player.tvOnline = true
    await mount()
    expect(root.querySelector('.online').textContent).toContain('电视在线')
    expect(root.querySelector('.section-label span').textContent).toBe('98')

    root.querySelector('[aria-label="提高音量"]').click()
    await flushUi()
    expect(controlsMock.setVolume).toHaveBeenLastCalledWith(100)
    expect(root.querySelector('.section-label span').textContent).toBe('100')

    root.querySelector('[aria-label="降低音量"]').click()
    await flushUi()
    expect(controlsMock.setVolume).toHaveBeenLastCalledWith(95)

    player.volume = 30
    await flushUi()
    expect(root.querySelector('.section-label span').textContent).toBe('30')
    const slider = root.querySelector('input[type="range"]')
    slider.value = '45'
    slider.dispatchEvent(new Event('input', { bubbles: true }))
    slider.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(controlsMock.setVolume).toHaveBeenLastCalledWith(45)
  })

  it('switches both vocal modes and confirms vocal-track correction', async () => {
    player.nowPlaying = { song: { id: 41, title: 'KTV 歌', hasVocalTrack: true } }
    await mount()
    clickText('.seg button', '原唱')
    await flushUi()
    expect(controlsMock.setVocal).toHaveBeenCalledWith('original')
    expect(toastMock).toHaveBeenCalledWith('已切原唱')

    clickText('.seg button', '伴唱')
    await flushUi()
    expect(controlsMock.setVocal).toHaveBeenCalledWith('accompaniment')
    expect(toastMock).toHaveBeenLastCalledWith('已切伴唱')

    confirmDialogMock.mockResolvedValueOnce(false)
    root.querySelector('.track-fix').click()
    await flushUi()
    expect(controlsMock.swapVocalTracks).not.toHaveBeenCalled()

    confirmDialogMock.mockResolvedValueOnce(true)
    root.querySelector('.track-fix').click()
    await flushUi()
    expect(controlsMock.swapVocalTracks).toHaveBeenCalledOnce()
    expect(toastMock).toHaveBeenLastCalledWith('已纠正，本歌曲下次播放继续生效')
  })

  it('uses a safe title fallback when a vocal-capable song has no title', async () => {
    player.nowPlaying = { song: { id: 42, title: '', hasVocalTrack: true } }
    confirmDialogMock.mockResolvedValueOnce(false)
    await mount()

    root.querySelector('.track-fix').click()
    await flushUi()

    expect(confirmDialogMock).toHaveBeenCalledWith('将永久更正《》的原唱和伴唱标记。', {
      title: '纠正音轨标记'
    })
    expect(controlsMock.swapVocalTracks).not.toHaveBeenCalled()
  })

  it('rechecks vocal support if the track-correction button becomes stale before render', async () => {
    player.nowPlaying = { song: { id: 43, title: '切换中的歌曲', hasVocalTrack: true } }
    await mount()
    const correctionButton = root.querySelector('.track-fix')

    player.nowPlaying = { song: { id: 43, title: '切换中的歌曲', hasVocalTrack: false } }
    correctionButton.click()
    await flushUi()

    expect(confirmDialogMock).not.toHaveBeenCalled()
    expect(controlsMock.swapVocalTracks).not.toHaveBeenCalled()
    expect(toastMock).toHaveBeenLastCalledWith('该歌曲没有原唱/伴唱语义')
    expect(root.querySelector('.track-fix')).toBeNull()
  })

  it('supports dual-channel layouts and sends every ambience effect', async () => {
    player.nowPlaying = { song: { id: 42, title: '双声道歌', hasVocalTrack: false } }
    player.audioLayout = { layout: 'DUAL_CHANNEL' }
    await mount()
    expect(root.querySelector('.track-fix')).not.toBeNull()

    const labels = ['鼓掌', '欢呼', '倒彩', '干杯']
    const ids = ['clap', 'cheer', 'boo', 'toast']
    for (let index = 0; index < labels.length; index += 1) {
      clickText('.fx', labels[index])
      await flushUi()
      expect(controlsMock.effect).toHaveBeenLastCalledWith(ids[index])
      expect(toastMock).toHaveBeenLastCalledWith(`已发送：${labels[index]}`)
    }

    controlsMock.effect.mockRejectedValueOnce({})
    clickText('.fx', '鼓掌')
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('发送失败')
  })

  async function mount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(RemoteView)
    app.use(pinia)
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
