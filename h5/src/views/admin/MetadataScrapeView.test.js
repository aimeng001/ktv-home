// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, nextTick } from 'vue'

const { apiMock, alertMock, confirmMock, routeMock, routerMock } = vi.hoisted(() => ({
  apiMock: {
    adminMusicSourceConfig: vi.fn(), adminLatestMetadataScrape: vi.fn(), adminStartMetadataScrape: vi.fn(),
    adminMetadataScrape: vi.fn(), adminPauseMetadataScrape: vi.fn(), adminResumeMetadataScrape: vi.fn(),
    adminRetryMetadataScrapeItem: vi.fn(), adminSong: vi.fn(), adminSongExternalMatches: vi.fn(),
    lyricText: vi.fn(), adminApplyManualMetadata: vi.fn(), adminApplyExternalMatch: vi.fn(),
    adminApplyMetadataScrapeItem: vi.fn(), adminEditSong: vi.fn()
  },
  alertMock: vi.fn(), confirmMock: vi.fn(), routeMock: { query: {} }, routerMock: { push: vi.fn() }
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock }))
vi.mock('../../composables/useDialog', () => ({ alertDialog: alertMock, confirmDialog: confirmMock }))
vi.mock('./AdminLayout.vue', () => ({ default: defineComponent({ template: '<div class="admin-layout"><slot /></div>' }) }))

import MetadataScrapeView from './MetadataScrapeView.vue'

describe('MetadataScrapeView', () => {
  let app
  let root

  beforeEach(() => {
    vi.clearAllMocks()
    routeMock.query = {}
    apiMock.adminMusicSourceConfig.mockResolvedValue({ autoApplyThreshold: 0.9 })
    apiMock.adminLatestMetadataScrape.mockResolvedValue({ exists: false })
    apiMock.adminStartMetadataScrape.mockResolvedValue({ batchId: 'batch-2', mode: 'SELECTED', status: 'RUNNING', total: 2, completed: 0, autoApplyThreshold: 0.9, items: [] })
    apiMock.adminMetadataScrape.mockResolvedValue({ batchId: 'batch-2', mode: 'SELECTED', status: 'RUNNING', total: 2, completed: 1, autoApplyThreshold: 0.9, items: [], page: 0, totalPages: 1 })
    apiMock.adminSong.mockResolvedValue({ id: 42, title: '当前歌名', artist: '当前歌手', language: '国语', lyricType: 'none', filePath: '/music/song42.mp4' })
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [] })
    apiMock.adminApplyManualMetadata.mockResolvedValue({})
    apiMock.adminEditSong.mockResolvedValue({})
    confirmMock.mockResolvedValue(true)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('initializes threshold and route-selected songs and starts only the selected batch', async () => {
    routeMock.query = { songIds: '4,5,bad,0' }
    await mount()
    expect(root.querySelector('.threshold-control strong').textContent).toBe('90%')
    expect(root.textContent).toContain('刮削已选（2）')

    root.querySelector('.launch-actions .secondary').click()
    await flushUi()
    expect(apiMock.adminStartMetadataScrape).toHaveBeenCalledWith({ all: false, songIds: [4, 5], autoApplyThreshold: 0.9 })
    expect(root.textContent).toContain('刮削中')
  })

  it('keeps a single route-selected song in selection mode unless review is requested', async () => {
    routeMock.query = { songIds: '42' }

    await mount()

    expect(root.querySelector('.launch-actions .secondary').textContent).toContain('刮削已选（1）')
    expect(apiMock.adminSong).not.toHaveBeenCalled()
    expect(root.querySelector('.review-modal')).toBeNull()
  })

  it('keeps route-selected scraping available when automatic review cannot load the song', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSong.mockRejectedValueOnce(new Error('song unavailable'))

    await mount()

    expect(apiMock.adminSong).toHaveBeenCalledWith(42)
    expect(apiMock.adminLatestMetadataScrape).toHaveBeenCalledOnce()
    expect(root.querySelector('.launch-actions .secondary').textContent).toContain('刮削已选（1）')
    expect(root.querySelector('.review-modal')).toBeNull()
  })

  it('requires confirmation for all-song scraping and routes missing providers to metadata settings', async () => {
    await mount()
    confirmMock.mockResolvedValueOnce(false)
    root.querySelector('.launch-actions .primary').click()
    await flushUi()
    expect(apiMock.adminStartMetadataScrape).not.toHaveBeenCalled()

    const error = Object.assign(new Error('platforms missing'), { code: 'MUSIC_SOURCES_NOT_CONFIGURED' })
    apiMock.adminStartMetadataScrape.mockRejectedValueOnce(error)
    root.querySelector('.launch-actions .primary').click()
    await flushUi()
    expect(confirmMock).toHaveBeenCalledTimes(2)
    expect(alertMock).toHaveBeenCalledWith('请先在系统设置中启用至少一个元数据平台。')
    expect(routerMock.push).toHaveBeenCalledWith({ name: 'admin-settings', query: { section: 'metadata' } })
  })

  it('loads standalone matches, supports manual fields, and writes only selected metadata', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    await mount()
    await flushUi()
    expect(apiMock.adminSong).toHaveBeenCalledWith(42)
    expect(apiMock.adminSongExternalMatches).toHaveBeenCalledWith(42, true, '当前歌名 当前歌手', [])
    expect(root.textContent).toContain('song42.mp4')

    root.querySelector('.manual-candidate').click()
    await nextTick()
    const title = root.querySelector('.review-input')
    title.value = '人工确认歌名'
    title.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminApplyManualMetadata).toHaveBeenCalledWith(42, ['title'], { title: '人工确认歌名' })
    expect(apiMock.adminApplyExternalMatch).not.toHaveBeenCalled()
    expect(root.querySelector('.review-modal')).toBeNull()
  })

  it('shows the latest completed task and retries failed items after refresh', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue({
      exists: true, batchId: 'old-batch', mode: 'ALL', status: 'COMPLETED', createdAt: '2026-01-01T00:00:00Z',
      total: 1, completed: 1, autoApplyThreshold: 0.8, autoApplied: 0, review: 0, failed: 1, processing: 0, pending: 0,
      page: 0, totalPages: 1, items: [{ id: 3, songId: 42, title: '失败歌曲', artist: '歌手', status: 'FAILED', error: '平台超时' }]
    })
    apiMock.adminMetadataScrape.mockResolvedValue({
      batchId: 'old-batch', mode: 'ALL', status: 'COMPLETED', createdAt: '2026-01-01T00:00:00Z',
      total: 1, completed: 1, autoApplyThreshold: 0.8, autoApplied: 0, review: 0, failed: 1, processing: 0, pending: 0,
      page: 0, totalPages: 1, items: [{ id: 3, songId: 42, title: '失败歌曲', artist: '歌手', status: 'FAILED', error: '平台超时' }]
    })
    apiMock.adminRetryMetadataScrapeItem.mockResolvedValue({})
    await mount()
    expect(root.textContent).toContain('80%')
    expect(root.textContent).toContain('平台超时')
    root.querySelector('[aria-label="重试刮削"]').click()
    await flushUi()
    expect(apiMock.adminRetryMetadataScrapeItem).toHaveBeenCalledWith('old-batch', 3)
    expect(apiMock.adminMetadataScrape).toHaveBeenCalledWith('old-batch', { status: '', page: 0, size: 20 })
  })

  it('uses the default threshold and reports a latest-task load error when setup APIs fail', async () => {
    routeMock.query = { songIds: 'bad,0,-2,1.5' }
    apiMock.adminMusicSourceConfig.mockRejectedValue(new Error('settings offline'))
    apiMock.adminLatestMetadataScrape.mockRejectedValue(new Error('task service offline'))

    await mount()

    expect(root.querySelector('.threshold-control strong').textContent).toBe('95%')
    expect(root.querySelector('.launch-actions .secondary')).toBeNull()
    expect(apiMock.adminSong).not.toHaveBeenCalled()
    expect(alertMock).toHaveBeenCalledWith('task service offline')
    expect(root.querySelector('.empty-state')).not.toBeNull()
  })

  it('uses the fallback message when latest task loading fails without a message', async () => {
    apiMock.adminLatestMetadataScrape.mockRejectedValueOnce({})

    await mount()

    expect(alertMock).toHaveBeenCalledWith('刮削任务加载失败')
    expect(root.querySelector('.empty-state')).not.toBeNull()
  })

  it('starts an all-song scrape after confirmation and clears the busy state after failure', async () => {
    confirmMock.mockResolvedValue(true)
    apiMock.adminStartMetadataScrape.mockRejectedValue(new Error('queue unavailable'))

    await mount()
    root.querySelector('.launch-actions .primary').click()
    await flushUi()

    expect(apiMock.adminStartMetadataScrape).toHaveBeenCalledWith({ all: true, songIds: [], autoApplyThreshold: 0.9 })
    expect(alertMock).toHaveBeenCalledWith('queue unavailable')
    expect(root.querySelector('.launch-actions .primary').disabled).toBe(false)
  })

  it('uses the start-task fallback when the API error has no message', async () => {
    routeMock.query = { songIds: '42' }
    apiMock.adminStartMetadataScrape.mockRejectedValueOnce({})
    await mount()

    root.querySelector('.launch-actions .secondary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('刮削任务创建失败')
    expect(root.querySelector('.launch-actions .secondary').disabled).toBe(false)
    expect(apiMock.adminStartMetadataScrape).toHaveBeenCalledWith({
      all: false, songIds: [42], autoApplyThreshold: 0.9
    })
  })

  it('reports pause and resume failures while restoring the task action buttons', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ status: 'RUNNING' }))
    apiMock.adminPauseMetadataScrape.mockRejectedValue(new Error('pause rejected'))
    await mount()

    root.querySelector('.task-actions .secondary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('pause rejected')
    expect(root.querySelector('.task-actions .secondary').disabled).toBe(false)

    app.unmount()
    app = null
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ status: 'PAUSED' }))
    apiMock.adminResumeMetadataScrape.mockRejectedValue(new Error('resume rejected'))
    await mount()

    root.querySelector('.task-actions .primary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('resume rejected')
    expect(root.querySelector('.task-actions .primary').disabled).toBe(false)
  })

  it('filters task results and moves between pages with the selected filter', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ status: 'COMPLETED', totalPages: 2 }))
    apiMock.adminMetadataScrape.mockImplementation(async (_batchId, params) => taskFixture({
      status: 'COMPLETED', page: params.page, totalPages: 2, items: []
    }))
    await mount()

    root.querySelectorAll('.metrics button')[3].click()
    await flushUi()
    expect(apiMock.adminMetadataScrape).toHaveBeenLastCalledWith('batch-2', { status: 'FAILED', page: 0, size: 20 })

    root.querySelector('.pager button:last-child').click()
    await flushUi()
    expect(apiMock.adminMetadataScrape).toHaveBeenLastCalledWith('batch-2', { status: 'FAILED', page: 1, size: 20 })
    expect(root.querySelector('.pager button:first-child').disabled).toBe(false)

    root.querySelector('.pager button:first-child').click()
    await flushUi()
    expect(apiMock.adminMetadataScrape).toHaveBeenLastCalledWith('batch-2', { status: 'FAILED', page: 0, size: 20 })
  })

  it('rejects short review searches without calling the metadata provider', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    await mount()

    const input = root.querySelector('.review-search input')
    input.value = 'x'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-search').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('请输入至少 2 个字符的搜索关键词')
    expect(apiMock.adminSongExternalMatches).toHaveBeenCalledTimes(1)
  })

  it('applies a selected external match, records edited fields, and saves local language and lyric changes', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ items: [reviewItem()] }))
    apiMock.adminSong.mockResolvedValue({
      id: 42, title: '旧歌名', artist: '旧歌手', language: '国语', lyricType: 'lrc',
      coverPath: 'cover.jpg', filePath: '/music/review-song.mp4'
    })
    apiMock.lyricText.mockResolvedValue('[00:00]旧歌词')
    const qq = candidate('QQ', 'qq-1', 'QQ 推荐歌名')
    const netease = candidate('NETEASE', 'ne-1', '网易推荐歌名')
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [netease, qq] })

    await mount()
    root.querySelector('.review-action').click()
    await flushUi()

    expect(apiMock.lyricText).toHaveBeenCalledWith(42)
    expect(root.querySelector('.review-file').textContent).toContain('review-song.mp4')
    expect(root.querySelector('.cover-compare img').getAttribute('src')).toBe('/api/cover/42')
    expect(root.textContent).toContain('QQ 推荐歌名')

    const neteaseFilter = [...root.querySelectorAll('.provider-filters button')].find(button => button.textContent.includes('网易云'))
    neteaseFilter.click()
    await nextTick()
    expect(root.querySelector('.review-input').value).toBe('网易推荐歌名')

    const title = root.querySelector('.review-input')
    title.value = '人工校正歌名'
    title.dispatchEvent(new Event('input', { bubbles: true }))
    const language = root.querySelector('.library-edit select')
    language.value = '粤语'
    language.dispatchEvent(new Event('change', { bubbles: true }))
    const lyric = root.querySelector('.library-edit textarea')
    lyric.value = '[00:00]修订歌词'
    lyric.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminApplyMetadataScrapeItem).toHaveBeenCalledWith('batch-2', 7, {
      fields: ['title', 'artist', 'album', 'releaseDate', 'aliases', 'cover'],
      overrides: { title: '人工校正歌名' }, provider: 'NETEASE', externalId: 'ne-1'
    })
    expect(apiMock.adminEditSong).toHaveBeenCalledWith(42, { language: '粤语', lyricText: '[00:00]修订歌词' })
    expect(root.querySelector('.review-modal')).toBeNull()
  })

  it('completes a review item with local-only edits and reports a retry failure', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ items: [reviewItem({ status: 'FAILED' })] }))
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [] })
    apiMock.adminRetryMetadataScrapeItem.mockRejectedValue(new Error('retry rejected'))
    await mount()

    root.querySelector('[aria-label="重试刮削"]').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('retry rejected')
    expect(root.querySelector('[aria-label="重试刮削"]').disabled).toBe(false)

    root.querySelector('.review-action').click()
    await flushUi()
    const language = root.querySelector('.library-edit select')
    language.value = '英语'
    language.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminEditSong).toHaveBeenCalledWith(42, { language: '英语' })
    expect(apiMock.adminApplyMetadataScrapeItem).toHaveBeenCalledWith('batch-2', 7, {
      fields: [], overrides: {}, completeOnly: true
    })
    expect(root.querySelector('.review-modal')).toBeNull()
  })

  it('saves a lyric-only review edit while preserving the current language', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ items: [reviewItem()] }))
    apiMock.adminSong.mockResolvedValue({ id: 42, title: '歌名', artist: '歌手', language: '国语', lyricType: 'lrc' })
    apiMock.lyricText.mockResolvedValue('[00:00]原歌词')
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [] })
    await mount()

    root.querySelector('.review-action').click()
    await flushUi()
    const lyric = root.querySelector('.library-edit textarea')
    lyric.value = '[00:00]仅歌词修改'
    lyric.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminEditSong).toHaveBeenCalledWith(42, { language: '国语', lyricText: '[00:00]仅歌词修改' })
    expect(apiMock.adminApplyMetadataScrapeItem).toHaveBeenCalledWith('batch-2', 7, {
      fields: [], overrides: {}, completeOnly: true
    })
  })

  it('keeps review data recoverable after provider failure and ignores an unavailable lyric file', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSong.mockResolvedValue({ id: 42, title: '单曲', artist: '歌手', lyricType: 'lrc', language: null })
    apiMock.adminSongExternalMatches.mockRejectedValueOnce(new Error('metadata provider offline'))
    await mount()

    expect(alertMock).toHaveBeenCalledWith('metadata provider offline')
    expect(root.querySelector('.review-modal')).not.toBeNull()
    expect(root.querySelector('.review-loading')).toBeNull()

    apiMock.adminSongExternalMatches.mockResolvedValueOnce({ matches: [] })
    apiMock.lyricText.mockRejectedValueOnce(new Error('lyric file unavailable'))
    root.querySelector('button[title="重新搜索平台"]').click()
    await flushUi()

    expect(apiMock.lyricText).toHaveBeenCalledWith(42)
    expect(root.querySelector('.manual-candidate').classList.contains('active')).toBe(true)
    expect(root.querySelector('.library-edit select').value).toBe('未知')
    expect(alertMock).toHaveBeenCalledTimes(1)
  })

  it('shows the provider-search fallback for message-less errors and allows a retry', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSongExternalMatches
      .mockRejectedValueOnce({})
      .mockResolvedValueOnce({ matches: [candidate('QQ', 'retry-match', '重试候选')] })

    await mount()

    expect(alertMock).toHaveBeenCalledWith('平台元数据搜索失败')
    expect(root.querySelector('.review-modal')).not.toBeNull()
    expect(root.querySelector('.review-loading')).toBeNull()

    root.querySelector('button[title="重新搜索平台"]').click()
    await flushUi()

    expect(root.querySelector('.review-candidates').textContent).toContain('重试候选')
    expect(root.querySelector('.review-loading')).toBeNull()
    expect(apiMock.adminSongExternalMatches).toHaveBeenCalledTimes(2)
  })

  it('applies a standalone external match only for checked fields and preserves manual overrides', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    const match = candidate('QQ', 'external-42', '平台歌名')
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [match] })
    await mount()

    expect(root.querySelector('.review-score').textContent).toContain('QQ 音乐')
    expect(root.querySelectorAll('.review-candidates > button')).toHaveLength(2)
    const checks = [...root.querySelectorAll('.compare-row input[type="checkbox"]')]
    for (const check of checks) if (check.checked) check.click()
    checks[0].click()
    const title = root.querySelector('.review-input')
    title.value = '人工改名'
    title.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminApplyExternalMatch).toHaveBeenCalledWith(42, 'QQ', 'external-42', ['title'], { title: '人工改名' })
    expect(apiMock.adminApplyManualMetadata).not.toHaveBeenCalled()
    expect(root.querySelector('.review-modal')).toBeNull()
  })

  it('does not close an applying review and leaves it open when the metadata write fails', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ items: [reviewItem()] }))
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [candidate('QQ', 'qq-1', '推荐歌名')] })
    let finishWrite
    apiMock.adminApplyMetadataScrapeItem.mockReturnValueOnce(new Promise((_, reject) => {
      finishWrite = reject
    }))
    await mount()
    root.querySelector('.review-action').click()
    await flushUi()

    root.querySelector('.review-modal footer .primary').click()
    await nextTick()
    expect(root.querySelector('.review-modal footer .primary').disabled).toBe(true)
    root.querySelector('.mask').dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(root.querySelector('.review-modal')).not.toBeNull()

    finishWrite(new Error('metadata write rejected'))
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('metadata write rejected')
    expect(root.querySelector('.review-modal')).not.toBeNull()
    expect(root.querySelector('.review-modal footer .primary').disabled).toBe(false)
  })

  it('ignores a late provider response after switching to another review item', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ items: [
      reviewItem({ id: 7, songId: 41, title: '第一首', artist: '歌手一' }),
      reviewItem({ id: 8, songId: 42, title: '第二首', artist: '歌手二' })
    ] }))
    apiMock.adminSong.mockImplementation(async id => ({
      id, title: id === 41 ? '第一首详情' : '第二首详情', artist: '歌手', language: '国语', lyricType: 'none'
    }))
    let finishFirstSearch
    apiMock.adminSongExternalMatches.mockImplementation(songId => songId === 41
      ? new Promise(resolve => { finishFirstSearch = resolve })
      : Promise.resolve({ matches: [candidate('QQ', 'second-match', '第二首平台结果')] }))
    await mount()

    const reviewActions = root.querySelectorAll('.review-action')
    reviewActions[0].click()
    await flushUi()
    reviewActions[1].click()
    await flushUi()

    expect(root.querySelector('.review-modal .review-title p').textContent).toContain('第二首')
    expect(root.querySelector('.review-candidates').textContent).toContain('第二首平台结果')

    finishFirstSearch({ matches: [candidate('QQ', 'first-late', '过时平台结果')] })
    await flushUi()

    expect(root.querySelector('.review-modal .review-title p').textContent).toContain('第二首')
    expect(root.querySelector('.review-candidates').textContent).toContain('第二首平台结果')
    expect(root.querySelector('.review-candidates').textContent).not.toContain('过时平台结果')
  })

  it('shows pending-item fallbacks when metadata, provider, and score are absent', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({
      total: 1, pending: 1, items: [{
        id: 9, songId: 42, title: '待刮削歌曲', artist: null, status: 'PENDING',
        provider: null, score: null, result: null, error: null
      }]
    }))

    await mount()

    const row = root.querySelector('tbody tr')
    expect(row.textContent).toContain('未知歌手')
    expect(row.textContent).toContain('等待刮削')
    expect(row.textContent).toContain('—')
    expect(row.querySelector('.review-action')).toBeNull()
    expect(row.querySelector('[aria-label="重试刮削"]')).toBeNull()
  })

  it('uses a visible fallback when a provider candidate has no provider name', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [candidate('', 'missing-provider', '无平台名候选')] })
    await mount()

    expect(root.querySelector('.review-candidates > button:not(.manual-candidate) .provider').textContent).toBe('—')
    expect(root.querySelector('.review-score').textContent).toContain('当前候选：—')
  })

  it('renders provider, status, skipped-count, and suggestion fallbacks for mixed task records', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({
      skippedExisting: 3,
      total: 3,
      items: [
        {
          id: 10, songId: 42, title: '字段不完整', artist: null, status: 'FUTURE_STATUS',
          provider: 'EXPERIMENTAL', score: 0.827,
          result: { track: { title: '平台建议', artists: [], album: '' } }, error: null
        },
        {
          id: 11, songId: 43, title: '尚未开始', artist: '歌手', status: 'PENDING',
          provider: null, score: null, result: null, error: null
        },
        {
          id: 12, songId: 44, title: '候选缺少艺术家字段', artist: '歌曲艺术家', status: 'REVIEW',
          provider: 'QQ', score: 0.81, result: { track: { title: '缺字段候选' } }, error: null
        }
      ]
    }))

    await mount()

    expect(root.querySelector('.progress-copy').textContent).toContain('已跳过 3 首')
    const rows = root.querySelectorAll('tbody tr')
    expect(rows[0].textContent).toContain('平台建议')
    expect(rows[0].textContent).toContain('未知歌手')
    expect(rows[0].textContent).toContain('EXPERIMENTAL')
    expect(rows[0].textContent).toContain('82.7%')
    expect(rows[0].textContent).toContain('FUTURE_STATUS')
    expect(rows[1].textContent).toContain('等待刮削')
    expect(rows[1].textContent).toContain('—')
    expect(rows[2].textContent).toContain('缺字段候选')
    expect(rows[2].textContent).toContain('未知歌手')
  })

  it('marks a failed progress refresh stale and clears it after a manual retry', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ total: 0, completed: 0 }))
    apiMock.adminMetadataScrape
      .mockRejectedValueOnce(new Error('temporary offline'))
      .mockResolvedValueOnce(taskFixture({ total: 0, completed: 0 }))

    await mount()
    expect(root.querySelector('.progress-copy').textContent).toContain('完成 0%')

    root.querySelector('.metrics button').click()
    await flushUi()
    expect(root.querySelector('[role="alert"]').textContent).toContain('连接中断')
    expect(apiMock.adminMetadataScrape).toHaveBeenCalledTimes(1)

    root.querySelector('.stale-alert button').click()
    await flushUi()
    expect(root.querySelector('.stale-alert')).toBeNull()
    expect(apiMock.adminMetadataScrape).toHaveBeenCalledTimes(2)
    expect(apiMock.adminMetadataScrape).toHaveBeenLastCalledWith('batch-2', { status: '', page: 0, size: 20 })
  })

  it('shows the last successful refresh time when a later poll fails', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture())
    apiMock.adminMetadataScrape
      .mockResolvedValueOnce(taskFixture())
      .mockRejectedValueOnce(new Error('second refresh failed'))
    await mount()

    root.querySelector('.metrics button').click()
    await flushUi()
    root.querySelector('.metrics button').click()
    await flushUi()

    expect(root.querySelector('[role="alert"]').textContent).toContain('（')
    expect(root.querySelector('[role="alert"]').textContent).toContain('）')
    expect(root.querySelector('[role="alert"]').textContent).toContain('连接中断')
  })

  it('clears the scheduled task poll when the view unmounts', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ status: 'RUNNING' }))
    const setTimeoutSpy = vi.spyOn(window, 'setTimeout')
    const clearTimeoutSpy = vi.spyOn(window, 'clearTimeout')

    try {
      await mount()

      const pollTimerIndex = setTimeoutSpy.mock.calls.findIndex(([, delay]) => Number(delay) > 0)
      expect(pollTimerIndex).not.toBe(-1)
      const pollTimer = setTimeoutSpy.mock.results[pollTimerIndex].value

      app.unmount()
      app = null

      expect(clearTimeoutSpy).toHaveBeenCalledWith(pollTimer)
    } finally {
      setTimeoutSpy.mockRestore()
      clearTimeoutSpy.mockRestore()
    }
  })

  it('does not schedule another poll when a start request completes after the view unmounts', async () => {
    routeMock.query = { songIds: '42' }
    let finishStart
    apiMock.adminStartMetadataScrape.mockReturnValueOnce(new Promise(resolve => { finishStart = resolve }))
    const setTimeoutSpy = vi.spyOn(window, 'setTimeout')

    try {
      await mount()
      root.querySelector('.launch-actions .secondary').click()
      await nextTick()
      expect(apiMock.adminStartMetadataScrape).toHaveBeenCalledOnce()
      const scheduledPollsBeforeUnmount = setTimeoutSpy.mock.calls.filter(([, delay]) => Number(delay) > 0).length

      app.unmount()
      app = null
      finishStart(taskFixture({ status: 'RUNNING' }))
      await flushUi()

      const scheduledPollsAfterUnmount = setTimeoutSpy.mock.calls.filter(([, delay]) => Number(delay) > 0).length
      expect(scheduledPollsAfterUnmount).toBe(scheduledPollsBeforeUnmount)
    } finally {
      setTimeoutSpy.mockRestore()
    }
  })

  it('renders low-confidence and unknown batch values without treating them as known states', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({
      mode: 'LEGACY_MODE', status: 'CANCELLED', autoApplyThreshold: 0.8,
      items: [{
        id: 12, songId: 42, title: '低置信歌曲', artist: '歌手', status: 'PENDING',
        provider: 'UNLISTED_PROVIDER', score: 0.79,
        result: { track: { title: '候选歌名', artists: ['候选歌手'], album: null } }
      }]
    }))
    await mount()

    expect(root.querySelector('.section-title p').textContent).toContain('LEGACY_MODE')
    expect(root.querySelector('.batch-status').textContent).toBe('CANCELLED')
    expect(root.querySelector('tbody .score').classList.contains('low')).toBe(true)
    expect(root.querySelector('tbody .provider').textContent).toBe('UNLISTED_PROVIDER')
  })

  it('marks a score between the auto-apply threshold and the review floor as medium confidence', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({
      autoApplyThreshold: 0.9,
      items: [{
        id: 13, songId: 42, title: '中置信歌曲', artist: '歌手', status: 'REVIEW',
        provider: 'QQ', score: 0.85, result: { track: { title: '平台候选', artists: ['候选歌手'] } }
      }]
    }))

    await mount()

    expect(root.querySelector('tbody .score').textContent).toBe('85%')
    expect(root.querySelector('tbody .score').classList.contains('medium')).toBe(true)
  })

  it('formats fractional thresholds and confidence scores to one decimal place', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({
      autoApplyThreshold: 0.856,
      items: [reviewItem({ score: 0.856 })]
    }))

    await mount()

    expect(root.querySelector('.progress-copy').textContent).toContain('本批次阈值 85.6%')
    expect(root.querySelector('tbody .score').textContent).toBe('85.6%')
  })

  it('renders a zero percent fallback for a legacy task without a threshold', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ autoApplyThreshold: null }))

    await mount()

    expect(root.querySelector('.progress-copy').textContent).toContain('本批次阈值 0%')
  })

  it('uses fallback messages for pause, resume, and retry errors without message fields', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({
      status: 'RUNNING', items: [reviewItem({ status: 'FAILED' })]
    }))
    apiMock.adminPauseMetadataScrape.mockRejectedValueOnce({})
    apiMock.adminRetryMetadataScrapeItem.mockRejectedValueOnce({})
    await mount()

    root.querySelector('.task-actions .secondary').click()
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('暂停失败')

    app.unmount()
    app = null
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({
      status: 'COMPLETED', items: [reviewItem({ status: 'FAILED' })]
    }))
    await mount()
    root.querySelector('[aria-label="重试刮削"]').click()
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('重试失败')

    app.unmount()
    app = null
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ status: 'PAUSED' }))
    apiMock.adminResumeMetadataScrape.mockRejectedValueOnce({})
    await mount()
    root.querySelector('.task-actions .primary').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('继续任务失败')
  })

  it('applies unchanged standalone candidate fields without inventing overrides', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSong.mockResolvedValue({ id: 42, title: '当前歌名', artist: '歌手', language: '国语', lyricType: 'lrc' })
    apiMock.lyricText.mockResolvedValue('[00:00]原歌词')
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [candidate('QQ', 'unchanged-1', '平台歌名')] })
    await mount()

    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminApplyExternalMatch).toHaveBeenCalledWith(
      42, 'QQ', 'unchanged-1', ['title', 'artist', 'album', 'releaseDate', 'aliases', 'cover'], {}
    )
    expect(apiMock.adminEditSong).not.toHaveBeenCalled()
  })

  it('allows applying only the available cover without creating unrelated field overrides', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [candidate('QQ', 'cover-only', '封面候选')] })
    await mount()

    const checks = [...root.querySelectorAll('.compare-row input[type="checkbox"]')]
    for (const check of checks) if (check.checked) check.click()
    const coverRow = [...root.querySelectorAll('.compare-row')].find(row => row.textContent.includes('封面'))
    const coverCheck = coverRow.querySelector('input[type="checkbox"]')
    expect(coverCheck.disabled).toBe(false)
    coverCheck.click()
    await nextTick()

    const applyButton = root.querySelector('.review-modal footer .primary')
    expect(applyButton.disabled).toBe(false)
    applyButton.click()
    await flushUi()

    expect(apiMock.adminApplyExternalMatch).toHaveBeenCalledWith(42, 'QQ', 'cover-only', ['cover'], {})
    expect(apiMock.adminEditSong).not.toHaveBeenCalled()
  })

  it('uses the default threshold when the config value is null and renders the no-task state', async () => {
    apiMock.adminMusicSourceConfig.mockResolvedValue({ autoApplyThreshold: null })

    await mount()

    expect(root.querySelector('.threshold-control strong').textContent).toBe('95%')
    expect(root.querySelector('.empty-state')).not.toBeNull()
    expect(root.querySelector('.progress-track')).toBeNull()
    expect(apiMock.adminMetadataScrape).not.toHaveBeenCalled()
  })

  it('completes successful pause and resume actions from the refreshed task state', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ status: 'RUNNING' }))
    apiMock.adminPauseMetadataScrape.mockResolvedValue({})
    apiMock.adminResumeMetadataScrape.mockResolvedValue({})
    apiMock.adminMetadataScrape
      .mockResolvedValueOnce(taskFixture({ status: 'PAUSED' }))
      .mockResolvedValueOnce(taskFixture({ status: 'RUNNING' }))

    await mount()
    root.querySelector('.task-actions .secondary').click()
    await flushUi()
    expect(apiMock.adminPauseMetadataScrape).toHaveBeenCalledWith('batch-2')
    expect(root.querySelector('.task-actions .primary').textContent).toContain('继续')

    root.querySelector('.task-actions .primary').click()
    await flushUi()
    expect(apiMock.adminResumeMetadataScrape).toHaveBeenCalledWith('batch-2')
    expect(root.querySelector('.task-actions .secondary').textContent).toContain('暂停')
  })

  it('prefers the stored external match and switches candidates within provider filters', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({
      items: [reviewItem({ provider: 'QQ', externalId: 'qq-stored' })]
    }))
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [
      candidate('NETEASE', 'ne-1', '网易候选'),
      candidate('QQ', 'qq-stored', '已保存匹配')
    ] })
    await mount()

    root.querySelector('.review-action').click()
    await flushUi()
    expect(root.querySelector('.review-candidates > button.active').textContent).toContain('已保存匹配')

    const providerButton = label => [...root.querySelectorAll('.provider-filters button')]
      .find(button => button.textContent.trim() === label)
    providerButton('网易云').click()
    await nextTick()
    expect(root.querySelector('.review-candidates > button.active').textContent).toContain('网易候选')

    providerButton('QQ 音乐').click()
    await nextTick()
    expect(root.querySelector('.review-candidates > button.active').textContent).toContain('已保存匹配')

    providerButton('全部平台').click()
    const keyword = root.querySelector('.review-search input')
    keyword.value = '新搜索词'
    keyword.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-search').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushUi()
    expect(apiMock.adminSongExternalMatches).toHaveBeenLastCalledWith(42, true, '新搜索词', [])
  })

  it('limits a refreshed platform search to the selected provider', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [candidate('QQ', 'qq-1', 'QQ 候选')] })
    await mount()

    const qqFilter = [...root.querySelectorAll('.provider-filters button')]
      .find(button => button.textContent.trim() === 'QQ 音乐')
    qqFilter.click()
    await nextTick()
    root.querySelector('button[title="重新搜索平台"]').click()
    await flushUi()

    expect(apiMock.adminSongExternalMatches).toHaveBeenLastCalledWith(42, true, '当前歌名 当前歌手', ['QQ'])
  })

  it('allows standalone manual local edits when the provider returns no match list', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: null })
    await mount()

    expect(root.querySelector('.manual-candidate').classList.contains('active')).toBe(true)
    expect(root.querySelector('.review-modal footer .primary').disabled).toBe(true)
    const language = root.querySelector('.library-edit select')
    language.value = '粤语'
    language.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminEditSong).toHaveBeenCalledWith(42, { language: '粤语' })
    expect(apiMock.adminApplyManualMetadata).not.toHaveBeenCalled()
    expect(apiMock.adminApplyExternalMatch).not.toHaveBeenCalled()
    expect(apiMock.adminApplyMetadataScrapeItem).not.toHaveBeenCalled()
    expect(root.querySelector('.review-modal')).toBeNull()
  })

  it('shows lyric loading state and ignores a lyric response after closing review', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSong.mockResolvedValue({ id: 42, title: '当前歌名', artist: '当前歌手', language: '国语', lyricType: 'lrc' })
    let finishLyrics
    apiMock.lyricText.mockReturnValueOnce(new Promise(resolve => { finishLyrics = resolve }))
    await mount()

    expect(root.querySelector('.review-loading')).not.toBeNull()
    expect(root.querySelector('.library-edit textarea')).toBeNull()

    root.querySelector('button[title="关闭"]').click()
    finishLyrics(undefined)
    await flushUi()

    expect(root.querySelector('.review-modal')).toBeNull()
    expect(apiMock.lyricText).toHaveBeenCalledWith(42)
  })

  it('normalizes an empty lyric response and keeps the review unchanged', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSong.mockResolvedValue({ id: 42, title: '当前歌名', artist: '当前歌手', language: '国语', lyricType: 'lrc' })
    apiMock.lyricText.mockResolvedValueOnce(null)
    await mount()

    expect(root.querySelector('.library-edit textarea').value).toBe('')
    expect(root.querySelector('.review-modal footer .primary').disabled).toBe(true)
    expect(apiMock.adminEditSong).not.toHaveBeenCalled()
  })

  it('does not treat whitespace-only lyric edits as a local change', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSong.mockResolvedValue({ id: 42, title: '当前歌名', artist: '当前歌手', language: '国语', lyricType: 'none' })
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [] })
    await mount()

    const lyric = root.querySelector('.library-edit textarea')
    lyric.value = ' \n\t '
    lyric.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    expect(root.querySelector('.review-modal footer .primary').disabled).toBe(true)
    expect(apiMock.adminEditSong).not.toHaveBeenCalled()
    expect(apiMock.adminApplyManualMetadata).not.toHaveBeenCalled()
    expect(apiMock.adminApplyExternalMatch).not.toHaveBeenCalled()
  })

  it('does not save a lyric edit after it is restored to the original content', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSong.mockResolvedValue({ id: 42, title: '当前歌名', artist: '当前歌手', language: '国语', lyricType: 'lrc' })
    apiMock.lyricText.mockResolvedValue('[00:00]原歌词')
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [] })
    await mount()

    const lyric = root.querySelector('.library-edit textarea')
    lyric.value = '[00:00]临时修改'
    lyric.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    expect(root.querySelector('.review-modal footer .primary').disabled).toBe(false)

    lyric.value = '[00:00]原歌词'
    lyric.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    expect(root.querySelector('.review-modal footer .primary').disabled).toBe(true)
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()
    expect(apiMock.adminEditSong).not.toHaveBeenCalled()
    expect(root.querySelector('.review-modal')).not.toBeNull()
  })

  it('fills a missing candidate field manually and does not enable the unavailable cover field', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [{
      score: 0.82,
      track: { provider: 'QQ', externalId: 'partial-1', title: '候选歌名', album: null, releaseDate: null, coverUrl: null }
    }] })
    await mount()

    const candidateRow = root.querySelector('.review-candidates > button:not(.manual-candidate)')
    expect(candidateRow.textContent).toContain('未知歌手')
    expect(candidateRow.textContent).toContain('无专辑')
    candidateRow.click()
    await nextTick()

    const fields = root.querySelectorAll('.compare-row')
    const artistField = [...fields].find(row => row.textContent.includes('歌手'))
    const artistInput = artistField.querySelector('.review-input')
    expect(artistInput.value).toBe('')
    const coverField = [...fields].find(row => row.textContent.includes('封面'))
    expect(coverField.querySelector('input[type="checkbox"]').disabled).toBe(true)

    artistInput.value = '人工补充歌手'
    artistInput.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminApplyExternalMatch).toHaveBeenCalledWith(42, 'QQ', 'partial-1', ['title', 'artist'], { artist: '人工补充歌手' })
  })

  it('does not create an empty manual override for a checked candidate field', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [{
      score: 0.91,
      track: {
        provider: 'QQ', externalId: 'empty-artist', title: '候选歌名', artists: [], album: '候选专辑',
        releaseDate: '2020-01-01', aliases: ['简称'], coverUrl: null
      }
    }] })
    await mount()

    const artistRow = [...root.querySelectorAll('.compare-row')].find(row => row.textContent.includes('歌手'))
    const artistCheck = artistRow.querySelector('input[type="checkbox"]')
    expect(artistCheck.checked).toBe(false)
    artistCheck.click()
    await nextTick()

    const applyButton = root.querySelector('.review-modal footer .primary')
    expect(applyButton.disabled).toBe(false)
    applyButton.click()
    await flushUi()

    expect(apiMock.adminApplyExternalMatch).toHaveBeenCalledWith(
      42, 'QQ', 'empty-artist', ['title', 'album', 'releaseDate', 'aliases', 'artist'], {}
    )
  })

  it('lets an administrator supply a title omitted by the provider candidate', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [{
      score: 0.86,
      track: {
        provider: 'QQ', externalId: 'missing-title', title: '', artists: ['平台歌手'], album: '平台专辑',
        releaseDate: '2020-01-01', aliases: ['平台别名'], coverUrl: null
      }
    }] })
    await mount()

    const titleRow = [...root.querySelectorAll('.compare-row')].find(row => row.textContent.includes('歌名'))
    const titleCheck = titleRow.querySelector('input[type="checkbox"]')
    expect(titleCheck.checked).toBe(false)
    titleCheck.click()
    const titleInput = titleRow.querySelector('.review-input')
    titleInput.value = '人工补全歌名'
    titleInput.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminApplyExternalMatch).toHaveBeenCalledWith(
      42, 'QQ', 'missing-title', ['artist', 'album', 'releaseDate', 'aliases', 'title'], { title: '人工补全歌名' }
    )
  })

  it('applies task-level manual metadata without inventing a provider identity', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ items: [reviewItem()] }))
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [] })
    apiMock.adminApplyMetadataScrapeItem.mockResolvedValue({})
    await mount()

    root.querySelector('.review-action').click()
    await flushUi()
    const title = root.querySelector('.review-input')
    title.value = '人工填写的歌名'
    title.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(apiMock.adminApplyMetadataScrapeItem).toHaveBeenCalledWith('batch-2', 7, {
      fields: ['title'], overrides: { title: '人工填写的歌名' }, provider: null, externalId: null
    })
  })

  it('keeps a task review open and shows a fallback message when an apply error has no message', async () => {
    apiMock.adminLatestMetadataScrape.mockResolvedValue(taskFixture({ items: [reviewItem()] }))
    apiMock.adminSongExternalMatches.mockResolvedValue({ matches: [] })
    apiMock.adminApplyMetadataScrapeItem.mockRejectedValue({})
    await mount()

    root.querySelector('.review-action').click()
    await flushUi()
    const title = root.querySelector('.review-input')
    title.value = '人工填写的歌名'
    title.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal footer .primary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('元数据写入失败')
    expect(root.querySelector('.review-modal')).not.toBeNull()
    expect(root.querySelector('.review-modal footer .primary').disabled).toBe(false)
  })

  it('ignores a provider response after the review is closed', async () => {
    routeMock.query = { songIds: '42', review: '1' }
    let finishSearch
    apiMock.adminSongExternalMatches.mockReturnValueOnce(new Promise(resolve => { finishSearch = resolve }))
    await mount()
    expect(root.querySelector('.review-loading')).not.toBeNull()

    root.querySelector('button[title="关闭"]').click()
    finishSearch({ matches: [candidate('QQ', 'late-result', '过时候选')] })
    await flushUi()

    expect(root.querySelector('.review-modal')).toBeNull()
    expect(root.textContent).not.toContain('过时候选')
  })

  function taskFixture(overrides = {}) {
    return {
      exists: true, batchId: 'batch-2', mode: 'ALL', status: 'COMPLETED', createdAt: '2026-01-01T00:00:00Z',
      total: 1, completed: 1, autoApplyThreshold: 0.8, autoApplied: 0, review: 1, failed: 0, processing: 0, pending: 0,
      page: 0, totalPages: 1, items: [], ...overrides
    }
  }

  function reviewItem(overrides = {}) {
    return { id: 7, songId: 42, title: '旧歌名', artist: '旧歌手', status: 'REVIEW', provider: 'QQ', externalId: 'qq-1', ...overrides }
  }

  function candidate(provider, externalId, title) {
    return { score: 0.93, track: { provider, externalId, title, artists: ['推荐歌手'], album: '推荐专辑', releaseDate: '2020-01-01', aliases: ['简称'], coverUrl: `https://example.test/${externalId}.jpg` } }
  }

  async function mount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(MetadataScrapeView)
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
