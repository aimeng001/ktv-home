// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, nextTick } from 'vue'

const { apiMock, alertMock, routerMock } = vi.hoisted(() => ({
  apiMock: {
    adminArtistPage: vi.fn(), adminAiConfig: vi.fn(), adminAnalyzeArtist: vi.fn(),
    adminAnalyzeArtists: vi.fn(), adminApplyArtistGender: vi.fn(),
    adminScanLocalAvatars: vi.fn(), adminRetryMissingAvatars: vi.fn(),
    adminApplyArtistGenderDictionary: vi.fn(), adminUploadArtistAvatar: vi.fn()
  },
  alertMock: vi.fn(), routerMock: { push: vi.fn() }
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))
vi.mock('../../composables/useDialog', () => ({ alertDialog: alertMock }))
vi.mock('./AdminLayout.vue', () => ({ default: defineComponent({ template: '<div class="admin-layout"><slot /></div>' }) }))

import ArtistLibraryView from './ArtistLibraryView.vue'

describe('ArtistLibraryView', () => {
  let app
  let root
  let artists

  beforeEach(() => {
    vi.clearAllMocks()
    artists = [person('张三'), person('李四')]
    apiMock.adminArtistPage.mockImplementation(async params => ({ items: artists, total: artists.length, page: params.page }))
    apiMock.adminAiConfig.mockResolvedValue({ enabled: true, baseUrl: 'http://ai.local', bulkModel: 'test-model', bulkConcurrency: 2 })
    apiMock.adminAnalyzeArtist.mockResolvedValue({ gender: '男歌手', confidence: 0.88, reason: '代表歌曲判断' })
    apiMock.adminAnalyzeArtists.mockResolvedValue([{ artist: '张三', gender: '男歌手', confidence: 0.9, source: 'AI', reason: 'AI 建议' }])
    apiMock.adminApplyArtistGender.mockResolvedValue({})
    apiMock.adminScanLocalAvatars.mockResolvedValue({ matched: 2 })
    apiMock.adminRetryMissingAvatars.mockResolvedValue({ queued: 3 })
    apiMock.adminApplyArtistGenderDictionary.mockResolvedValue({ changed: 1 })
    apiMock.adminUploadArtistAvatar.mockResolvedValue({ avatarUrl: '/avatar/new.webp' })
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('loads artists, keeps selection across filtering only for visible identities, and resets filters', async () => {
    await mount()
    expect(root.textContent).toContain('张三')
    expect(root.textContent).toContain('歌曲 A')
    root.querySelector('[aria-label="选择 张三"]').click()
    await nextTick()
    expect(root.textContent).toContain('已选择')

    const keyword = root.querySelector('.keyword-field input')
    keyword.value = '李'
    keyword.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector('.filter-actions .primary').click()
    await flushUi()
    expect(apiMock.adminArtistPage).toHaveBeenLastCalledWith(
      { keyword: '李', gender: '', reviewed: '', page: 0, size: 50 },
      { signal: expect.any(AbortSignal) }
    )

    root.querySelector('.filter-actions .secondary').click()
    await flushUi()
    expect(apiMock.adminArtistPage).toHaveBeenLastCalledWith(
      { keyword: '', gender: '', reviewed: '', page: 0, size: 50 },
      { signal: expect.any(AbortSignal) }
    )
  })

  it('selects and clears the full page while keeping the header checkbox synchronized', async () => {
    await mount()
    const selectAll = root.querySelector('[aria-label="全选当前歌手"]')
    selectAll.click()
    await nextTick()
    expect(selectAll.checked).toBe(true)
    expect(root.querySelector('.stats-row article:nth-child(3) strong').textContent).toBe('2')

    root.querySelector('[aria-label="选择 张三"]').click()
    await nextTick()
    expect(selectAll.checked).toBe(false)
    expect(root.querySelector('.stats-row article:nth-child(3) strong').textContent).toBe('1')

    selectAll.click()
    await nextTick()
    expect(selectAll.checked).toBe(true)
    selectAll.click()
    await nextTick()
    expect(selectAll.checked).toBe(false)
    expect(root.querySelector('.stats-row article:nth-child(3) strong').textContent).toBe('0')
  })

  it('renders confirmed avatars and group identity labels while blocking group analysis', async () => {
    artists = [
      { ...person('已确认歌手'), gender: '女歌手', reviewed: true, avatarUrl: '/avatar/confirmed.webp' },
      { ...person('群星'), artistKind: 'VARIOUS', songs: [] },
      { ...person('未归属'), artistKind: 'UNATTRIBUTED', songs: null }
    ]
    apiMock.adminArtistPage.mockImplementation(async params => ({ items: artists, total: artists.length, page: params.page }))
    await mount()

    const rows = root.querySelectorAll('tbody tr')
    expect(rows[0].querySelector('.artist-avatar img').getAttribute('src')).toBe('/avatar/confirmed.webp')
    expect(rows[0].textContent).toContain('已人工确认')
    expect(rows[0].querySelector('.status').classList.contains('blue')).toBe(true)
    expect(rows[0].querySelector('.link').disabled).toBe(false)
    expect(rows[1].textContent).toContain('群星')
    expect(rows[1].querySelector('.link').disabled).toBe(true)
    expect(rows[2].textContent).toContain('未归属')
    expect(rows[2].textContent).toContain('暂无代表歌曲')
    expect(rows[2].querySelector('.link').disabled).toBe(true)
  })

  it('uses safe review defaults for an artist with missing optional data and ignores detached actions', async () => {
    const sparseArtist = {
      artistKey: '', name: '未完善歌手', gender: '', reviewed: false, artistKind: 'PERSON',
      songCount: 1, songs: [{ id: 3, title: '缺失语种歌曲', artist: '未完善歌手', language: '', coverUrl: null }], avatarUrl: null
    }
    const blankNameArtist = { ...sparseArtist, artistKey: '', name: '', songCount: 0, songs: [] }
    artists = [sparseArtist, blankNameArtist]
    apiMock.adminArtistPage.mockImplementation(async params => ({ items: artists, total: 1, page: params.page }))
    await mount()
    const actions = app._instance.setupState

    actions.toggleArtist(sparseArtist, true)
    expect(actions.selectedKeys.has(sparseArtist.name)).toBe(true)
    actions.toggleArtist(sparseArtist, false)
    actions.toggleAll(true)
    expect(actions.selectedKeys).toEqual(new Set([sparseArtist.name, blankNameArtist.name]))
    actions.toggleAll(false)
    expect(actions.selectedKeys.size).toBe(0)
    expect(root.querySelectorAll('.artist-avatar span')[1].textContent).toBe('?')

    actions.selectedKeys = new Set([sparseArtist.name])
    await actions.openBatch()
    expect(apiMock.adminAnalyzeArtists).toHaveBeenCalledWith([sparseArtist.name])
    actions.closeBatch()

    root.querySelector('tbody .link').click()
    await nextTick()
    expect(root.querySelector('.review-avatar span').textContent).toBe('未')
    expect(root.querySelector('.review-summary').textContent).toContain('未知')
    expect(root.querySelector('.review-summary').textContent).toContain('未分析')
    expect(root.querySelector('.reason').textContent).toContain('没有 API Key 时仍可手动选择')
    expect(root.querySelector('.sample-list').textContent).toContain('未知语种')
    expect(root.querySelector('.sample-list .cover span').textContent).toBe('♪')

    const file = new File(['avatar'], 'avatar.png', { type: 'image/png' })
    actions.selected = null
    await actions.apply()
    await actions.analyze()
    await actions.handleAvatarUpload({ target: { files: [file], value: 'unchanged' } })

    expect(apiMock.adminApplyArtistGender).not.toHaveBeenCalled()
    expect(apiMock.adminAnalyzeArtist).not.toHaveBeenCalled()
    expect(apiMock.adminUploadArtistAvatar).not.toHaveBeenCalled()
  })

  it('deduplicates identical page requests and ignores an aborted late response', async () => {
    let finishInitial
    apiMock.adminArtistPage
      .mockImplementationOnce(() => new Promise(resolve => { finishInitial = resolve }))
      .mockImplementationOnce(async params => ({ items: [person('当前结果')], total: 1, page: params.page }))
    await mount()
    expect(apiMock.adminArtistPage).toHaveBeenCalledTimes(1)
    const firstSignal = apiMock.adminArtistPage.mock.calls[0][1].signal

    root.querySelector('.filter-actions .primary').click()
    await nextTick()
    expect(apiMock.adminArtistPage).toHaveBeenCalledTimes(1)

    const gender = root.querySelectorAll('.filter-panel select')[0]
    gender.value = '男歌手'
    gender.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(apiMock.adminArtistPage).toHaveBeenCalledTimes(2)
    expect(firstSignal.aborted).toBe(true)
    expect(apiMock.adminArtistPage).toHaveBeenLastCalledWith(
      { keyword: '', gender: '男歌手', reviewed: '', page: 0, size: 50 },
      { signal: expect.any(AbortSignal) }
    )

    finishInitial({ items: [person('过时结果')], total: 1, page: 0 })
    await flushUi()
    expect(root.textContent).toContain('当前结果')
    expect(root.textContent).not.toContain('过时结果')
  })

  it('does not let a stale request completion clear the newest request refresh state', async () => {
    await mount()
    let finishOlder
    let finishNewest
    apiMock.adminArtistPage
      .mockImplementationOnce(() => new Promise(resolve => { finishOlder = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { finishNewest = resolve }))
    const actions = app._instance.setupState

    actions.filters.keyword = 'older'
    const olderRequest = actions.load()
    actions.filters.keyword = 'newest'
    const newestRequest = actions.load()

    finishOlder({ items: [person('过期歌手')], total: 1, page: 0 })
    await olderRequest
    expect(actions.refreshing).toBe(true)
    expect(root.textContent).not.toContain('过期歌手')

    finishNewest({ items: [person('最新歌手')], total: 1, page: 0 })
    await newestRequest
    await nextTick()
    expect(actions.refreshing).toBe(false)
    expect(root.textContent).toContain('最新歌手')
  })

  it('does not publish a stale request error while the newest page request is pending', async () => {
    await mount()
    let rejectOlder
    let finishNewest
    apiMock.adminArtistPage
      .mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectOlder = reject }))
      .mockImplementationOnce(() => new Promise(resolve => { finishNewest = resolve }))
    const actions = app._instance.setupState

    actions.filters.keyword = 'older-error'
    const olderRequest = actions.load()
    actions.filters.keyword = 'newest-after-error'
    const newestRequest = actions.load()
    rejectOlder(new Error('stale request failed'))
    await olderRequest

    expect(actions.refreshing).toBe(true)
    expect(actions.loadError).toBe('')
    expect(root.textContent).not.toContain('stale request failed')

    finishNewest({ items: [person('最新无错歌手')], total: 1, page: 0 })
    await newestRequest
    await nextTick()
    expect(actions.refreshing).toBe(false)
    expect(root.textContent).toContain('最新无错歌手')
  })

  it('loads the next and previous artist pages with server page results', async () => {
    apiMock.adminArtistPage.mockImplementation(async params => ({
      items: [person(`第${params.page + 1}页歌手`)], total: 51, page: params.page
    }))
    await mount()
    expect(root.textContent).toContain('第1页歌手')
    expect(root.querySelector('.pagination span').textContent).toContain('第 1 / 2 页')

    root.querySelector('.pagination button:last-child').click()
    await flushUi()
    expect(apiMock.adminArtistPage).toHaveBeenLastCalledWith(
      { keyword: '', gender: '', reviewed: '', page: 1, size: 50 },
      { signal: expect.any(AbortSignal) }
    )
    expect(root.textContent).toContain('第2页歌手')
    expect(root.querySelector('.pagination button:last-child').disabled).toBe(true)

    root.querySelector('.pagination button:first-child').click()
    await flushUi()
    expect(root.querySelector('.pagination span').textContent).toContain('第 1 / 2 页')
    expect(root.textContent).toContain('第1页歌手')
  })

  it('blocks AI analysis when configuration is missing and redirects to AI settings', async () => {
    apiMock.adminAiConfig.mockResolvedValue({ enabled: false })
    await mount()
    root.querySelector('tbody .link').click()
    await nextTick()
    root.querySelector('.review-modal .secondary.action-button').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('批量 AI 分析需要先配置并启用 AI 模型。')
    expect(routerMock.push).toHaveBeenCalledWith({ name: 'admin-settings', query: { section: 'ai' } })
    expect(apiMock.adminAnalyzeArtist).not.toHaveBeenCalled()
  })

  it('blocks batch analysis when AI configuration is unavailable', async () => {
    apiMock.adminAiConfig.mockResolvedValue({ enabled: false })
    await mount()

    root.querySelector('.head-actions .primary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('批量 AI 分析需要先配置并启用 AI 模型。')
    expect(routerMock.push).toHaveBeenCalledWith({ name: 'admin-settings', query: { section: 'ai' } })
    expect(apiMock.adminAnalyzeArtists).not.toHaveBeenCalled()
    expect(root.querySelector('[aria-label="批量 AI 分析"]')).toBeNull()
  })

  it('uses an AI suggestion, permits manual gender selection, and saves the reviewed result', async () => {
    await mount()
    root.querySelector('tbody .link').click()
    await nextTick()
    root.querySelector('.review-modal .secondary.action-button').click()
    await flushUi()
    expect(root.textContent).toContain('88%')
    expect(root.textContent).toContain('代表歌曲判断')

    const gender = root.querySelector('.review-modal .gender-editor select')
    gender.value = '女歌手'
    gender.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    root.querySelector('.review-modal .primary.action-button').click()
    await flushUi()

    expect(apiMock.adminApplyArtistGender).toHaveBeenCalledWith('张三', '女歌手')
    expect(root.querySelector('.review-modal')).toBeNull()
  })

  it('analyzes selected artists in a batch and writes only actionable suggestions', async () => {
    await mount()
    root.querySelector('[aria-label="选择 张三"]').click()
    root.querySelector('[aria-label="选择 李四"]').click()
    await nextTick()
    root.querySelector('.head-actions .primary').click()
    await flushUi()

    expect(apiMock.adminAnalyzeArtists).toHaveBeenCalledWith(['张三', '李四'])
    expect(root.textContent).toContain('建议可用 1 位')
    expect(root.textContent).toContain('待写入 1 位')
    expect(root.querySelector('[aria-label="写入 李四"]').disabled).toBe(true)
    root.querySelector('.batch-modal .primary').click()
    await flushUi()

    expect(apiMock.adminApplyArtistGender).toHaveBeenCalledTimes(1)
    expect(apiMock.adminApplyArtistGender).toHaveBeenCalledWith('张三', '男歌手')
    expect(root.querySelector('.batch-modal')).toBeNull()
  })

  it('keeps batch analysis visibly busy until its request settles and ignores an empty apply action', async () => {
    let finishAnalysis
    apiMock.adminAnalyzeArtists.mockImplementationOnce(() => new Promise(resolve => { finishAnalysis = resolve }))
    await mount()

    root.querySelector('.head-actions .primary').click()
    await flushUi()
    expect(root.querySelector('.batch-loading')).not.toBeNull()
    expect(root.querySelector('.batch-modal .icon-button').disabled).toBe(true)
    app._instance.setupState.closeBatch()
    expect(root.querySelector('.batch-modal')).not.toBeNull()

    finishAnalysis([])
    await flushUi()
    expect(root.querySelector('.batch-modal').textContent).toContain('服务未返回该歌手的分析结果')
    expect(root.querySelector('.batch-modal .primary').disabled).toBe(true)
    await app._instance.setupState.applyBatch()
    expect(apiMock.adminApplyArtistGender).not.toHaveBeenCalled()
    expect(root.querySelector('.batch-modal')).not.toBeNull()
  })

  it('reports local avatar scan, queued backfill, and offline dictionary outcomes', async () => {
    await mount()
    root.querySelector('button[title="扫描本地头像目录并自动匹配"]').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('已从本地头像库成功匹配并导入 2 位歌手头像！')

    root.querySelector('button[title="低频补全本地缺失的歌手头像"]').click()
    await flushUi()
    expect(root.textContent).toContain('已排队 3 位缺失头像')

    root.querySelector('button[title="应用离线数据库歌手分类"]').click()
    await flushUi()
    expect(root.textContent).toContain('数据库分类已应用：1 位歌手更新。')
  })

  it('coalesces repeated avatar and dictionary actions while their requests are pending', async () => {
    let resolveAvatarScan
    apiMock.adminScanLocalAvatars.mockReturnValueOnce(new Promise(resolve => { resolveAvatarScan = resolve }))
    await mount()
    const scanButton = root.querySelector('button[title="扫描本地头像目录并自动匹配"]')
    scanButton.click()
    scanButton.click()
    expect(apiMock.adminScanLocalAvatars).toHaveBeenCalledOnce()
    resolveAvatarScan({ matched: 1 })
    await flushUi()

    let resolveBackfill
    apiMock.adminRetryMissingAvatars.mockReturnValueOnce(new Promise(resolve => { resolveBackfill = resolve }))
    const backfillButton = root.querySelector('button[title="低频补全本地缺失的歌手头像"]')
    backfillButton.click()
    backfillButton.click()
    expect(apiMock.adminRetryMissingAvatars).toHaveBeenCalledOnce()
    resolveBackfill({ queued: 1 })
    await flushUi()

    let resolveDictionary
    apiMock.adminApplyArtistGenderDictionary.mockReturnValueOnce(new Promise(resolve => { resolveDictionary = resolve }))
    const dictionaryButton = root.querySelector('button[title="应用离线数据库歌手分类"]')
    dictionaryButton.click()
    dictionaryButton.click()
    expect(apiMock.adminApplyArtistGenderDictionary).toHaveBeenCalledOnce()
    resolveDictionary({ changed: 1 })
    await flushUi()
  })

  it('uses fallback messages for empty avatar and dictionary responses and rejected operations', async () => {
    apiMock.adminScanLocalAvatars.mockResolvedValueOnce({ matched: 0 })
    apiMock.adminRetryMissingAvatars.mockResolvedValueOnce({ queued: 0 })
    apiMock.adminApplyArtistGenderDictionary.mockResolvedValueOnce({ changed: 0 })
    await mount()

    root.querySelector('button[title="扫描本地头像目录并自动匹配"]').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('已从本地头像库成功匹配并导入 0 位歌手头像！')

    root.querySelector('button[title="低频补全本地缺失的歌手头像"]').click()
    await flushUi()
    expect(root.textContent).toContain('当前没有可补全的缺失头像任务。')

    root.querySelector('button[title="应用离线数据库歌手分类"]').click()
    await flushUi()
    expect(root.textContent).toContain('数据库分类已应用：0 位歌手更新。')

    apiMock.adminScanLocalAvatars.mockRejectedValueOnce({})
    root.querySelector('button[title="扫描本地头像目录并自动匹配"]').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('本地头像扫描失败')

    apiMock.adminRetryMissingAvatars.mockRejectedValueOnce({})
    root.querySelector('button[title="低频补全本地缺失的歌手头像"]').click()
    await flushUi()
    expect(root.textContent).toContain('头像补全任务提交失败')

    apiMock.adminApplyArtistGenderDictionary.mockRejectedValueOnce({})
    root.querySelector('button[title="应用离线数据库歌手分类"]').click()
    await flushUi()
    expect(root.textContent).toContain('数据库分类执行失败')
  })

  it('recovers from artist list errors and renders an empty response safely', async () => {
    apiMock.adminArtistPage.mockRejectedValueOnce(new Error('artist list offline'))
    await mount()
    expect(root.querySelector('[role="alert"]').textContent).toContain('artist list offline')

    apiMock.adminArtistPage.mockResolvedValueOnce({ items: null, total: 'not-a-number', page: -1 })
    root.querySelector('[role="alert"] .secondary').click()
    await flushUi()

    expect(root.querySelector('[role="alert"]')).toBeNull()
    expect(root.querySelector('tbody .empty').textContent).toContain('暂无歌手数据')
    expect(root.querySelector('.stats-row article:nth-child(1) strong').textContent).toBe('0')
    expect(root.querySelector('.pagination span').textContent).toContain('0 / 1 页')
  })

  it('shows a fallback list error when the API rejects without a message', async () => {
    apiMock.adminArtistPage.mockRejectedValueOnce({})
    await mount()
    expect(root.querySelector('[role="alert"]').textContent).toContain('歌手列表加载失败')
    expect(root.querySelector('tbody .empty')).not.toBeNull()
  })

  it('surfaces avatar scan, backfill, and dictionary errors while retaining a zero-queue reason', async () => {
    apiMock.adminScanLocalAvatars.mockRejectedValueOnce(new Error('avatar scan failed'))
    apiMock.adminRetryMissingAvatars.mockResolvedValueOnce({ queued: 0, reason: 'no missing local avatars' })
    apiMock.adminApplyArtistGenderDictionary.mockRejectedValueOnce(new Error('dictionary unavailable'))
    await mount()

    root.querySelector('button[title="扫描本地头像目录并自动匹配"]').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('avatar scan failed')

    root.querySelector('button[title="低频补全本地缺失的歌手头像"]').click()
    await flushUi()
    expect(root.textContent).toContain('no missing local avatars')

    root.querySelector('button[title="应用离线数据库歌手分类"]').click()
    await flushUi()
    expect(root.textContent).toContain('dictionary unavailable')
  })

  it('reports individual AI analysis and gender-save errors without closing the review modal', async () => {
    apiMock.adminAnalyzeArtist.mockRejectedValueOnce(new Error('analysis unavailable'))
    apiMock.adminApplyArtistGender.mockRejectedValueOnce(new Error('gender write failed'))
    await mount()
    root.querySelector('tbody .link').click()
    await nextTick()

    root.querySelector('.review-modal .secondary.action-button').click()
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('analysis unavailable')
    expect(root.querySelector('.review-modal')).not.toBeNull()

    const gender = root.querySelector('.review-modal .gender-editor select')
    gender.value = '组合'
    gender.dispatchEvent(new Event('change', { bubbles: true }))
    root.querySelector('.review-modal .primary.action-button').click()
    await flushUi()

    expect(apiMock.adminApplyArtistGender).toHaveBeenCalledWith('张三', '组合')
    expect(alertMock).toHaveBeenCalledWith('gender write failed')
    expect(root.querySelector('.review-modal')).not.toBeNull()
  })

  it('uses the fallback message for a gender-save failure without a message', async () => {
    apiMock.adminApplyArtistGender.mockRejectedValueOnce({})
    await mount()
    root.querySelector('tbody .link').click()
    await nextTick()
    root.querySelector('.review-modal .primary.action-button').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('歌手类型保存失败')
    expect(root.querySelector('.review-modal')).not.toBeNull()
  })

  it('uses the fallback message for an AI analysis failure without a message', async () => {
    apiMock.adminAnalyzeArtist.mockRejectedValueOnce({})
    await mount()
    root.querySelector('tbody .link').click()
    await nextTick()
    root.querySelector('.review-modal .secondary.action-button').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('歌手分析失败')
    expect(root.querySelector('.review-modal')).not.toBeNull()
  })

  it('closes the batch dialog only after an active write has settled', async () => {
    let finishWrite
    await mount()
    const actions = app._instance.setupState
    actions.batchOpen = true
    actions.closeBatch()
    expect(root.querySelector('.batch-modal')).toBeNull()
    actions.batchOpen = true
    actions.batchRows = [{ artist: '张三', gender: '女歌手', apply: true }]
    apiMock.adminApplyArtistGender.mockImplementationOnce(() => new Promise(resolve => { finishWrite = resolve }))

    const pendingWrite = actions.applyBatch()
    await nextTick()
    expect(actions.batchSaving).toBe(true)
    actions.closeBatch()
    expect(root.querySelector('.batch-modal')).not.toBeNull()

    finishWrite({})
    await pendingWrite
    await flushUi()
    expect(actions.batchSaving).toBe(false)
    expect(root.querySelector('.batch-modal')).toBeNull()
  })

  it('uploads an avatar through the selected artist identity and clears the file input on success and failure', async () => {
    const file = new File(['synthetic-avatar'], 'avatar.png', { type: 'image/png' })
    apiMock.adminUploadArtistAvatar.mockResolvedValueOnce({})
    await mount()
    root.querySelector('tbody .link').click()
    await nextTick()

    const input = root.querySelector('.upload-btn input')
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()

    expect(apiMock.adminUploadArtistAvatar).toHaveBeenCalledWith('张三', file)
    expect(root.querySelector('.review-avatar img').getAttribute('src')).toContain('/api/artists/avatar?key=%E5%BC%A0%E4%B8%89&t=')
    expect(input.value).toBe('')

    const secondFile = new File(['bad-avatar'], 'bad.png', { type: 'image/png' })
    apiMock.adminUploadArtistAvatar.mockRejectedValueOnce(new Error('avatar upload rejected'))
    Object.defineProperty(input, 'files', { configurable: true, value: [secondFile] })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('avatar upload rejected')
    expect(input.value).toBe('')

    apiMock.adminUploadArtistAvatar.mockRejectedValueOnce({})
    const thirdFile = new File(['no-message'], 'unknown.png', { type: 'image/png' })
    Object.defineProperty(input, 'files', { configurable: true, value: [thirdFile] })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(alertMock).toHaveBeenLastCalledWith('头像上传失败')
    expect(input.value).toBe('')
  })

  it('uses the artist name when no artist key or avatar URL is returned', async () => {
    artists = [{ ...person('名称身份歌手'), artistKey: undefined, avatarUrl: null, songs: [] }]
    apiMock.adminArtistPage.mockImplementation(async params => ({ items: artists, total: artists.length, page: params.page }))
    apiMock.adminUploadArtistAvatar.mockResolvedValueOnce({})
    await mount()
    root.querySelector('tbody .link').click()
    await nextTick()
    const input = root.querySelector('.upload-btn input')
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(apiMock.adminUploadArtistAvatar).not.toHaveBeenCalled()

    const file = new File(['avatar'], 'avatar.webp', { type: 'image/webp' })
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(apiMock.adminUploadArtistAvatar).toHaveBeenCalledWith('名称身份歌手', file)
    expect(root.querySelector('.review-avatar img').getAttribute('src')).toContain('key=%E5%90%8D%E7%A7%B0%E8%BA%AB%E4%BB%BD%E6%AD%8C%E6%89%8B&t=')
  })

  it('uses selected-song details when an AI suggestion omits optional fields', async () => {
    apiMock.adminAnalyzeArtist.mockResolvedValueOnce({ confidence: null })
    await mount()
    root.querySelector('tbody .link').click()
    await nextTick()
    root.querySelector('.review-modal .secondary.action-button').click()
    await flushUi()

    expect(root.querySelector('.review-summary').textContent).toContain('未知')
    expect(root.querySelector('.review-summary').textContent).toContain('0%')
    expect(root.querySelector('.reason').textContent).toContain('没有 API Key 时仍可手动选择')
    expect(root.querySelector('.sample-list').textContent).toContain('歌曲 A')
  })

  it('aborts pending page and avatar-backfill requests when the view is unmounted', async () => {
    let finishPage
    apiMock.adminArtistPage.mockImplementationOnce((_params, options) => {
      return new Promise(resolve => { finishPage = resolve; apiMock.pendingPageSignal = options.signal })
    })
    await mount()
    const pageSignal = apiMock.pendingPageSignal
    app.unmount()
    app = null
    expect(pageSignal.aborted).toBe(true)
    finishPage({ items: [], total: 0, page: 0 })
    await flushUi()

    await mount()
    let finishBackfill
    apiMock.adminRetryMissingAvatars.mockImplementationOnce(options => {
      return new Promise(resolve => { finishBackfill = resolve; apiMock.pendingBackfillSignal = options.signal })
    })
    root.querySelector('button[title="低频补全本地缺失的歌手头像"]').click()
    await nextTick()
    const backfillSignal = apiMock.pendingBackfillSignal
    app.unmount()
    app = null
    expect(backfillSignal.aborted).toBe(true)
    finishBackfill({ queued: 0 })
    await flushUi()
  })

  it('uses eligible unselected artists, chunks requests, and preserves missing or failed AI results for review', async () => {
    artists = [person('候选歌手'), person('失败歌手'), person('无返回歌手')]
    apiMock.adminArtistPage.mockImplementation(async params => ({ items: artists, total: artists.length, page: params.page }))
    apiMock.adminAiConfig.mockResolvedValue({ enabled: true, baseUrl: 'http://ai.local', bulkModel: 'test-model', bulkConcurrency: 1 })
    apiMock.adminAnalyzeArtists
      .mockResolvedValueOnce([{ artist: '候选歌手', gender: '女歌手', confidence: 0.76, source: 'AI', reason: '合成建议' }])
      .mockRejectedValueOnce(new Error('batch provider unavailable'))
      .mockResolvedValueOnce([])
    await mount()

    root.querySelector('.head-actions .primary').click()
    await flushUi()

    expect(apiMock.adminAnalyzeArtists.mock.calls).toEqual([[['候选歌手']], [['失败歌手']], [['无返回歌手']]])
    expect(root.textContent).toContain('已分析 3 位')
    expect(root.textContent).toContain('batch provider unavailable')
    expect(root.textContent).toContain('服务未返回该歌手的分析结果')
    expect(root.querySelector('[aria-label="写入 候选歌手"]').checked).toBe(true)
    expect(root.querySelector('[aria-label="写入 失败歌手"]').disabled).toBe(true)
  })

  it('does not open batch analysis when no person needs review', async () => {
    artists = [
      { ...person('已确认歌手'), reviewed: true },
      { ...person('群星'), artistKind: 'VARIOUS' },
      { ...person('未归属'), artistKind: 'UNATTRIBUTED' }
    ]
    apiMock.adminArtistPage.mockImplementation(async params => ({ items: artists, total: artists.length, page: params.page }))
    await mount()

    root.querySelector('.head-actions .primary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('当前筛选结果中没有待复核歌手，请勾选需要重新分析的歌手。')
    expect(apiMock.adminAiConfig).not.toHaveBeenCalled()
    expect(apiMock.adminAnalyzeArtists).not.toHaveBeenCalled()
  })

  it('retains batch results after a partial gender write failure', async () => {
    await mount()
    root.querySelector('[aria-label="选择 张三"]').click()
    root.querySelector('[aria-label="选择 李四"]').click()
    await nextTick()
    apiMock.adminAnalyzeArtists.mockResolvedValue([
      { artist: '张三', gender: '男歌手', confidence: 0.92, source: 'AI', reason: '建议 1' },
      { artist: '李四', gender: '组合', confidence: 0.65, source: 'AI', reason: '建议 2' }
    ])
    root.querySelector('.head-actions .primary').click()
    await flushUi()
    apiMock.adminApplyArtistGender.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('second write failed'))

    root.querySelector('.batch-modal .primary').click()
    await flushUi()

    expect(apiMock.adminApplyArtistGender).toHaveBeenCalledTimes(2)
    expect(alertMock).toHaveBeenCalledWith('second write failed')
    expect(root.querySelector('.batch-modal')).not.toBeNull()
    expect(root.querySelector('.batch-modal .primary').disabled).toBe(false)
  })

  it('uses safe review rows for null results and batch errors without messages', async () => {
    artists = [person('空响应歌手'), person('无消息错误歌手')]
    apiMock.adminArtistPage.mockImplementation(async params => ({ items: artists, total: artists.length, page: params.page }))
    apiMock.adminAiConfig.mockResolvedValue({ enabled: true, baseUrl: 'http://ai.local', bulkModel: 'test-model', bulkConcurrency: 0 })
    apiMock.adminAnalyzeArtists
      .mockResolvedValueOnce(null)
      .mockRejectedValueOnce({})
    await mount()

    root.querySelector('.head-actions .primary').click()
    await flushUi()

    expect(apiMock.adminAnalyzeArtists.mock.calls).toEqual([
      [['空响应歌手']], [['无消息错误歌手']]
    ])
    expect(root.textContent).toContain('服务未返回该歌手的分析结果')
    expect(root.textContent).toContain('批量分析失败')
    expect(root.querySelector('.batch-modal').textContent).toContain('已分析 2 位')
  })

  it('caps concurrent batch analysis at ten artists', async () => {
    artists = Array.from({ length: 11 }, (_, index) => person(`并发歌手${index + 1}`))
    apiMock.adminArtistPage.mockImplementation(async params => ({ items: artists, total: artists.length, page: params.page }))
    apiMock.adminAiConfig.mockResolvedValue({ enabled: true, baseUrl: 'http://ai.local', bulkModel: 'test-model', bulkConcurrency: 50 })
    apiMock.adminAnalyzeArtists.mockResolvedValue([])
    await mount()

    root.querySelector('.head-actions .primary').click()
    await flushUi()

    expect(apiMock.adminAnalyzeArtists.mock.calls.map(([chunk]) => chunk.length)).toEqual([10, 1])
    expect(root.querySelector('.batch-modal').textContent).toContain('已分析 11 位')
  })

  it('keeps batch suggestions visible when a write error has no message', async () => {
    await mount()
    root.querySelector('[aria-label="选择 张三"]').click()
    await nextTick()
    apiMock.adminAnalyzeArtists.mockResolvedValue([
      { artist: '张三', gender: '女歌手', confidence: 0.81, source: 'AI', reason: '合成建议' }
    ])
    root.querySelector('.head-actions .primary').click()
    await flushUi()
    apiMock.adminApplyArtistGender.mockRejectedValueOnce({})

    expect(apiMock.adminAnalyzeArtists).toHaveBeenCalledWith(['张三'])

    root.querySelector('.batch-modal .primary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('批量写入失败')
    expect(root.querySelector('.batch-modal').textContent).toContain('张三')
    expect(root.querySelector('.batch-modal .primary').disabled).toBe(false)
  })

  async function mount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(ArtistLibraryView)
    app.mount(root)
    await flushUi()
  }
})

function person(name) {
  return { artistKey: name.toLowerCase(), name, gender: '未知', reviewed: false, artistKind: 'PERSON', songCount: 1, songs: [{ id: 1, title: '歌曲 A', artist: name, language: '国语' }] }
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
