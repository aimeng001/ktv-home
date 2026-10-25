// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, nextTick } from 'vue'

const { apiMock, alertMock, confirmMock } = vi.hoisted(() => ({
  apiMock: {
    adminSourceLibrary: vi.fn(), adminSourceTranscodeProgress: vi.fn(),
    adminStartSourceTranscode: vi.fn(), adminCleanupImportedSources: vi.fn(),
    adminPrioritizeSourceTranscode: vi.fn(), adminDeleteSources: vi.fn()
  },
  alertMock: vi.fn(), confirmMock: vi.fn()
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../composables/useDialog', () => ({ alertDialog: alertMock, confirmDialog: confirmMock }))
vi.mock('./AdminLayout.vue', () => ({ default: defineComponent({ template: '<div class="admin-layout"><slot /></div>' }) }))

import SourceLibraryView from './SourceLibraryView.vue'

describe('SourceLibraryView', () => {
  let app
  let root

  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.adminSourceLibrary.mockResolvedValue({ libraryMode: 'MANAGED', content: [pendingSource(1), pendingSource(2)], total: 2, totalPages: 1 })
    apiMock.adminSourceTranscodeProgress.mockResolvedValue({ running: false, total: 0, completed: 0 })
    apiMock.adminStartSourceTranscode.mockResolvedValue({ running: true, total: 1, completed: 0 })
    apiMock.adminPrioritizeSourceTranscode.mockResolvedValue({ progress: { running: true, total: 2, completed: 0, currentRecordId: 2, priorityRecordIds: [1] } })
    apiMock.adminDeleteSources.mockResolvedValue({})
    confirmMock.mockResolvedValue(true)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('renders external libraries as read-only with all transcode and cleanup controls absent', async () => {
    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'EXTERNAL_READ_ONLY', content: [pendingSource(1)], total: 1, totalPages: 1 })
    await mount()

    expect(root.textContent).toContain('当前 NAS 曲库仅允许扫描、索引和播放')
    expect(root.querySelector('.filter-panel')).toBeNull()
    expect(root.querySelector('.cleanup-button')).toBeNull()
    expect(root.querySelector('.table-panel')).toBeNull()
    expect(apiMock.adminStartSourceTranscode).not.toHaveBeenCalled()
    expect(apiMock.adminDeleteSources).not.toHaveBeenCalled()
  })

  it('defaults missing source-page fields and reports a zero-total running progress state', async () => {
    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'MANAGED' })
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: true, total: 0, completed: 0 })
    await mount()

    expect(root.querySelector('.empty').textContent).toContain('暂无符合条件的源素材')
    expect(root.querySelector('.toolbar > span').textContent).toContain('共 0 条')
    expect(root.querySelector('.pager').textContent).toContain('第 1 / 1 页')
    expect(root.querySelector('.progress-value').textContent).toBe('0%')
    expect(root.querySelector('.header-actions .primary').disabled).toBe(true)
  })

  it('clears a selected source when a later server response switches the library to read-only', async () => {
    await mount()
    root.querySelector('[aria-label="选择 song-1.rmvb"]').click()
    await nextTick()
    expect(root.querySelector('.batch-actions .danger').textContent).toContain('批量删除（1）')

    apiMock.adminSourceLibrary.mockResolvedValueOnce({
      libraryMode: 'EXTERNAL_READ_ONLY', content: [pendingSource(1)], total: 1, totalPages: 1
    })
    const status = root.querySelectorAll('.filter-panel select')[0]
    status.value = 'failed'
    status.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()

    expect(root.querySelector('.readonly-panel')).not.toBeNull()
    expect(root.querySelector('.filter-panel')).toBeNull()
    expect(root.querySelector('.table-panel')).toBeNull()
    expect(root.querySelector('.batch-actions')).toBeNull()
  })

  it('starts a single transcode only after confirmation and uses the source record id', async () => {
    await mount()
    root.querySelector('tbody tr .row-actions .action-button').click()
    await flushUi()

    expect(confirmMock).toHaveBeenCalledWith('/source/song-1.rmvb', { title: '转码该源文件' })
    expect(apiMock.adminStartSourceTranscode).toHaveBeenCalledWith([1])
    expect(root.textContent).toContain('转码进行中')
  })

  it('does not start a transcode when the user declines and prevents deleting source-deleted rows', async () => {
    confirmMock.mockResolvedValueOnce(false)
    await mount()
    root.querySelector('tbody tr .row-actions .action-button').click()
    await flushUi()
    expect(apiMock.adminStartSourceTranscode).not.toHaveBeenCalled()

    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'MANAGED', content: [pendingSource(1, true)], total: 1, totalPages: 1 })
    await mount()
    expect(root.querySelector('tbody tr input[type="checkbox"]').disabled).toBe(true)
    expect(root.querySelector('tbody tr .action-button.danger-text').disabled).toBe(true)
    expect(apiMock.adminDeleteSources).not.toHaveBeenCalled()
  })

  it('guards hidden actions for active jobs and empty selections before asking for confirmation', async () => {
    await mount()
    const actions = app._instance.setupState

    await actions.prioritize(pendingSource(99))
    await actions.deleteSelected()
    await actions.transcodeSelected()
    expect(apiMock.adminPrioritizeSourceTranscode).not.toHaveBeenCalled()
    expect(apiMock.adminDeleteSources).not.toHaveBeenCalled()
    expect(confirmMock).not.toHaveBeenCalled()

    actions.progress.running = true
    await actions.transcodeAll()
    await actions.cleanupImported()
    await actions.transcodeOne(pendingSource(99))
    expect(apiMock.adminStartSourceTranscode).not.toHaveBeenCalled()
    expect(apiMock.adminCleanupImportedSources).not.toHaveBeenCalled()

    actions.progress.running = false
    actions.cleaning = true
    await actions.cleanupImported()
    actions.cleaning = false

    actions.selected = [1]
    confirmMock.mockResolvedValueOnce(false)
    await actions.transcodeSelected()
    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(apiMock.adminStartSourceTranscode).not.toHaveBeenCalled()
  })

  it('replaces an existing progress interval when polling is restarted', async () => {
    await mount()
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    try {
      app._instance.setupState.startProgressPolling()
      app._instance.setupState.startProgressPolling()

      expect(clearIntervalSpy).toHaveBeenCalledTimes(1)
    } finally {
      clearIntervalSpy.mockRestore()
    }
  })

  it('prioritizes eligible work and updates the visible progress state', async () => {
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: true, total: 2, completed: 0, currentRecordId: 2, priorityRecordIds: [] })
    await mount()
    root.querySelector('tbody tr .priority-text').click()
    await flushUi()

    expect(apiMock.adminPrioritizeSourceTranscode).toHaveBeenCalledWith(1)
    expect(root.querySelector('tbody tr .priority-text').textContent).toContain('已插队')
    expect(root.querySelector('tbody tr .priority-text').disabled).toBe(true)
  })

  it('shows list failures and retries the list without surfacing progress polling errors', async () => {
    apiMock.adminSourceLibrary.mockRejectedValueOnce(new Error('source list unavailable'))
    apiMock.adminSourceTranscodeProgress.mockRejectedValueOnce(new Error('progress endpoint unavailable'))
    await mount()

    expect(root.querySelector('[role="alert"]').textContent).toContain('source list unavailable')
    expect(root.textContent).not.toContain('progress endpoint unavailable')
    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'MANAGED', content: [pendingSource(9)], total: 1, totalPages: 1 })
    root.querySelector('.error-notice .text-btn').click()
    await flushUi()

    expect(root.querySelector('[role="alert"]')).toBeNull()
    expect(root.textContent).toContain('song-9.rmvb')
  })

  it('uses a fallback message when the source-list error has no message', async () => {
    apiMock.adminSourceLibrary.mockRejectedValueOnce(new Error(''))
    await mount()

    expect(root.querySelector('[role="alert"]').textContent).toContain('加载源素材失败')
  })

  it('silently ignores an aborted list request while retaining the last loaded rows', async () => {
    await mount()
    apiMock.adminSourceLibrary.mockRejectedValueOnce(Object.assign(new Error('request aborted'), { name: 'AbortError' }))

    root.querySelector('.filter-actions .primary').click()
    await flushUi()

    expect(root.querySelector('[role="alert"]')).toBeNull()
    expect(root.textContent).toContain('song-1.rmvb')
    expect(root.textContent).toContain('song-2.rmvb')
  })

  it('keeps the newest source list when an older search response arrives late', async () => {
    let resolveOldRequest
    await mount()
    apiMock.adminSourceLibrary.mockReturnValueOnce(new Promise(resolve => { resolveOldRequest = resolve }))

    root.querySelector('.filter-actions .primary').click()
    await flushUi()
    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'MANAGED', content: [pendingSource(9)], total: 1, totalPages: 1 })
    root.querySelector('.filter-actions .primary').click()
    await flushUi()
    resolveOldRequest({ libraryMode: 'MANAGED', content: [pendingSource(8)], total: 1, totalPages: 1 })
    await flushUi()

    expect(root.textContent).toContain('song-9.rmvb')
    expect(root.textContent).not.toContain('song-8.rmvb')
    expect(root.querySelector('[role="alert"]')).toBeNull()
  })

  it('ignores a stale search error after a newer source list has loaded', async () => {
    let rejectOldRequest
    await mount()
    apiMock.adminSourceLibrary.mockReturnValueOnce(new Promise((_, reject) => { rejectOldRequest = reject }))

    root.querySelector('.filter-actions .primary').click()
    await flushUi()
    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'MANAGED', content: [pendingSource(9)], total: 1, totalPages: 1 })
    root.querySelector('.filter-actions .primary').click()
    await flushUi()
    rejectOldRequest(new Error('stale search failed'))
    await flushUi()

    expect(root.textContent).toContain('song-9.rmvb')
    expect(root.querySelector('[role="alert"]')).toBeNull()
  })

  it('applies filters, resets them, pages results, and selects only non-deleted rows for batch transcode', async () => {
    apiMock.adminSourceLibrary.mockImplementation(async params => ({
      libraryMode: 'MANAGED', content: [pendingSource(1), pendingSource(2), pendingSource(3, true)],
      total: 3, totalPages: 3, ...params
    }))
    await mount()

    const keyword = root.querySelector('input[placeholder="歌名、歌手或文件名"]')
    keyword.value = '  singer  '
    keyword.dispatchEvent(new Event('input', { bubbles: true }))
    keyword.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    await flushUi()
    expect(apiMock.adminSourceLibrary).toHaveBeenLastCalledWith(
      { keyword: 'singer', status: '', formatAnalysis: '', page: 0, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    const selects = root.querySelectorAll('.filter-panel select')
    selects[0].value = 'failed'
    selects[0].dispatchEvent(new Event('change', { bubbles: true }))
    selects[1].value = 'TRANSCODE_REQUIRED'
    selects[1].dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(apiMock.adminSourceLibrary).toHaveBeenLastCalledWith(
      { keyword: 'singer', status: 'failed', formatAnalysis: 'TRANSCODE_REQUIRED', page: 0, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    root.querySelector('.pager button:last-child').click()
    await flushUi()
    expect(apiMock.adminSourceLibrary).toHaveBeenLastCalledWith(
      { keyword: 'singer', status: 'failed', formatAnalysis: 'TRANSCODE_REQUIRED', page: 1, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    root.querySelector('.pager button:last-child').click()
    await flushUi()
    expect(apiMock.adminSourceLibrary).toHaveBeenLastCalledWith(
      { keyword: 'singer', status: 'failed', formatAnalysis: 'TRANSCODE_REQUIRED', page: 2, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    root.querySelector('.pager button:first-child').click()
    await flushUi()
    expect(apiMock.adminSourceLibrary).toHaveBeenLastCalledWith(
      { keyword: 'singer', status: 'failed', formatAnalysis: 'TRANSCODE_REQUIRED', page: 1, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    root.querySelector('.filter-actions .secondary').click()
    await flushUi()
    expect(apiMock.adminSourceLibrary).toHaveBeenLastCalledWith(
      { keyword: '', status: '', formatAnalysis: '', page: 0, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    root.querySelector('[aria-label="全选当前页"]').click()
    await nextTick()
    expect(root.querySelector('[aria-label="选择 song-3.rmvb"]').disabled).toBe(true)
    expect(root.querySelector('.batch-actions .secondary').textContent).toContain('批量转码（2）')

    root.querySelector('[aria-label="全选当前页"]').click()
    await nextTick()
    expect(root.querySelector('.batch-actions .danger').disabled).toBe(true)
    root.querySelector('[aria-label="全选当前页"]').click()
    await nextTick()
    expect(root.querySelector('.batch-actions .danger').textContent).toContain('批量删除（2）')

    root.querySelector('[aria-label="选择 song-2.rmvb"]').click()
    await nextTick()
    expect(root.querySelector('.batch-actions .secondary').textContent).toContain('批量转码（1）')
    root.querySelector('.batch-actions .secondary').click()
    await flushUi()
    expect(apiMock.adminStartSourceTranscode).toHaveBeenCalledWith([1])
  })

  it('maps every supported source status and unknown display values', async () => {
    const values = [
      ['AUTO_COPIED', '已移动入库', 'green'], ['TRANSCODED', '已转码入库', 'green'],
      ['PENDING_TRANSCODE', '待转码', 'blue'], ['DUPLICATE', '重复跳过', 'amber'],
      ['UNRECOGNIZED', '未识别', 'red'], ['FAILED', '失败', 'red'], ['CUSTOM', 'CUSTOM', 'neutral'],
      [undefined, '待处理', 'neutral']
    ]
    apiMock.adminSourceLibrary.mockResolvedValueOnce({
      libraryMode: 'MANAGED', content: values.map(([displayStatus], index) => ({
        ...pendingSource(index + 1), displayStatus, transcodeRequired: false,
        mediaType: ['KTV_VIDEO', 'MV', 'AUDIO', 'OTHER'][index % 4],
        videoCodec: 'h264', audioCodec: 'aac', sourceFormat: 'mp4'
      })), total: values.length, totalPages: 1
    })
    await mount()

    const rows = root.querySelectorAll('tbody tr')
    values.forEach(([status, label, color], index) => {
      const cell = rows[index].querySelector('td:nth-child(6) .status')
      expect(cell.textContent).toBe(label)
      expect(cell.classList.contains(color)).toBe(true)
    })
    expect(root.textContent).toContain('KTV 视频')
    expect(root.textContent).toContain('MV')
    expect(root.textContent).toContain('音频')
    expect(root.textContent).toContain('未知格式')
    expect(root.textContent).toContain('h264 / aac / mp4')
  })

  it('reports batch transcode startup errors and cleanup result counts', async () => {
    apiMock.adminStartSourceTranscode.mockRejectedValueOnce(new Error('transcode queue unavailable'))
    await mount()
    root.querySelector('.header-actions .primary').click()
    await flushUi()
    expect(apiMock.adminStartSourceTranscode).toHaveBeenCalledWith([], true)
    expect(alertMock).toHaveBeenCalledWith('transcode queue unavailable')

    apiMock.adminCleanupImportedSources.mockResolvedValue({ deleted: 2, skipped: 3, failed: 1 })
    apiMock.adminSourceLibrary.mockResolvedValue({ libraryMode: 'MANAGED', content: [pendingSource(4)], total: 1, totalPages: 1 })
    root.querySelector('.cleanup-button').click()
    await flushUi()

    expect(apiMock.adminCleanupImportedSources).toHaveBeenCalledOnce()
    expect(apiMock.adminSourceLibrary).toHaveBeenCalledTimes(2)
    expect(alertMock).toHaveBeenLastCalledWith(
      '清理完成：删除 2 个源文件，跳过 3 个，不符合安全条件或无需清理；失败 1 个。请重新扫描原始音乐路径，以同步目录中的最新文件。',
      { title: '自动清理完成', tone: 'warning' }
    )
  })

  it('does not start full-library transcode after the confirmation is declined', async () => {
    confirmMock.mockResolvedValueOnce(false)
    await mount()

    root.querySelector('.header-actions .primary').click()
    await flushUi()

    expect(confirmMock).toHaveBeenCalledWith('将处理全部待转码和转码失败的源文件。', { title: '批量转码所有歌曲' })
    expect(apiMock.adminStartSourceTranscode).not.toHaveBeenCalled()
  })

  it('starts full-library transcode after confirmation and reports active progress', async () => {
    await mount()

    root.querySelector('.header-actions .primary').click()
    await flushUi()

    expect(confirmMock).toHaveBeenCalledWith(
      '将处理全部待转码和转码失败的源文件。', { title: '批量转码所有歌曲' }
    )
    expect(apiMock.adminStartSourceTranscode).toHaveBeenCalledWith([], true)
    expect(root.querySelector('.header-actions .primary').textContent).toContain('转码进行中')
  })

  it('reports zero cleanup counts with a success tone and uses a fallback error message', async () => {
    apiMock.adminCleanupImportedSources.mockResolvedValueOnce({})
    await mount()
    root.querySelector('.cleanup-button').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith(
      '清理完成：删除 0 个源文件，跳过 0 个，不符合安全条件或无需清理；失败 0 个。请重新扫描原始音乐路径，以同步目录中的最新文件。',
      { title: '自动清理完成', tone: 'success' }
    )
  })

  it('uses a fallback message when cleanup rejects without an error message', async () => {
    apiMock.adminCleanupImportedSources.mockRejectedValueOnce({})
    await mount()
    root.querySelector('.cleanup-button').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('自动清理失败')
  })

  it('keeps cleanup and deletion behind confirmation and reports confirmed operation failures', async () => {
    confirmMock.mockResolvedValueOnce(false)
    await mount()
    root.querySelector('.cleanup-button').click()
    await flushUi()
    expect(apiMock.adminCleanupImportedSources).not.toHaveBeenCalled()

    apiMock.adminCleanupImportedSources.mockRejectedValueOnce(new Error('cleanup rejected'))
    root.querySelector('.cleanup-button').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('cleanup rejected')

    confirmMock.mockResolvedValueOnce(false)
    root.querySelector('tbody tr .danger-text').click()
    await flushUi()
    expect(apiMock.adminDeleteSources).not.toHaveBeenCalled()

    apiMock.adminDeleteSources.mockRejectedValueOnce(new Error('delete rejected'))
    root.querySelector('tbody tr .danger-text').click()
    await flushUi()
    expect(apiMock.adminDeleteSources).toHaveBeenCalledWith([1])
    expect(alertMock).toHaveBeenCalledWith('delete rejected')
  })

  it('reloads the source list after a confirmed single-source deletion', async () => {
    await mount()
    apiMock.adminSourceLibrary.mockResolvedValueOnce({
      libraryMode: 'MANAGED', content: [pendingSource(2)], total: 1, totalPages: 1
    })

    await app._instance.setupState.deleteOne(pendingSource(1))

    expect(confirmMock).toHaveBeenCalledWith('/source/song-1.rmvb', { title: '删除源文件', tone: 'warning' })
    expect(apiMock.adminDeleteSources).toHaveBeenCalledWith([1])
    expect(root.textContent).toContain('song-2.rmvb')
    expect(root.textContent).not.toContain('song-1.rmvb')
  })

  it('deletes only confirmed selected ids and reports prioritization failures', async () => {
    apiMock.adminSourceTranscodeProgress.mockResolvedValue({
      running: true, total: 2, completed: 0, currentRecordId: 2, priorityRecordIds: []
    })
    apiMock.adminPrioritizeSourceTranscode.mockRejectedValueOnce(new Error('priority rejected'))
    await mount()

    root.querySelector('tbody tr .priority-text').click()
    await flushUi()
    expect(apiMock.adminPrioritizeSourceTranscode).toHaveBeenCalledWith(1)
    expect(alertMock).toHaveBeenCalledWith('priority rejected')

    apiMock.adminSourceTranscodeProgress.mockResolvedValue({ running: false, total: 0, completed: 0 })
    app.unmount()
    app = null
    await mount()
    root.querySelector('[aria-label="全选当前页"]').click()
    await nextTick()
    confirmMock.mockResolvedValueOnce(false)
    root.querySelector('.batch-actions .danger').click()
    await flushUi()
    expect(apiMock.adminDeleteSources).not.toHaveBeenCalled()

    apiMock.adminDeleteSources.mockResolvedValueOnce({})
    root.querySelector('.batch-actions .danger').click()
    await flushUi()
    expect(apiMock.adminDeleteSources).toHaveBeenCalledWith([1, 2])
    expect(root.querySelector('.batch-actions .danger').disabled).toBe(true)
  })

  it('renders incomplete source metadata and active transcode progress with safe defaults', async () => {
    const incomplete = {
      ...pendingSource(4), parsedTitle: '', parsedArtist: '', mediaType: 'OTHER', transcodeRequired: false,
      duplicate: true, reason: 'duplicate source', displayStatus: 'UNRECOGNIZED', sourceMd5: null, outputMd5: null
    }
    apiMock.adminSourceLibrary.mockResolvedValueOnce({ libraryMode: 'MANAGED', content: [incomplete], total: 1, totalPages: 1 })
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({
      running: true, total: 4, completed: 1, transcoded: 1, skippedSourceDuplicate: 2,
      skippedOutputDuplicate: 1, failed: 1, currentFile: 'synthetic-current.mp4', currentRecordId: 4
    })
    await mount()

    expect(root.querySelector('.header-actions .primary').textContent).toContain('转码进行中')
    expect(root.querySelector('.progress-value').textContent).toBe('25%')
    expect(root.querySelector('.progress-panel').textContent).toContain('重复跳过 3')
    expect(root.querySelector('.progress-panel .current').textContent).toContain('synthetic-current.mp4')
    const row = root.querySelector('tbody tr')
    expect(row.textContent).toContain('未识别')
    expect(row.textContent).toContain('未知歌手')
    expect(row.textContent).toContain('未知格式')
    expect(row.textContent).toContain('可直拷')
    expect(row.textContent).toContain('重复')
    expect(row.textContent).toContain('duplicate source')
    expect(row.textContent).toContain('源：—')
    expect(row.querySelector('.row-actions .action-button').disabled).toBe(true)
  })

  it('renders codec values and the pager fallback for a zero-page state', async () => {
    await mount()
    const actions = app._instance.setupState
    actions.rows = [{
      ...pendingSource(3), transcodeRequired: false, displayStatus: 'TRANSCODED',
      videoCodec: 'h264', audioCodec: 'aac', sourceFormat: 'MP4'
    }, {
      ...pendingSource(4), transcodeRequired: false, displayStatus: 'TRANSCODED',
      videoCodec: 'h264', audioCodec: 'aac', sourceFormat: null
    }]
    actions.total = 1
    actions.page = 0
    actions.totalPages = 0
    await nextTick()

    const rows = root.querySelectorAll('tbody tr')
    expect(rows[0].querySelector('td:nth-child(4)').textContent).toContain('h264 / aac / MP4')
    expect(rows[0].querySelector('td:nth-child(4) .status').textContent).toBe('可直拷')
    expect(rows[1].querySelector('td:nth-child(4)').textContent).toContain('h264 / aac / —')
    expect(root.querySelector('.pager').textContent).toContain('第 1 / 1 页')
  })

  it('reports single and selected transcode failures and keeps the selected identity', async () => {
    apiMock.adminStartSourceTranscode.mockRejectedValueOnce(new Error('single transcode rejected'))
    await mount()

    root.querySelector('tbody tr .row-actions .action-button').click()
    await flushUi()
    expect(apiMock.adminStartSourceTranscode).toHaveBeenCalledWith([1])
    expect(alertMock).toHaveBeenCalledWith('single transcode rejected')

    root.querySelector('[aria-label="选择 song-1.rmvb"]').click()
    await nextTick()
    apiMock.adminStartSourceTranscode.mockRejectedValueOnce(new Error('batch transcode rejected'))
    root.querySelector('.batch-actions .secondary').click()
    await flushUi()

    expect(apiMock.adminStartSourceTranscode).toHaveBeenLastCalledWith([1])
    expect(alertMock).toHaveBeenLastCalledWith('batch transcode rejected')
    expect(root.querySelector('.batch-actions .secondary').textContent).toContain('批量转码（1）')
  })

  it('uses fallback messages when transcode and prioritization errors have no message', async () => {
    apiMock.adminStartSourceTranscode.mockRejectedValueOnce({})
    await mount()
    root.querySelector('.header-actions .primary').click()
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('全量转码启动失败')

    await mount()
    root.querySelector('[aria-label="选择 song-1.rmvb"]').click()
    await nextTick()
    apiMock.adminStartSourceTranscode.mockRejectedValueOnce({})
    root.querySelector('.batch-actions .secondary').click()
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('批量转码启动失败')

    await mount()
    apiMock.adminStartSourceTranscode.mockRejectedValueOnce({})
    root.querySelector('tbody tr .row-actions .action-button').click()
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('转码启动失败')

    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({
      running: true, total: 2, completed: 0, currentRecordId: 2, priorityRecordIds: []
    })
    await mount()
    apiMock.adminPrioritizeSourceTranscode.mockRejectedValueOnce({})
    root.querySelector('tbody tr .priority-text').click()
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('插队失败')
  })

  it('uses fallback messages when single and batch deletion errors have no message', async () => {
    apiMock.adminDeleteSources.mockRejectedValueOnce({})
    await mount()
    root.querySelector('tbody tr .danger-text').click()
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('删除失败')

    await mount()
    root.querySelector('[aria-label="全选当前页"]').click()
    await nextTick()
    apiMock.adminDeleteSources.mockRejectedValueOnce({})
    root.querySelector('.batch-actions .danger').click()
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('批量删除失败')
    expect(root.querySelector('.batch-actions .danger').textContent).toContain('批量删除（2）')
  })

  it('retains checked source ids when a confirmed batch deletion fails', async () => {
    apiMock.adminDeleteSources.mockRejectedValueOnce(new Error('batch deletion rejected'))
    await mount()
    root.querySelector('[aria-label="全选当前页"]').click()
    await nextTick()
    root.querySelector('.batch-actions .danger').click()
    await flushUi()

    expect(apiMock.adminDeleteSources).toHaveBeenCalledWith([1, 2])
    expect(alertMock).toHaveBeenCalledWith('batch deletion rejected')
    expect(root.querySelector('.batch-actions .danger').textContent).toContain('批量删除（2）')
    expect(root.querySelector('.batch-actions .danger').disabled).toBe(false)
  })

  it('stops progress polling and reloads the source list after the active transcode completes', async () => {
    let poll
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 77
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    apiMock.adminSourceTranscodeProgress
      .mockResolvedValueOnce({ running: true, total: 2, completed: 1, currentFile: 'synthetic.mp4' })
      .mockResolvedValueOnce({ running: false, total: 2, completed: 2, transcoded: 2 })
    await mount()

    expect(setIntervalSpy).toHaveBeenCalledOnce()
    expect(typeof poll).toBe('function')
    await poll()
    await flushUi()

    expect(clearIntervalSpy).toHaveBeenCalledWith(77)
    expect(apiMock.adminSourceLibrary).toHaveBeenCalledTimes(2)
    expect(root.querySelector('.progress-panel').textContent).toContain('2 / 2')
    expect(root.querySelector('.header-actions .primary').disabled).toBe(false)

    app.unmount()
    app = null
    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
  })

  it('coalesces overlapping progress polls into one follow-up request', async () => {
    let poll
    let retry
    let resolvePendingPoll
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 78
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: true, total: 2, completed: 0 })
    await mount()
    const setTimeoutSpy = vi.spyOn(window, 'setTimeout').mockImplementation(callback => {
      retry = callback
      return 79
    })
    apiMock.adminSourceTranscodeProgress.mockReturnValueOnce(new Promise(resolve => { resolvePendingPoll = resolve }))

    const pendingPoll = poll()
    await poll()
    expect(apiMock.adminSourceTranscodeProgress).toHaveBeenCalledTimes(2)

    resolvePendingPoll({ running: true, total: 2, completed: 1 })
    await pendingPoll
    await flushUi()
    expect(typeof retry).toBe('function')
    retry()
    await flushUi()

    expect(apiMock.adminSourceTranscodeProgress).toHaveBeenCalledTimes(3)
    expect(clearIntervalSpy).toHaveBeenCalledWith(78)
    expect(root.querySelector('.progress-panel')).toBeNull()

    app.unmount()
    app = null
    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
    setTimeoutSpy.mockRestore()
  })

  it('replaces a queued follow-up poll and clears it when the page unmounts', async () => {
    let poll
    let resolveFirstPoll
    let resolveSecondPoll
    const retryCallbacks = []
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 82
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: true, total: 3, completed: 0 })
    await mount()
    const setTimeoutSpy = vi.spyOn(window, 'setTimeout').mockImplementation(callback => {
      retryCallbacks.push(callback)
      return 90 + retryCallbacks.length
    })
    const clearTimeoutSpy = vi.spyOn(window, 'clearTimeout')

    apiMock.adminSourceTranscodeProgress.mockReturnValueOnce(new Promise(resolve => { resolveFirstPoll = resolve }))
    const firstPoll = poll()
    await poll()
    resolveFirstPoll({ running: true, total: 3, completed: 1 })
    await firstPoll
    await flushUi()
    expect(retryCallbacks).toHaveLength(1)

    apiMock.adminSourceTranscodeProgress.mockReturnValueOnce(new Promise(resolve => { resolveSecondPoll = resolve }))
    const secondPoll = poll()
    await poll()
    resolveSecondPoll({ running: true, total: 3, completed: 2 })
    await secondPoll
    await flushUi()

    expect(clearTimeoutSpy).toHaveBeenCalledWith(91)
    expect(retryCallbacks).toHaveLength(2)
    app.unmount()
    app = null
    expect(clearIntervalSpy).toHaveBeenCalledWith(82)
    expect(clearTimeoutSpy).toHaveBeenCalledWith(92)

    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
    setTimeoutSpy.mockRestore()
    clearTimeoutSpy.mockRestore()
  })

  it('retains the last known progress when a background poll fails', async () => {
    let poll
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 80
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: true, total: 4, completed: 1 })
    await mount()
    apiMock.adminSourceTranscodeProgress.mockRejectedValueOnce(new Error('temporary poll error'))

    await poll()
    await flushUi()

    expect(root.querySelector('.progress-panel').textContent).toContain('1 / 4')
    expect(root.querySelector('[role="alert"]')).toBeNull()
    expect(clearIntervalSpy).not.toHaveBeenCalledWith(80)

    app.unmount()
    app = null
    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
  })

  it('ignores an in-flight progress response after the page unmounts', async () => {
    let poll
    let resolvePoll
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 81
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: true, total: 3, completed: 1 })
    await mount()
    apiMock.adminSourceTranscodeProgress.mockReturnValueOnce(new Promise(resolve => { resolvePoll = resolve }))

    const pendingPoll = poll()
    await nextTick()
    app.unmount()
    app = null
    resolvePoll({ running: false, total: 3, completed: 3 })
    await pendingPoll
    await flushUi()

    expect(clearIntervalSpy).toHaveBeenCalledWith(81)
    expect(apiMock.adminSourceLibrary).toHaveBeenCalledTimes(1)

    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
  })

  async function mount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(SourceLibraryView)
    app.mount(root)
    await flushUi()
  }
})

function pendingSource(id, sourceDeleted = false) {
  return {
    id, sourceFilename: `song-${id}.rmvb`, sourcePath: `/source/song-${id}.rmvb`,
    sourceDeleted, sourceMd5: '1234567890abcdef1234567890abcdef', outputMd5: null,
    parsedTitle: `歌曲 ${id}`, parsedArtist: '歌手', mediaType: 'KTV_VIDEO',
    transcodeRequired: true, displayStatus: 'PENDING_TRANSCODE', sourceFormat: 'RMVB'
  }
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
