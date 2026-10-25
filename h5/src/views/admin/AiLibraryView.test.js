// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, nextTick } from 'vue'

const { apiMock, alertMock, confirmMock } = vi.hoisted(() => ({
  apiMock: {
    adminAiPlaylists: vi.fn(), adminAiConfig: vi.fn(), adminAiPlaylist: vi.fn(),
    adminAiCreatePlaylist: vi.fn(), adminAiUpdatePlaylist: vi.fn(), adminAiDeletePlaylist: vi.fn(),
    adminAiUploadPlaylistCover: vi.fn(), adminAiPreviewPlaylist: vi.fn(), adminAiSavePlaylistPreview: vi.fn(),
    adminSongs: vi.fn(), adminAiAddPlaylistSong: vi.fn(), adminAiRemovePlaylistSong: vi.fn(), adminAiReorderPlaylistSongs: vi.fn()
  },
  alertMock: vi.fn(), confirmMock: vi.fn()
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../composables/useDialog', () => ({ alertDialog: alertMock, confirmDialog: confirmMock }))
vi.mock('./AdminLayout.vue', () => ({ default: defineComponent({ template: '<div class="admin-layout"><slot /></div>' }) }))
vi.mock('../../components/admin/AiTaskPanel.vue', () => ({ default: defineComponent({ emits: ['close', 'applied'], template: '<div class="ai-task-panel"><button @click="$emit(\'close\')">close</button></div>' }) }))

import AiLibraryView from './AiLibraryView.vue'

describe('AiLibraryView', () => {
  let app
  let root
  let playlists
  let playlist

  beforeEach(() => {
    vi.clearAllMocks()
    playlists = [playlistSummary()]
    playlist = playlistDetail()
    apiMock.adminAiPlaylists.mockImplementation(async () => playlists)
    apiMock.adminAiConfig.mockResolvedValue({ enabled: true, baseUrl: 'http://ai.local', bulkModel: 'mock' })
    apiMock.adminAiPlaylist.mockImplementation(async id => ({ ...playlist, id }))
    apiMock.adminAiCreatePlaylist.mockResolvedValue({ id: 5 })
    apiMock.adminAiUpdatePlaylist.mockResolvedValue({ id: 5 })
    apiMock.adminAiPreviewPlaylist.mockResolvedValue({ name: '周末歌单', description: '轻松愉快', songIds: [11, 12], songs: [{ id: 11, title: '歌一', artist: '歌手一' }, { id: 12, title: '歌二', artist: '歌手二' }], selectedCount: 2, candidateCount: 20, metadataEnrichedCount: 18, limit: 2 })
    apiMock.adminAiSavePlaylistPreview.mockResolvedValue({ id: 6 })
    apiMock.adminSongs.mockResolvedValue({ content: [{ id: 11, title: '歌一', artist: '歌手一' }, { id: 13, title: '新歌', artist: '新歌手' }] })
    apiMock.adminAiAddPlaylistSong.mockImplementation(async (_playlistId, songId) => {
      playlist = { ...playlist, songs: [...playlist.songs, { songId, title: '新歌', artist: '新歌手', manual: true }] }
      return playlist
    })
    apiMock.adminAiRemovePlaylistSong.mockResolvedValue({})
    apiMock.adminAiReorderPlaylistSongs.mockImplementation(async (_id, ids) => ({ ...playlist, songs: ids.map(songId => playlist.songs.find(song => song.songId === songId)) }))
    confirmMock.mockResolvedValue(true)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('loads playlists, handles AI-config errors, and filters by title, source, and visibility', async () => {
    apiMock.adminAiConfig.mockRejectedValueOnce(new Error('AI 配置读取失败'))
    playlists = [
      { ...playlistSummary(), id: 1, name: '公开 AI 歌单', aiGenerated: true, publicVisible: true },
      { ...playlistSummary(), id: 2, name: '隐藏 AI 歌单', aiGenerated: true, publicVisible: false },
      { ...playlistSummary(), id: 3, name: '公开人工歌单', aiGenerated: false, publicVisible: true },
      { ...playlistSummary(), id: 4, name: '隐藏人工歌单', aiGenerated: false, publicVisible: false }
    ]
    await mount()
    expect(root.textContent).toContain('AI 配置读取失败')
    expect(root.querySelector('.toolbar-actions .primary').disabled).toBe(true)
    expect(root.querySelectorAll('tbody tr')).toHaveLength(4)

    const keyword = root.querySelector('.filter-panel input')
    keyword.value = '隐藏'
    keyword.dispatchEvent(new Event('input', { bubbles: true }))
    root.querySelectorAll('.filter-panel select')[0].value = 'manual'
    root.querySelectorAll('.filter-panel select')[0].dispatchEvent(new Event('change', { bubbles: true }))
    root.querySelectorAll('.filter-panel select')[1].value = 'hidden'
    root.querySelectorAll('.filter-panel select')[1].dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    expect(root.querySelectorAll('tbody tr')).toHaveLength(1)
    expect(root.textContent).toContain('隐藏人工歌单')

    keyword.value = ''
    keyword.dispatchEvent(new Event('input', { bubbles: true }))
    root.querySelectorAll('.filter-panel select')[0].value = ''
    root.querySelectorAll('.filter-panel select')[0].dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    expect(root.querySelectorAll('tbody tr')).toHaveLength(2)
    expect(root.textContent).toContain('隐藏 AI 歌单')
    expect(root.textContent).toContain('隐藏人工歌单')
    expect(root.textContent).not.toContain('公开 AI 歌单')
    expect(root.textContent).not.toContain('公开人工歌单')

    root.querySelector('.filter-actions .secondary').click()
    await nextTick()
    expect(root.querySelectorAll('tbody tr')).toHaveLength(4)
  })

  it('filters AI and public playlists and resets every filter together', async () => {
    playlists = [
      { ...playlistSummary(), id: 1, name: 'AI公开', aiGenerated: true, publicVisible: true },
      { ...playlistSummary(), id: 2, name: 'AI隐藏', aiGenerated: true, publicVisible: false },
      { ...playlistSummary(), id: 3, name: '人工公开', aiGenerated: false, publicVisible: true },
      { ...playlistSummary(), id: 4, name: '人工隐藏', aiGenerated: false, publicVisible: false }
    ]
    await mount()

    const [source, visibility] = root.querySelectorAll('.filter-panel select')
    source.value = 'ai'
    source.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    expect(root.querySelectorAll('tbody tr')).toHaveLength(2)

    visibility.value = 'public'
    visibility.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    expect(root.querySelectorAll('tbody tr')).toHaveLength(1)
    expect(root.textContent).toContain('AI公开')

    root.querySelector('.filter-actions .secondary').click()
    await nextTick()
    expect(root.querySelectorAll('tbody tr')).toHaveLength(4)
    expect(source.value).toBe('')
    expect(visibility.value).toBe('')
  })

  it('renders defaults for missing playlist summary fields and applies an available cover', async () => {
    playlists = [
      { ...playlistSummary(), id: 1, name: null, description: null, theme: '', songCount: 0, manualCount: 0, updatedAt: null, coverUrl: null },
      { ...playlistSummary(), id: 2, name: '有封面', coverUrl: 'https://example.test/cover.jpg' }
    ]
    await mount()

    const rows = root.querySelectorAll('tbody tr')
    expect(rows[0].textContent).toContain('暂无描述')
    expect(rows[0].textContent).toContain('未设置')
    expect(rows[0].textContent).toContain('人工加入 0 首')
    expect(rows[0].textContent).toContain('—')
    expect(rows[0].querySelector('.cover-thumb').style.backgroundImage).toBe('')
    expect(rows[1].querySelector('.cover-thumb').style.backgroundImage).toContain('cover.jpg')
  })

  it('retries AI config with the fallback error text and unlocks generation after recovery', async () => {
    apiMock.adminAiConfig.mockRejectedValueOnce({})
    await mount()

    expect(root.querySelectorAll('[role="alert"]')[0].textContent).toContain('网络错误，请稍后重试')
    expect(root.querySelector('.toolbar-actions .primary').disabled).toBe(true)
    root.querySelector('.error-notice button').click()
    await flushUi()

    expect(root.querySelectorAll('[role="alert"]')).toHaveLength(0)
    expect(root.querySelector('.toolbar-actions .primary').disabled).toBe(false)
  })

  it('creates and edits a playlist with normalized form data', async () => {
    await mount()
    root.querySelector('.toolbar-actions .secondary').click()
    await nextTick()
    const fields = root.querySelectorAll('.editor-modal .form-grid input')
    fields[0].value = ' 周末聚会 '
    fields[0].dispatchEvent(new Event('input', { bubbles: true }))
    fields[1].value = ' 家庭 '
    fields[1].dispatchEvent(new Event('input', { bubbles: true }))
    const description = root.querySelector('.editor-modal textarea')
    description.value = '适合家庭聚会'
    description.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.editor-modal .primary').click()
    await flushUi()

    expect(apiMock.adminAiCreatePlaylist).toHaveBeenCalledWith({ name: '周末聚会', description: '适合家庭聚会', theme: '家庭', publicVisible: true })
    expect(apiMock.adminAiPlaylist).toHaveBeenCalledWith(5)
    expect(root.querySelector('.editor-modal')).toBeNull()
    expect(root.textContent).toContain('歌单已保存')
  })

  it('updates an existing playlist and defaults missing optional detail fields', async () => {
    apiMock.adminAiPlaylist.mockResolvedValueOnce({ ...playlist, description: null, theme: null, publicVisible: false })
    await mount()
    root.querySelector('button[title="编辑歌单"]').click()
    await flushUi()

    const fields = root.querySelectorAll('.editor-modal .form-grid input')
    expect(fields[0].value).toBe('测试歌单')
    expect(fields[1].value).toBe('')
    expect(root.querySelector('.editor-modal textarea').value).toBe('')
    expect(root.querySelector('.editor-modal .check-row input').checked).toBe(false)

    fields[0].value = '更新后的歌单'
    fields[0].dispatchEvent(new Event('input', { bubbles: true }))
    fields[1].value = '新主题'
    fields[1].dispatchEvent(new Event('input', { bubbles: true }))
    root.querySelector('.editor-modal textarea').value = '新说明'
    root.querySelector('.editor-modal textarea').dispatchEvent(new Event('input', { bubbles: true }))
    const visibility = root.querySelector('.editor-modal .check-row input')
    visibility.checked = true
    visibility.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    root.querySelector('.editor-modal .primary').click()
    await flushUi()

    expect(apiMock.adminAiUpdatePlaylist).toHaveBeenCalledWith(5, {
      name: '更新后的歌单', description: '新说明', theme: '新主题', publicVisible: true
    })
    expect(root.querySelector('.editor-modal')).toBeNull()
  })

  it('ignores an empty cover selection and reloads playlist state after a successful upload', async () => {
    await mount()
    root.querySelector('button[title="编辑歌单"]').click()
    await flushUi()
    const input = root.querySelector('.editor-modal .upload-button input')

    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(apiMock.adminAiUploadPlaylistCover).not.toHaveBeenCalled()

    const file = new File(['cover'], 'cover.webp', { type: 'image/webp' })
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    apiMock.adminAiUploadPlaylistCover.mockResolvedValueOnce({})
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()

    expect(apiMock.adminAiUploadPlaylistCover).toHaveBeenCalledWith(5, file)
    expect(apiMock.adminAiPlaylist).toHaveBeenCalledTimes(2)
    expect(root.textContent).toContain('歌单封面已更新')
    expect(input.value).toBe('')
  })

  it('clamps the current page after deleting the only playlist on the last page', async () => {
    playlists = Array.from({ length: 21 }, (_, index) => ({ ...playlistSummary(), id: index + 1, name: `歌单${index + 1}` }))
    apiMock.adminAiDeletePlaylist.mockImplementation(async id => {
      playlists = playlists.filter(item => item.id !== id)
    })
    await mount()

    root.querySelector('.pager button:last-child').click()
    await nextTick()
    expect(root.querySelector('.pager').textContent).toContain('第 2 / 2 页')
    expect(root.querySelector('tbody tr').textContent).toContain('歌单21')
    root.querySelector('button[title="管理歌曲"]').click()
    await flushUi()
    root.querySelector('.songs-modal .modal-actions .primary').click()
    await nextTick()
    root.querySelector('button[title="删除歌单"]').click()
    await flushUi()

    expect(root.querySelector('.pager').textContent).toContain('第 1 / 1 页')
    expect(root.querySelector('tbody tr').textContent).toContain('歌单1')
  })

  it('clamps generator limits, removes preview songs, and saves the remaining IDs', async () => {
    await mount()
    root.querySelector('.toolbar-actions .primary').click()
    await nextTick()
    const instruction = root.querySelector('.generator-modal textarea')
    instruction.value = '适合家庭聚会'
    instruction.dispatchEvent(new Event('input', { bubbles: true }))
    const limit = root.querySelector('.limit-field input')
    limit.value = '150'
    limit.dispatchEvent(new Event('input', { bubbles: true }))
    limit.dispatchEvent(new Event('blur'))
    await nextTick()
    root.querySelector('.generator-controls .primary').click()
    await flushUi()

    expect(apiMock.adminAiPreviewPlaylist).toHaveBeenCalledWith({ instruction: '适合家庭聚会', limit: 100 })
    expect(root.textContent).toContain('周末歌单')
    root.querySelector('button[title="从预览中移除"]').click()
    await nextTick()
    root.querySelector('.generator-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminAiSavePlaylistPreview).toHaveBeenCalledWith({ name: '周末歌单', description: '轻松愉快', theme: 'AI 策划', publicVisible: true, songIds: [12] })
    expect(root.querySelector('.generator-modal')).toBeNull()
  })

  it('clamps a nonpositive generator limit to one song', async () => {
    await mount()
    root.querySelector('.toolbar-actions .primary').click()
    await nextTick()
    const instruction = root.querySelector('.generator-modal textarea')
    instruction.value = '只选一首歌'
    instruction.dispatchEvent(new Event('input', { bubbles: true }))
    const limit = root.querySelector('.limit-field input')
    limit.value = '-3'
    limit.dispatchEvent(new Event('input', { bubbles: true }))
    limit.dispatchEvent(new Event('blur'))
    await nextTick()
    root.querySelector('.generator-controls .primary').click()
    await flushUi()

    expect(apiMock.adminAiPreviewPlaylist).toHaveBeenCalledWith({ instruction: '只选一首歌', limit: 1 })

    limit.value = '0'
    limit.dispatchEvent(new Event('input', { bubbles: true }))
    limit.dispatchEvent(new Event('blur'))
    await nextTick()
    root.querySelector('.generator-controls .primary').click()
    await flushUi()
    expect(apiMock.adminAiPreviewPlaylist).toHaveBeenLastCalledWith({ instruction: '只选一首歌', limit: 100 })
  })

  it('renders sparse AI preview defaults and removes a preview when IDs are omitted', async () => {
    apiMock.adminAiPreviewPlaylist
      .mockResolvedValueOnce({ name: '稀疏结果', description: null, songs: null, songIds: [88], selectedCount: null, candidateCount: 0, metadataEnrichedCount: 0, limit: 0 })
      .mockResolvedValueOnce({ name: '待清理结果', description: '', songs: [{ id: 88, title: '待移除歌曲', artist: '歌手' }], songIds: null, selectedCount: 1, candidateCount: 1, metadataEnrichedCount: 0, limit: 1 })
    await mount()
    root.querySelector('.toolbar-actions .primary').click()
    await nextTick()
    const instruction = root.querySelector('.generator-modal textarea')
    instruction.value = '按稀疏响应测试'
    instruction.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.generator-controls .primary').click()
    await flushUi()

    expect(root.querySelector('.preview-head').textContent).toContain('暂无描述')
    expect(root.querySelector('.preview-head').textContent).toContain('0 首')
    expect(root.querySelector('.preview-table .empty').textContent).toContain('AI 未选出符合条件的歌曲')
    expect(root.querySelector('.generator-modal footer .primary').disabled).toBe(false)

    root.querySelector('.generator-controls .primary').click()
    await flushUi()
    root.querySelector('button[title="从预览中移除"]').click()
    await nextTick()
    expect(root.querySelector('.preview-table .empty').textContent).toContain('AI 未选出符合条件的歌曲')
    expect(root.querySelector('.generator-modal footer .primary').disabled).toBe(true)
  })

  it('searches playlist songs, prevents duplicates, adds new songs, and persists reordered results', async () => {
    playlist = {
      ...playlist,
      songs: playlist.songs.map((song, index) => index === 0 ? { ...song, artist: '' } : song)
    }
    await mount()
    root.querySelector('button[title="管理歌曲"]').click()
    await flushUi()
    expect(root.querySelector('.song-table tbody tr:first-child td:nth-child(3)').textContent).toContain('未知歌手')
    const search = root.querySelector('.song-search-field input')
    search.value = '新歌'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.add-song-bar .action-button').click()
    await flushUi()

    expect(apiMock.adminSongs).toHaveBeenCalledWith({ keyword: '新歌', page: 0, size: 10 })
    const buttons = root.querySelectorAll('.song-search-result button')
    expect(buttons[0].disabled).toBe(true)
    expect(buttons[0].textContent).toContain('已在歌单')
    buttons[1].click()
    await flushUi()
    expect(apiMock.adminAiAddPlaylistSong).toHaveBeenCalledWith(5, 13)

    root.querySelector('.song-table [title="下移"]').click()
    await flushUi()
    expect(apiMock.adminAiReorderPlaylistSongs).toHaveBeenCalledWith(5, [12, 11, 13])
  })

  it('renders empty song-search results and ignores a result without a valid song ID', async () => {
    await mount()
    root.querySelector('button[title="管理歌曲"]').click()
    await flushUi()
    const search = root.querySelector('.song-search-field input')
    search.value = '不存在'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    apiMock.adminSongs.mockResolvedValueOnce({})
    root.querySelector('.add-song-bar .action-button').click()
    await flushUi()
    expect(root.querySelector('.search-empty').textContent).toContain('没有找到匹配的 KTV 曲库歌曲')

    apiMock.adminSongs.mockResolvedValueOnce({ content: [{ id: 0, title: '无效 ID', artist: null }] })
    root.querySelector('.add-song-bar .action-button').click()
    await flushUi()
    const invalidResult = root.querySelector('.song-search-result button')
    expect(root.querySelector('.song-search-result').textContent).toContain('未知歌手')
    expect(invalidResult.disabled).toBe(false)
    invalidResult.click()
    await flushUi()
    expect(apiMock.adminAiAddPlaylistSong).not.toHaveBeenCalled()
  })

  it('ignores empty and duplicate in-flight song searches submitted by Enter', async () => {
    let finishSearch
    apiMock.adminSongs.mockReturnValueOnce(new Promise(resolve => { finishSearch = resolve }))
    await mount()
    root.querySelector('button[title="管理歌曲"]').click()
    await flushUi()
    const search = root.querySelector('.song-search-field input')
    search.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    await flushUi()
    expect(apiMock.adminSongs).not.toHaveBeenCalled()

    search.value = '测试歌曲'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    search.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    await nextTick()
    search.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    await flushUi()
    expect(apiMock.adminSongs).toHaveBeenCalledTimes(1)
    expect(root.querySelector('.add-song-bar .action-button').textContent).toContain('搜索中')

    finishSearch({ content: [] })
    await flushUi()
    expect(root.querySelector('.search-empty')).not.toBeNull()
  })

  it('reports a fallback message and unlocks song management after a failed removal', async () => {
    apiMock.adminAiRemovePlaylistSong.mockRejectedValueOnce({})
    await mount()
    root.querySelector('button[title="管理歌曲"]').click()
    await flushUi()
    root.querySelector('.song-table button[title="移除歌曲"]').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('操作失败')
    expect(root.querySelector('.songs-modal')).not.toBeNull()
    expect(root.querySelector('.songs-modal .modal-actions .primary').disabled).toBe(false)
  })

  it('reorders rows with drag and drop while ignoring a drop on the dragged row', async () => {
    await mount()
    root.querySelector('button[title="管理歌曲"]').click()
    await flushUi()
    const rows = root.querySelectorAll('.song-table tbody tr')
    expect(rows[0].querySelector('button[title="上移"]').disabled).toBe(true)
    expect(rows[rows.length - 1].querySelector('button[title="下移"]').disabled).toBe(true)

    rows[0].dispatchEvent(new Event('dragstart', { bubbles: true }))
    rows[0].dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }))
    await flushUi()
    expect(apiMock.adminAiReorderPlaylistSongs).not.toHaveBeenCalled()

    rows[0].dispatchEvent(new Event('dragstart', { bubbles: true }))
    rows[1].dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }))
    await flushUi()
    expect(apiMock.adminAiReorderPlaylistSongs).toHaveBeenCalledWith(5, [12, 11])
  })

  it('declines deletion without calling the API, then deletes after confirmation', async () => {
    await mount()
    confirmMock.mockResolvedValueOnce(false)
    root.querySelector('button[title="删除歌单"]').click()
    await flushUi()
    expect(apiMock.adminAiDeletePlaylist).not.toHaveBeenCalled()

    root.querySelector('button[title="删除歌单"]').click()
    await flushUi()
    expect(apiMock.adminAiDeletePlaylist).toHaveBeenCalledWith(5)
    expect(root.textContent).toContain('歌单已删除')
  })

  it('retries playlist loading after an error and renders the empty state after recovery', async () => {
    apiMock.adminAiPlaylists.mockRejectedValueOnce({})
    await mount()

    expect(root.querySelector('.error-empty')).not.toBeNull()
    expect(root.querySelector('[role="alert"]').textContent).toContain('网络错误，请稍后重试')
    apiMock.adminAiPlaylists.mockResolvedValueOnce([])
    root.querySelector('.error-empty .text-btn').click()
    await flushUi()

    expect(root.querySelector('.error-empty')).toBeNull()
    expect(root.querySelector('.table-empty').textContent).toContain('暂无符合条件的主题歌单')
  })

  it('reports playlist detail, create, and cover upload failures while keeping the editor usable', async () => {
    apiMock.adminAiPlaylist.mockRejectedValueOnce(new Error('playlist detail unavailable'))
    await mount()
    root.querySelector('button[title="编辑歌单"]').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('playlist detail unavailable')
    expect(root.querySelector('.editor-modal')).toBeNull()

    root.querySelector('.toolbar-actions .secondary').click()
    await nextTick()
    const name = root.querySelector('.editor-modal .form-grid input')
    name.value = '新歌单'
    name.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    alertMock.mockClear()
    apiMock.adminAiCreatePlaylist.mockRejectedValueOnce(new Error('playlist create rejected'))
    root.querySelector('.editor-modal .primary').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('playlist create rejected')
    expect(root.querySelector('.editor-modal')).not.toBeNull()

    app.unmount()
    app = null
    apiMock.adminAiPlaylist.mockImplementation(async id => ({ ...playlist, id }))
    await mount()
    root.querySelector('button[title="编辑歌单"]').click()
    await flushUi()
    const input = root.querySelector('.editor-modal .upload-button input')
    const file = new File(['cover'], 'cover.png', { type: 'image/png' })
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    apiMock.adminAiUploadPlaylistCover.mockRejectedValueOnce(new Error('cover upload rejected'))
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(apiMock.adminAiUploadPlaylistCover).toHaveBeenCalledWith(5, file)
    expect(alertMock).toHaveBeenCalledWith('cover upload rejected')
    expect(input.value).toBe('')
  })

  it('keeps generator errors visible and blocks saving an empty preview', async () => {
    apiMock.adminAiPreviewPlaylist.mockRejectedValueOnce(new Error('preview unavailable'))
    await mount()
    root.querySelector('.toolbar-actions .primary').click()
    await nextTick()
    const instruction = root.querySelector('.generator-modal textarea')
    instruction.value = '适合通勤'
    instruction.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.generator-controls .primary').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('preview unavailable')
    expect(root.querySelector('.generator-modal')).not.toBeNull()

    apiMock.adminAiPreviewPlaylist.mockResolvedValueOnce({ name: '空歌单', description: '', songs: [], songIds: [], candidateCount: 0, metadataEnrichedCount: 0 })
    root.querySelector('.generator-controls .primary').click()
    await flushUi()
    expect(root.querySelector('.preview-table .empty').textContent).toContain('AI 未选出符合条件的歌曲')
    expect(root.querySelector('.generator-modal footer .primary').disabled).toBe(true)
    expect(apiMock.adminAiSavePlaylistPreview).not.toHaveBeenCalled()
  })

  it('reports song-search failures, respects the 100-song cap, and renders an emptied playlist after removal', async () => {
    playlist = {
      ...playlist,
      songs: Array.from({ length: 100 }, (_, index) => ({ songId: index + 1, title: `歌${index + 1}`, artist: '合成歌手', manual: false }))
    }
    await mount()
    root.querySelector('button[title="管理歌曲"]').click()
    await flushUi()
    const search = root.querySelector('.song-search-field input')
    search.value = '新歌'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    apiMock.adminSongs.mockRejectedValueOnce(new Error('song search unavailable'))
    root.querySelector('.add-song-bar .action-button').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('song search unavailable')

    apiMock.adminSongs.mockRejectedValueOnce({})
    root.querySelector('.add-song-bar .action-button').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('歌曲搜索失败')

    apiMock.adminSongs.mockResolvedValueOnce({ content: [{ id: 13, title: '新歌', artist: '新歌手' }] })
    root.querySelector('.add-song-bar .action-button').click()
    await flushUi()
    expect(root.querySelector('.song-search-result button').disabled).toBe(true)
    expect(apiMock.adminAiAddPlaylistSong).not.toHaveBeenCalled()

    app.unmount()
    app = null
    playlist = { ...playlist, songs: [{ songId: 11, title: '唯一歌曲', artist: '歌手', manual: false }] }
    apiMock.adminAiRemovePlaylistSong.mockImplementationOnce(async (_playlistId, songId) => {
      playlist = { ...playlist, songs: playlist.songs.filter(song => song.songId !== songId) }
    })
    await mount()
    root.querySelector('button[title="管理歌曲"]').click()
    await flushUi()
    root.querySelector('.song-table button[title="移除歌曲"]').click()
    await flushUi()
    expect(apiMock.adminAiRemovePlaylistSong).toHaveBeenCalledWith(5, 11)
    expect(root.querySelector('.song-table .empty').textContent).toContain('歌单中暂无歌曲')
  })

  async function mount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(AiLibraryView)
    app.component('router-link', { props: ['to'], template: '<a><slot /></a>' })
    app.mount(root)
    await flushUi()
  }
})

function playlistSummary() {
  return { id: 5, name: '测试歌单', description: '测试描述', theme: '流行', songCount: 2, manualCount: 1, aiGenerated: true, publicVisible: true, updatedAt: '2026-01-01T00:00:00Z' }
}

function playlistDetail() {
  return { ...playlistSummary(), songs: [
    { songId: 11, title: '歌一', artist: '歌手一', manual: false },
    { songId: 12, title: '歌二', artist: '歌手二', manual: true }
  ] }
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
