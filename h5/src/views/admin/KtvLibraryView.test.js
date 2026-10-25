// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, nextTick, h } from 'vue'

const { apiMock, alertMock, confirmMock, routerMock } = vi.hoisted(() => ({
  apiMock: {
    adminSourceLibrary: vi.fn(), adminSongs: vi.fn(), adminEditSong: vi.fn(),
    adminDeleteSong: vi.fn(), adminDeleteSongs: vi.fn(), adminAiPlaylists: vi.fn(), adminAiAddPlaylistSong: vi.fn()
  },
  alertMock: vi.fn(), confirmMock: vi.fn(), routerMock: { push: vi.fn() }
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))
vi.mock('../../composables/useDialog', () => ({ alertDialog: alertMock, confirmDialog: confirmMock }))
vi.mock('./AdminLayout.vue', () => ({ default: defineComponent({ template: '<div class="admin-layout"><slot /></div>' }) }))
vi.mock('../../components/admin/AiTaskPanel.vue', () => ({ default: defineComponent({ emits: ['close', 'applied'], template: '<div class="ai-task-panel"><button @click="$emit(\'close\')">close panel</button></div>' }) }))
vi.mock('./AudioLayoutEditor.vue', () => ({ default: defineComponent({
  props: ['song'],
  emits: ['updated'],
  setup(props, { emit }) {
    return () => h('button', {
      class: 'audio-layout-stub',
      onClick: () => emit('updated', { layout: 'NORMAL_STEREO' })
    }, String(props.song.id))
  }
}) }))

import KtvLibraryView from './KtvLibraryView.vue'

describe('KtvLibraryView', () => {
  let app
  let root
  let song

  beforeEach(() => {
    vi.clearAllMocks()
    song = { id: 7, title: '测试歌曲', artist: '测试歌手', language: '国语', tags: ['流行'], mediaType: 'KTV_VIDEO', fileId: 70, filePath: '/music/test.mp4', audioLayout: { layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 } }
    apiMock.adminSourceLibrary.mockResolvedValue({ libraryMode: 'MANAGED' })
    apiMock.adminSongs.mockResolvedValue({ content: [song], total: 1, totalPages: 1 })
    apiMock.adminEditSong.mockImplementation(async (_id, payload) => ({ ...song, ...payload }))
    apiMock.adminAiPlaylists.mockResolvedValue([{ id: 9, name: '通勤歌单', theme: '流行', songCount: 3 }])
    apiMock.adminAiAddPlaylistSong.mockResolvedValue({ songCount: 4 })
    confirmMock.mockResolvedValue(true)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('hides deletion controls for external read-only or unconfirmed library modes', async () => {
    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'EXTERNAL_READ_ONLY' })
    await mount()
    expect(root.textContent).toContain('外部只读曲库不能在 Home KTV 中删除源歌曲')
    expect(root.querySelector('.danger')).toBeNull()
    expect(root.querySelector('.danger-text')).toBeNull()

    apiMock.adminSourceLibrary.mockRejectedValueOnce(new Error('状态读取失败'))
    await mount()
    expect(root.textContent).toContain('曲库模式尚未确认')
    expect(root.querySelector('.danger')).toBeNull()
  })

  it('keeps deletion hidden when the server returns an empty library-mode value', async () => {
    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: '' })
    await mount()

    expect(root.textContent).toContain('曲库模式尚未确认')
    expect(root.querySelector('.danger')).toBeNull()
    expect(root.querySelector('.danger-text')).toBeNull()
  })

  it('ignores a stale library-mode failure after a newer filter load succeeds', async () => {
    let rejectInitialMode
    apiMock.adminSourceLibrary.mockImplementationOnce(() => new Promise((_, reject) => { rejectInitialMode = reject }))
    await mount()

    const type = root.querySelectorAll('.filter-panel select')[0]
    type.value = 'MV'
    type.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(root.querySelector('.danger')).not.toBeNull()

    rejectInitialMode(new Error('stale mode response'))
    await flushUi()
    expect(root.querySelector('.danger')).not.toBeNull()
    expect(root.textContent).not.toContain('曲库模式尚未确认')
  })

  it('edits metadata and submits normalized title, artist, language, and tags', async () => {
    await mount()
    root.querySelector('.edit-link').click()
    await nextTick()
    const inputs = root.querySelectorAll('.song-editor-form input')
    inputs[0].value = '  新歌名  '
    inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1].value = ' 新歌手 '
    inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[2].value = ' 粤语 '
    inputs[2].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[3].value = '流行，经典 pop'
    inputs[3].dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.song-editor-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()

    expect(apiMock.adminEditSong).toHaveBeenCalledWith(7, { title: '新歌名', artist: '新歌手', language: '粤语', tags: ['流行', '经典', 'pop'] })
    expect(root.querySelector('.song-editor-modal')).toBeNull()
  })

  it('initializes the editor with blank defaults for missing song metadata', async () => {
    song = { ...song, title: null, artist: null, language: null, tags: null }
    apiMock.adminSongs.mockResolvedValueOnce({ content: [song], total: 1, totalPages: 1 })
    await mount()
    root.querySelector('.edit-link').click()
    await nextTick()

    const inputs = root.querySelectorAll('.song-editor-form input')
    expect([...inputs].map(input => input.value)).toEqual(['', '', '', ''])
    root.querySelector('.song-editor-form .secondary').click()
    await nextTick()
    expect(root.querySelector('.song-editor-modal')).toBeNull()
  })

  it('requires explicit confirmation before deleting selected Managed songs', async () => {
    await mount()
    const row = root.querySelector('tbody tr')
    row.querySelector('input[type="checkbox"]').click()
    await nextTick()
    root.querySelector('.toolbar-actions .danger').click()
    await flushUi()

    expect(confirmMock).toHaveBeenCalledWith('将删除 1 首歌曲及其 /music 中的实际文件。', { title: '批量删除 KTV 歌曲', tone: 'warning' })
    expect(apiMock.adminDeleteSongs).toHaveBeenCalledWith([7])
    expect(apiMock.adminSongs).toHaveBeenCalledTimes(2)
  })

  it('cancels deletion when confirmation is declined and adds a song to a selected playlist', async () => {
    await mount()
    confirmMock.mockResolvedValueOnce(false)
    root.querySelector('.danger-text').click()
    await flushUi()
    expect(apiMock.adminDeleteSong).not.toHaveBeenCalled()

    root.querySelector('.playlist-link').click()
    await flushUi()
    expect(root.textContent).toContain('通勤歌单')
    root.querySelector('.playlist-picker-item').click()
    await flushUi()
    expect(apiMock.adminAiAddPlaylistSong).toHaveBeenCalledWith(9, 7)
    expect(alertMock).toHaveBeenCalledWith('《测试歌曲》已加入歌单“通勤歌单”')
    expect(root.querySelector('.playlist-picker-modal')).toBeNull()
  })

  it('shows a failed list load, uses empty response defaults, and recovers from retry', async () => {
    apiMock.adminSourceLibrary.mockRejectedValueOnce(new Error('mode offline'))
    apiMock.adminSongs.mockRejectedValueOnce(new Error('songs offline'))
    await mount()

    expect(root.querySelector('.error-notice').textContent).toContain('songs offline')
    expect(root.querySelector('.danger')).toBeNull()

    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'MANAGED' })
    apiMock.adminSongs.mockResolvedValueOnce({})
    root.querySelector('.error-notice .text-btn').click()
    await flushUi()

    expect(root.querySelector('.error-notice')).toBeNull()
    expect(root.querySelector('.empty').textContent).toContain('暂无符合条件')
    expect(root.querySelector('.toolbar-actions .danger')).not.toBeNull()
    expect(root.querySelector('.pager').textContent).toContain('第 1 / 1 页')
  })

  it('keeps an empty result on page one and disables forward navigation', async () => {
    apiMock.adminSongs.mockResolvedValueOnce({ content: [], total: 0, totalPages: 0 })
    await mount()

    expect(root.querySelector('.pager span').textContent).toContain('第 1 / 1 页')
    expect(root.querySelector('.pager button:last-child').disabled).toBe(true)
    expect(apiMock.adminSongs).toHaveBeenCalledTimes(1)
  })

  it('shows the fallback message when the current song-page failure has no message', async () => {
    apiMock.adminSongs.mockRejectedValueOnce({})
    await mount()

    expect(root.querySelector('.error-notice').textContent).toContain('曲库加载失败')
    expect(root.querySelector('.empty').textContent).toContain('暂无符合条件')
  })

  it('ignores an aborted song-page response after a newer filter request wins', async () => {
    let finishInitial
    apiMock.adminSongs
      .mockImplementationOnce((_params, options) => new Promise(resolve => { finishInitial = resolve; apiMock.initialSongPageSignal = options.signal }))
      .mockImplementationOnce(async params => ({
        content: [{ ...song, id: 88, title: `新结果 ${params.type}` }], total: 1, totalPages: 1
      }))
    await mount()
    const initialSignal = apiMock.initialSongPageSignal

    const type = root.querySelectorAll('.filter-panel select')[0]
    type.value = 'MV'
    type.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(initialSignal.aborted).toBe(true)
    expect(root.textContent).toContain('新结果 MV')

    finishInitial({ content: [{ ...song, id: 77, title: '过时结果' }], total: 1, totalPages: 1 })
    await flushUi()
    expect(root.textContent).toContain('新结果 MV')
    expect(root.textContent).not.toContain('过时结果')
  })

  it('ignores a stale aborted page failure after a newer filter request wins', async () => {
    let rejectInitial
    apiMock.adminSongs
      .mockImplementationOnce(() => new Promise((_, reject) => { rejectInitial = reject }))
      .mockImplementationOnce(async params => ({
        content: [{ ...song, id: 88, title: `新结果 ${params.type}` }], total: 1, totalPages: 1
      }))
    await mount()

    const type = root.querySelectorAll('.filter-panel select')[0]
    type.value = 'MV'
    type.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(root.textContent).toContain('新结果 MV')

    rejectInitial(new DOMException('request cancelled', 'AbortError'))
    await flushUi()

    expect(root.querySelector('.error-notice')).toBeNull()
    expect(root.textContent).toContain('新结果 MV')
  })

  it('does not show an error notice for an aborted page request', async () => {
    apiMock.adminSongs.mockRejectedValueOnce(new DOMException('request cancelled', 'AbortError'))
    await mount()

    expect(root.querySelector('.error-notice')).toBeNull()
    expect(root.querySelector('.empty').textContent).toContain('暂无符合条件')
  })

  it('handles playlist load and add failures and keeps the picker open while adding', async () => {
    await mount()
    apiMock.adminAiPlaylists.mockRejectedValueOnce(new Error('playlist service offline'))
    root.querySelector('.playlist-link').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('playlist service offline')
    expect(root.querySelector('.match-empty').textContent).toContain('暂无已有歌单')
    root.querySelector('.playlist-picker-modal .icon-button').click()
    await nextTick()
    expect(root.querySelector('.playlist-picker-modal')).toBeNull()

    const playlist = { id: 9, name: '通勤歌单', songCount: 3 }
    apiMock.adminAiPlaylists.mockResolvedValueOnce([playlist])
    root.querySelector('.playlist-link').click()
    await flushUi()

    apiMock.adminAiAddPlaylistSong.mockRejectedValueOnce(new Error('add offline'))
    root.querySelector('.playlist-picker-item').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('add offline')
    expect(root.querySelector('.playlist-picker-modal')).not.toBeNull()

    let finishAdd
    apiMock.adminAiAddPlaylistSong.mockReturnValueOnce(new Promise(resolve => { finishAdd = resolve }))
    root.querySelector('.playlist-picker-item').click()
    await nextTick()

    root.querySelector('.playlist-picker-modal .icon-button').click()
    await nextTick()
    expect(root.querySelector('.playlist-picker-modal')).not.toBeNull()
    expect(root.querySelector('.playlist-picker-item').disabled).toBe(true)

    finishAdd({ songs: [{ id: 7 }, { id: 8 }] })
    await flushUi()
    expect(playlist.songCount).toBe(2)
    expect(root.querySelector('.playlist-picker-modal')).toBeNull()
  })

  it('uses picker fallbacks and increments the displayed count when add returns no count', async () => {
    song = { ...song, artist: null }
    apiMock.adminSongs.mockResolvedValueOnce({ content: [song], total: 1, totalPages: 1 })
    const playlist = { id: 9, name: '缺少主题的歌单', theme: null, songCount: 3 }
    apiMock.adminAiPlaylists.mockResolvedValueOnce([playlist])
    apiMock.adminAiAddPlaylistSong.mockResolvedValueOnce({})
    await mount()
    root.querySelector('.playlist-link').click()
    await flushUi()

    expect(root.querySelector('.playlist-picker-modal .match-head').textContent).toContain('未知歌手')
    expect(root.querySelector('.playlist-picker-item').textContent).toContain('未设置主题 · 3 / 100 首')
    root.querySelector('.playlist-picker-item').click()
    await flushUi()

    expect(playlist.songCount).toBe(4)
    expect(alertMock).toHaveBeenCalledWith('《测试歌曲》已加入歌单“缺少主题的歌单”')
  })

  it('increments an empty playlist count from zero when the add response is null', async () => {
    const playlist = { id: 9, name: '空歌单', songCount: 0 }
    apiMock.adminAiPlaylists.mockResolvedValueOnce([playlist])
    apiMock.adminAiAddPlaylistSong.mockResolvedValueOnce(null)
    await mount()
    root.querySelector('.playlist-link').click()
    await flushUi()

    expect(root.querySelector('.playlist-picker-item').textContent).toContain('0 / 100 首')
    root.querySelector('.playlist-picker-item').click()
    await flushUi()

    expect(playlist.songCount).toBe(1)
    expect(apiMock.adminAiAddPlaylistSong).toHaveBeenCalledWith(9, 7)
  })

  it('uses a returned playlist song count when the add response has no song list', async () => {
    const playlist = { id: 11, name: '服务端计数歌单', songCount: 2 }
    apiMock.adminAiPlaylists.mockResolvedValueOnce([playlist])
    apiMock.adminAiAddPlaylistSong.mockResolvedValueOnce({ songCount: 8 })
    await mount()

    root.querySelector('.playlist-link').click()
    await flushUi()
    root.querySelector('.playlist-picker-item').click()
    await flushUi()

    expect(playlist.songCount).toBe(8)
    expect(apiMock.adminAiAddPlaylistSong).toHaveBeenCalledWith(11, 7)
  })

  it('shows a fallback message and empty state when playlist loading rejects without a message', async () => {
    apiMock.adminAiPlaylists.mockRejectedValueOnce({})
    await mount()
    root.querySelector('.playlist-link').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('歌单加载失败')
    expect(root.querySelector('.match-empty').textContent).toContain('暂无已有歌单')
    expect(root.querySelector('.playlist-picker-modal .match-loading')).toBeNull()

    root.querySelector('.playlist-picker-modal .icon-button').click()
    await nextTick()
    apiMock.adminAiPlaylists.mockResolvedValueOnce([{ id: 9, name: '正常歌单' }])
    root.querySelector('.playlist-link').click()
    await flushUi()
    apiMock.adminAiAddPlaylistSong.mockRejectedValueOnce({})
    root.querySelector('.playlist-picker-item').click()
    await flushUi()

    expect(alertMock).toHaveBeenLastCalledWith('加入歌单失败')
    expect(root.querySelector('.playlist-picker-modal')).not.toBeNull()
  })

  it('renders each audio layout and selects or clears the current page for scraping', async () => {
    const channelSong = {
      ...song,
      id: 8,
      title: '左右声道',
      audioLayout: { layout: 'DUAL_CHANNEL' }
    }
    const stereoSong = {
      ...song,
      id: 9,
      title: '普通立体声',
      audioLayout: { layout: 'NORMAL_STEREO' }
    }
    apiMock.adminSongs.mockResolvedValueOnce({
      content: [song, channelSong, stereoSong], total: 3, totalPages: 1
    })
    await mount()

    const layoutDetails = [...root.querySelectorAll('tbody tr td:nth-child(8) small')]
    expect(layoutDetails.map(node => node.textContent)).toEqual([
      'Original Track 0 / Accompaniment Track 1',
      'LEFT / RIGHT',
      '无原唱/伴唱切换'
    ])

    const selectAll = root.querySelector('thead input[type="checkbox"]')
    selectAll.click()
    await nextTick()
    expect(root.querySelector('.scrape-batch').textContent).toContain('刮削已选（3）')
    root.querySelector('.scrape-batch').click()
    expect(routerMock.push).toHaveBeenCalledWith({
      name: 'admin-metadata-scrape',
      query: { songIds: '7,8,9' }
    })

    selectAll.click()
    await nextTick()
    expect(root.querySelector('.scrape-batch').disabled).toBe(true)
  })

  it('applies audio-layout editor updates to the displayed song', async () => {
    await mount()

    root.querySelector('.audio-layout-stub').click()
    await nextTick()

    expect(song.audioLayout).toEqual({ layout: 'NORMAL_STEREO' })
    expect(root.querySelector('tbody tr td:nth-child(8) small').textContent).toBe('无原唱/伴唱切换')
  })

  it('resets all filters and returns to the first page', async () => {
    apiMock.adminSongs.mockImplementation(async () => ({ content: [song], total: 21, totalPages: 2 }))
    await mount()

    const keyword = root.querySelector('.filter-panel input')
    keyword.value = '周杰伦'
    keyword.dispatchEvent(new Event('input', { bubbles: true }))
    keyword.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    const selects = root.querySelectorAll('.filter-panel select')
    selects[0].value = 'MV'
    selects[0].dispatchEvent(new Event('change', { bubbles: true }))
    selects[1].value = 'COPIED'
    selects[1].dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()

    root.querySelector('.pager button:last-child').click()
    await flushUi()
    expect(apiMock.adminSongs).toHaveBeenLastCalledWith(
      { keyword: '周杰伦', type: 'MV', source: 'COPIED', page: 1, size: 20 },
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    root.querySelector('.filter-panel .filter-actions .secondary').click()
    await flushUi()

    expect(apiMock.adminSongs).toHaveBeenLastCalledWith(
      { keyword: '', type: '', source: '', page: 0, size: 20 },
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
  })

  it('uses the song count returned by the playlist API before the legacy count field', async () => {
    await mount()
    apiMock.adminAiPlaylists.mockResolvedValueOnce([{ id: 10, name: '服务端歌单', songCount: 2 }])
    root.querySelector('.playlist-link').click()
    await flushUi()

    apiMock.adminAiAddPlaylistSong.mockResolvedValueOnce({ songs: [{ id: 1 }, { id: 2 }, { id: 3 }] })
    root.querySelector('.playlist-picker-item').click()
    await flushUi()

    expect(root.querySelector('.playlist-picker-modal')).toBeNull()
    expect(apiMock.adminAiAddPlaylistSong).toHaveBeenCalledWith(10, 7)
  })

  it('filters and pages songs while dropping selections that no longer appear on the loaded page', async () => {
    const manySongs = Array.from({ length: 21 }, (_, index) => ({ ...song, id: index + 1, title: `歌曲 ${index + 1}` }))
    apiMock.adminSongs.mockImplementation(async params => ({
      content: manySongs.slice(params.page * params.size, (params.page + 1) * params.size),
      total: manySongs.length, totalPages: 2
    }))
    await mount()

    const previous = root.querySelector('.pager button:first-child')
    previous.disabled = false
    previous.click()
    await flushUi()
    expect(apiMock.adminSongs).toHaveBeenCalledTimes(1)

    const keyword = root.querySelector('.filter-panel input')
    keyword.value = '周杰伦'
    keyword.dispatchEvent(new Event('input', { bubbles: true }))
    const selects = root.querySelectorAll('.filter-panel select')
    selects[0].value = 'MV'
    selects[0].dispatchEvent(new Event('change', { bubbles: true }))
    selects[1].value = 'TRANSCODED'
    selects[1].dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()

    expect(apiMock.adminSongs).toHaveBeenLastCalledWith(
      { keyword: '周杰伦', type: 'MV', source: 'TRANSCODED', page: 0, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    root.querySelector('tbody input[type="checkbox"]').click()
    await nextTick()
    expect(root.querySelector('.scrape-batch').disabled).toBe(false)
    root.querySelector('tbody input[type="checkbox"]').click()
    await nextTick()
    expect(root.querySelector('.scrape-batch').disabled).toBe(true)
    root.querySelector('tbody input[type="checkbox"]').click()
    await nextTick()
    expect(root.querySelector('.scrape-batch').disabled).toBe(false)

    root.querySelector('.pager button:last-child').click()
    await flushUi()
    expect(apiMock.adminSongs).toHaveBeenLastCalledWith(
      { keyword: '周杰伦', type: 'MV', source: 'TRANSCODED', page: 1, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(root.querySelector('.scrape-batch').disabled).toBe(true)
    const next = root.querySelector('.pager button:last-child')
    expect(next.disabled).toBe(true)
    const callsBeforeInvalidNext = apiMock.adminSongs.mock.calls.length
    next.disabled = false
    next.click()
    await flushUi()
    expect(apiMock.adminSongs).toHaveBeenCalledTimes(callsBeforeInvalidNext)

    const validPrevious = root.querySelector('.pager button:first-child')
    expect(validPrevious.disabled).toBe(false)
    validPrevious.click()
    await flushUi()
    expect(apiMock.adminSongs).toHaveBeenLastCalledWith(
      { keyword: '周杰伦', type: 'MV', source: 'TRANSCODED', page: 0, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
  })

  it('keeps an edit open after a save error and safely closes when the server returns no updated entity', async () => {
    apiMock.adminEditSong.mockRejectedValueOnce(new Error('edit rejected'))
    await mount()
    root.querySelector('.edit-link').click()
    await nextTick()
    root.querySelector('.song-editor-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('edit rejected')
    expect(root.querySelector('.song-editor-modal')).not.toBeNull()

    apiMock.adminEditSong.mockRejectedValueOnce({})
    root.querySelector('.song-editor-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('保存歌曲失败')

    let finishSave
    apiMock.adminEditSong.mockReturnValueOnce(new Promise(resolve => { finishSave = resolve }))
    root.querySelector('.song-editor-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    root.querySelector('.song-editor-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await nextTick()
    expect(root.querySelector('.song-editor-modal .match-head .icon-button').disabled).toBe(true)
    root.querySelector('.mask').dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(root.querySelector('.song-editor-modal')).not.toBeNull()
    expect(apiMock.adminEditSong).toHaveBeenCalledTimes(3)
    finishSave(undefined)
    await flushUi()

    expect(root.querySelector('.song-editor-modal')).toBeNull()
    expect(song.title).toBe('测试歌曲')
  })

  it('deletes a single managed song after confirmation and retains batch selection on failure', async () => {
    apiMock.adminDeleteSongs.mockRejectedValueOnce(new Error('batch delete rejected'))
    await mount()

    root.querySelector('.danger-text').click()
    await flushUi()
    expect(apiMock.adminDeleteSong).toHaveBeenCalledWith(7)

    root.querySelector('tbody tr input[type="checkbox"]').click()
    await nextTick()
    root.querySelector('.toolbar-actions .danger').click()
    await flushUi()

    expect(apiMock.adminDeleteSongs).toHaveBeenCalledWith([7])
    expect(alertMock).toHaveBeenCalledWith('batch delete rejected')
    expect(root.querySelector('.toolbar-actions .danger').textContent).toContain('批量删除（1）')
  })

  it('keeps selection when batch deletion is declined and shows fallback errors for both delete APIs', async () => {
    apiMock.adminDeleteSong.mockRejectedValueOnce({})
    apiMock.adminDeleteSongs.mockRejectedValueOnce({})
    await mount()
    root.querySelector('.danger-text').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('删除失败')

    root.querySelector('tbody tr input[type="checkbox"]').click()
    await nextTick()
    confirmMock.mockResolvedValueOnce(false)
    root.querySelector('.toolbar-actions .danger').click()
    await flushUi()
    expect(apiMock.adminDeleteSongs).not.toHaveBeenCalled()
    expect(root.querySelector('.toolbar-actions .danger').textContent).toContain('批量删除（1）')

    confirmMock.mockResolvedValueOnce(true)
    root.querySelector('.toolbar-actions .danger').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('批量删除失败')
    expect(root.querySelector('.toolbar-actions .danger').textContent).toContain('批量删除（1）')
  })

  it('renders media, import-source, and audio-layout fallbacks for varied library records', async () => {
    apiMock.adminSongs.mockResolvedValueOnce({
      content: [
        { ...song, id: 21, mediaType: 'MV', importSource: 'COPIED', audioLayout: { layout: 'DUAL_TRACK' }, tags: [], playCount: 12 },
        { ...song, id: 22, mediaType: 'AUDIO', importSource: 'TRANSCODED', audioLayout: { layout: 'DUAL_CHANNEL', originalChannel: 'RIGHT', accompanimentChannel: 'LEFT' }, language: null },
        { ...song, id: 23, mediaType: 'UNRECOGNIZED', importSource: 'LEGACY', audioLayout: null, tags: null, filePath: null, playCount: null }
      ], total: 3, totalPages: 1
    })
    await mount()

    const rows = [...root.querySelectorAll('tbody tr')]
    expect(rows[0].textContent).toContain('MV')
    expect(rows[0].textContent).toContain('扫描直入')
    expect(rows[0].textContent).toContain('Original Track — / Accompaniment Track —')
    expect(rows[0].querySelector('td:nth-child(9)').textContent).toBe('12')
    expect(rows[1].textContent).toContain('音频版')
    expect(rows[1].textContent).toContain('转码入库')
    expect(rows[1].textContent).toContain('RIGHT / LEFT')
    expect(rows[2].textContent).toContain('UNRECOGNIZED')
    expect(rows[2].textContent).toContain('历史曲库')
    expect(rows[2].textContent).toContain('普通立体声')
    expect(rows[2].textContent).toContain('无原唱/伴唱切换')
    expect(rows[2].textContent).toContain('无标签')
    expect(rows[2].querySelector('td:nth-child(6)').textContent).toBe('—')

    const selectAll = root.querySelector('thead input[type="checkbox"]')
    selectAll.click()
    await nextTick()
    expect(selectAll.checked).toBe(true)
    selectAll.click()
    await nextTick()
    expect(selectAll.checked).toBe(false)
    expect(root.querySelector('.scrape-batch').disabled).toBe(true)
  })

  async function mount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(KtvLibraryView)
    app.mount(root)
    await flushUi()
  }
})

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
