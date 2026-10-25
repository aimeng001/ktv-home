// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock, alertDialogMock, confirmDialogMock } = vi.hoisted(() => ({
  apiMock: {
    adminGetSettings: vi.fn(),
    adminAiConfig: vi.fn(),
    adminMusicSourceConfig: vi.fn(),
    adminTranscodeHardware: vi.fn(),
    adminWishes: vi.fn(),
    adminSourceLibrary: vi.fn(),
    releaseInfo: vi.fn(),
    adminUploadStandbyLogo: vi.fn(),
    adminAiModels: vi.fn(),
    adminAiPutConfig: vi.fn(),
    adminAiTestConfig: vi.fn(),
    adminTestMusicSources: vi.fn(),
    adminPutSettings: vi.fn(),
    adminPutMusicSourceConfig: vi.fn(),
    adminResetTranscodeDefaults: vi.fn(),
    adminDeleteWish: vi.fn(),
    adminExportWishesCsv: vi.fn(),
    adminDownloadDiagnostics: vi.fn()
  },
  alertDialogMock: vi.fn(),
  confirmDialogMock: vi.fn()
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../composables/useDialog', () => ({
  alertDialog: alertDialogMock,
  confirmDialog: confirmDialogMock
}))
vi.mock('./AdminLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))

import SettingsView from './SettingsView.vue'

describe('SettingsView standby logo upload', () => {
  let app
  let root
  let router

  beforeEach(async () => {
    await resetHarness()
  })

  async function resetHarness({ section = 'tv', settings, aiConfig, musicConfig, hardware, sourceLibrary, wishes } = {}) {
    app?.unmount()
    root?.remove()
    app = undefined
    root = undefined
    router = undefined
    vi.clearAllMocks()
    apiMock.adminGetSettings.mockResolvedValue({
      library_watch_enabled: false,
      display_address: '',
      external_default_audio_layout: 'NORMAL_STEREO',
      delete_source_after_transcode: false,
      direct_copy_containers: ['mp4', 'm4v', 'mkv'],
      direct_copy_video_codecs: ['h264', 'hevc'],
      direct_copy_audio_codecs: ['aac', 'mp3'],
      transcode_audio_only: false,
      transcode_output_container: 'mkv',
      transcode_video_codec: 'h264',
      transcode_audio_codec: 'aac',
      transcode_hardware_acceleration: false,
      standby_welcome: '服务器已保存欢迎语',
      standby_subtitle: '服务器已保存副标题',
      standby_logo_configured: false,
      standby_carousel: true,
      standby_source: 'mixed',
      standby_song_ids: [],
      standby_interval_sec: 8,
      anti_burn: true,
      mini_qr: true,
      tv_video_scale_mode: 'zoom'
    })
    if (typeof settings === 'function') apiMock.adminGetSettings.mockImplementation(settings)
    else if (settings !== undefined) apiMock.adminGetSettings.mockResolvedValue(settings)
    const resolvedAiConfig = aiConfig === undefined ? { enabled: false, baseUrl: '', bulkModel: '' } : aiConfig
    if (typeof resolvedAiConfig === 'function') apiMock.adminAiConfig.mockImplementation(resolvedAiConfig)
    else apiMock.adminAiConfig.mockResolvedValue(resolvedAiConfig)
    apiMock.adminMusicSourceConfig.mockResolvedValue(musicConfig === undefined ? { enabled: false, providers: [] } : musicConfig)
    apiMock.adminTranscodeHardware.mockResolvedValue({ available: false, reason: 'test' })
    if (hardware !== undefined) apiMock.adminTranscodeHardware.mockResolvedValue(hardware)
    apiMock.adminWishes.mockResolvedValue(wishes || [])
    apiMock.adminSourceLibrary.mockResolvedValue(sourceLibrary || { libraryMode: 'EXTERNAL_READ_ONLY' })
    apiMock.releaseInfo.mockResolvedValue({ version: 'test' })
    apiMock.adminUploadStandbyLogo.mockResolvedValue({ logoUrl: '/api/standby/logo' })
    apiMock.adminAiModels.mockResolvedValue({ models: [] })
    apiMock.adminAiPutConfig.mockResolvedValue({ enabled: false })
    apiMock.adminAiTestConfig.mockResolvedValue({ testedModels: {} })
    apiMock.adminTestMusicSources.mockResolvedValue({ results: [], providerStatus: [] })
    apiMock.adminPutSettings.mockImplementation(async value => value)
    apiMock.adminPutMusicSourceConfig.mockImplementation(async value => value)
    apiMock.adminResetTranscodeDefaults.mockResolvedValue({})
    apiMock.adminDeleteWish.mockResolvedValue({})
    apiMock.adminExportWishesCsv.mockResolvedValue(new Blob(['csv']))
    apiMock.adminDownloadDiagnostics.mockResolvedValue(new Blob(['diagnostics']))

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/settings', name: 'settings', component: SettingsView },
        { path: '/admin/ktv-library', name: 'admin-ktv-library', component: { template: '<div />' } },
        { path: '/:pathMatch(.*)*', name: 'fallback', component: { template: '<div />' } }
      ]
    })
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(router)
    await router.push(`/settings?section=${section}`)
    await router.isReady()
    app.mount(root)
    await flushUi()
  }

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('keeps an unsaved settings draft and dirty indicator after the logo upload', async () => {
    const section = root.querySelector('#section-tv')
    const welcome = section.querySelector('input.input.wide')

    welcome.value = '本次临时欢迎语'
    welcome.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    expect(root.textContent).toContain('有未保存的修改')

    await uploadLogo(section)
    await flushUi()

    expect(apiMock.adminUploadStandbyLogo).toHaveBeenCalledTimes(1)
    expect(welcome.value).toBe('本次临时欢迎语')
    expect(root.textContent).toContain('有未保存的修改')
    expect(apiMock.adminGetSettings).toHaveBeenCalledTimes(1)
    clickButton(root, '放弃修改')
    await flushUi()
    expect(welcome.value).toBe('服务器已保存欢迎语')
    expect(root.textContent).not.toContain('有未保存的修改')
  })

  it('warns before leaving only when a settings draft is dirty', async () => {
    const cleanNavigation = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(cleanNavigation)
    expect(cleanNavigation.defaultPrevented).toBe(false)

    const welcome = root.querySelector('#section-tv input.input.wide')
    welcome.value = '尚未保存的欢迎语'
    welcome.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()

    const dirtyNavigation = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirtyNavigation)

    expect(dirtyNavigation.defaultPrevented).toBe(true)
    expect(root.textContent).toContain('有未保存的修改')
  })

  it('keeps a clean form clean and reflects the uploaded logo', async () => {
    const section = root.querySelector('#section-tv')
    expect(root.textContent).not.toContain('有未保存的修改')

    await uploadLogo(section)
    await flushUi()

    expect(section.textContent).toContain('已配置自定义 Logo')
    expect(root.textContent).not.toContain('有未保存的修改')
    expect(apiMock.adminGetSettings).toHaveBeenCalledTimes(1)
  })

  it('keeps the draft and dirty state when the upload fails', async () => {
    const section = root.querySelector('#section-tv')
    const welcome = section.querySelector('input.input.wide')
    apiMock.adminUploadStandbyLogo.mockRejectedValueOnce(new Error('上传服务暂不可用'))

    welcome.value = '失败后仍保留的草稿'
    welcome.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    await uploadLogo(section)
    await flushUi()

    expect(welcome.value).toBe('失败后仍保留的草稿')
    expect(root.textContent).toContain('有未保存的修改')
    expect(alertDialogMock).toHaveBeenCalledWith('上传服务暂不可用')
    expect(apiMock.adminGetSettings).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['DATABASE', '管理后台'],
    ['ENVIRONMENT', '环境变量'],
    ['NONE', '未配置'],
    ['OTHER', '默认值']
  ])('shows the AI configuration source label for %s', async (source, label) => {
    await resetHarness({
      section: 'ai',
      aiConfig: {
        enabled: true,
        baseUrl: 'https://ai.example.test/v1',
        bulkModel: 'test-model',
        sources: { bulk_model: source },
        apiKeyConfigured: true,
        apiKeySuffix: '1234',
        lastTestAt: '2026-10-18T12:00:00Z',
        jsonMode: 'FORCE'
      }
    })

    const aiSection = root.querySelector('#section-ai')
    expect(aiSection.querySelector('.source-pill').textContent).toContain('服务可用')
    expect(aiSection.querySelector('.ai-status strong').textContent).toBe(label)
    expect(aiSection.textContent).toContain('****1234')
    expect(aiSection.textContent).toContain('FORCE')
  })

  it('falls back to the basic section for an unknown route and searches and jumps to a setting', async () => {
    await resetHarness({ section: 'unknown' })

    expect([...root.querySelectorAll('.category-button')].find(button => button.textContent.includes('基础配置')).className).toContain('active')
    const search = root.querySelector('input[aria-label="搜索设置"]')
    search.value = 'nothing-matches'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    expect(root.querySelector('.search-results .empty').textContent).toContain('没有匹配设置')

    const target = root.querySelector('#ai_api_key')
    target.scrollIntoView = vi.fn()
    const replaceRoute = vi.spyOn(router, 'replace')
    search.value = '密钥'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    root.querySelector('.search-results button').click()
    await flushUi()

    expect(replaceRoute).toHaveBeenCalledWith({ query: expect.objectContaining({ section: 'ai' }) })
    expect(target.scrollIntoView).toHaveBeenCalledOnce()
    search.value = ''
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    expect([...root.querySelectorAll('.category-button')].find(button => button.textContent.includes('AI 模型')).className).toContain('active')
  })

  it('blocks saving a failed settings section, retries only that section, and then saves its draft', async () => {
    const unavailable = new Error('AI 服务暂不可用')
    let aiLoads = 0
    await resetHarness({
      section: 'ai',
      aiConfig: async () => {
        aiLoads += 1
        if (aiLoads === 1) throw unavailable
        return { enabled: false, baseUrl: '', bulkModel: '' }
      }
    })

    expect(root.querySelector('.settings-load-alert')).not.toBeNull()
    expect(root.querySelector('.settings-error').textContent).toContain('当前分区读取失败')
    root.querySelector('#ai_enabled .switch').click()
    await flushUi()
    clickButton(root, '保存设置')
    await flushUi()
    expect(apiMock.adminAiPutConfig).not.toHaveBeenCalled()
    expect(alertDialogMock).toHaveBeenCalledWith(
      expect.stringContaining('AI 设置（该设置分区尚未成功加载'),
      expect.objectContaining({ title: '设置保存不完整', tone: 'warning' })
    )

    clickButton(root.querySelector('.settings-load-alert'), '重试')
    await flushUi()
    expect(apiMock.adminAiConfig).toHaveBeenCalledTimes(2)
    expect(apiMock.adminGetSettings).toHaveBeenCalledTimes(1)
    expect(root.querySelector('.settings-error')).toBeNull()

    clickButton(root, '保存设置')
    await flushUi()
    expect(apiMock.adminAiPutConfig).toHaveBeenCalledWith(expect.objectContaining({ enabled: true }))
  })

  it('does not start another failed-section retry while the selected section is loading', async () => {
    const retryResult = { enabled: false, baseUrl: '', bulkModel: '' }
    let finishRetry
    const pendingRetry = new Promise(resolve => { finishRetry = resolve })
    let aiLoads = 0
    await resetHarness({
      section: 'ai',
      aiConfig: () => {
        aiLoads += 1
        if (aiLoads === 1) return Promise.reject(new Error('AI 配置暂不可用'))
        return pendingRetry
      }
    })

    const retryButton = clickButton(root.querySelector('.settings-load-alert'), '重试')
    await flushUi()
    expect(apiMock.adminAiConfig).toHaveBeenCalledTimes(2)
    retryButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushUi()
    expect(apiMock.adminAiConfig).toHaveBeenCalledTimes(2)

    finishRetry(retryResult)
    await flushUi()
    expect(root.querySelector('.settings-error')).toBeNull()
  })

  it('loads AI model choices, tests the connection, and saves a cleared key with the draft', async () => {
    await resetHarness({
      section: 'ai',
      aiConfig: {
        enabled: true,
        baseUrl: 'https://old.example.test/v1',
        apiKeyConfigured: true,
        apiKeySuffix: '7890',
        bulkModel: 'old-bulk',
        reasoningModel: 'old-reasoning',
        timeoutSeconds: 30,
        identityThreshold: 0.9,
        classificationThreshold: 0.8,
        jsonMode: 'AUTO',
        bulkConcurrency: 2,
        reasoningConcurrency: 1,
        sources: { bulk_model: 'DATABASE' }
      }
    })
    apiMock.adminAiModels.mockResolvedValue({ models: ['bulk-choice', 'reasoning-choice'] })
    apiMock.adminAiTestConfig.mockResolvedValueOnce({ testedModels: { bulk: {}, reasoning: {} } }).mockResolvedValueOnce({})
    apiMock.adminAiPutConfig.mockResolvedValue({ enabled: false })
    const aiSection = root.querySelector('#section-ai')

    clickButton(aiSection, '获取模型', 0)
    await flushUi()
    clickButton(aiSection, 'bulk-choice')
    clickButton(aiSection, '获取模型', 1)
    await flushUi()
    clickButton(aiSection, 'reasoning-choice')
    clickButton(aiSection, 'DeepSeek')
    await flushUi()
    const baseUrl = aiSection.querySelector('#ai_base_url input')
    expect(baseUrl.value).toBe('https://api.deepseek.com/v1')
    clickButton(aiSection, 'Ollama / 本地模型')
    await flushUi()
    expect(baseUrl.value).toBe('https://api.deepseek.com/v1')

    clickButton(aiSection, '测试连接与模型')
    await flushUi()
    expect(aiSection.textContent).toContain('连接成功，已检测 2 个模型')
    clickButton(aiSection, '测试连接与模型')
    await flushUi()
    expect(aiSection.textContent).toContain('连接成功，已检测 0 个模型')
    expect(apiMock.adminAiConfig).toHaveBeenCalledTimes(3)

    clickButton(aiSection, '清除 Key')
    aiSection.querySelector('#ai_enabled .switch').click()
    await flushUi()
    clickButton(root, '保存设置')
    await flushUi()

    expect(apiMock.adminAiPutConfig).toHaveBeenCalledWith(expect.objectContaining({
      enabled: false,
      apiKey: null,
      clearApiKey: true,
      bulkModel: 'bulk-choice',
      reasoningModel: 'reasoning-choice'
    }))
  })

  it('renders music provider health states, tests providers, and saves provider selection', async () => {
    const future = new Date(Date.now() + 60_000).toISOString()
    await resetHarness({
      section: 'metadata',
      musicConfig: {
        enabled: true,
        providers: ['QQ'],
        resultLimit: 10,
        timeoutSeconds: 8,
        searchCacheHours: 4,
        concurrencyLimit: 2,
        requestIntervalMs: 5000,
        autoApplyThreshold: 0.9,
        providerStatus: [
          { provider: 'QQ', displayName: 'QQ 音乐', healthy: true, cooldownUntil: future, requestCount: 3, dailyLimit: 10 },
          { provider: 'NETEASE', displayName: '网易云音乐', healthy: false, lastError: '限流中', requestCount: 0, dailyLimit: 0 },
          { provider: 'KUGOU', displayName: '酷狗音乐', healthy: true, lastSuccessAt: future, requestCount: null },
          { provider: 'LOCAL', displayName: '本地', healthy: false }
        ]
      }
    })
    apiMock.adminTestMusicSources.mockResolvedValue({
      results: [{ healthy: true }, { healthy: false }]
    })
    const musicSection = root.querySelector('#section-metadata')

    expect(musicSection.textContent).toContain('请求冷却至')
    expect(musicSection.textContent).toContain('限流中')
    expect(musicSection.textContent).toContain('最近成功')
    expect(musicSection.textContent).toContain('3 / 10')
    expect(musicSection.textContent).toContain('0 / 300')
    expect(musicSection.textContent).toContain('尚未测试')
    clickButton(musicSection, '测试歌曲元数据连通性')
    await flushUi()
    expect(musicSection.textContent).toContain('1/2 个平台连接成功')
    expect(apiMock.adminTestMusicSources).toHaveBeenCalledWith(['QQ'])

    apiMock.adminTestMusicSources.mockResolvedValueOnce({})
    clickButton(musicSection, '测试歌曲元数据连通性')
    await flushUi()
    expect(musicSection.textContent).toContain('0/0 个平台连接成功')

    clickButton(musicSection, '网易云音乐')
    await flushUi()
    clickButton(root, '保存设置')
    await flushUi()
    expect(apiMock.adminPutMusicSourceConfig).toHaveBeenCalledWith(expect.objectContaining({ providers: ['QQ', 'NETEASE'] }))
  })

  it('shows metadata as disabled when enabled has no selected providers, then reflects a selection', async () => {
    await resetHarness({
      section: 'metadata',
      musicConfig: { enabled: true, providers: [] }
    })
    const musicSection = root.querySelector('#section-metadata')
    expect(musicSection.querySelector('.source-pill').textContent).toContain('默认关闭')
    clickButton(musicSection, '网易云音乐')
    await flushUi()
    expect(musicSection.querySelector('.source-pill').textContent).toContain('已启用')
    clickButton(musicSection, '网易云音乐')
    await flushUi()
    expect(musicSection.querySelector('.source-pill').textContent).toContain('默认关闭')
    clickButton(musicSection, '网易云音乐')
    await flushUi()
    expect(musicSection.querySelector('.source-pill').textContent).toContain('已启用')

    await resetHarness({ section: 'metadata', musicConfig: { enabled: false, providers: null } })
    expect(root.querySelector('#section-metadata .source-pill').textContent).toContain('默认关闭')
    expect(root.querySelector('#section-metadata .section-actions .btn').disabled).toBe(true)

    const search = root.querySelector('input[aria-label="搜索设置"]')
    search.value = '   '
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    expect(root.querySelector('.search-results .empty').textContent).toContain('没有匹配设置')
  })

  it('keeps transcode settings unavailable for read-only sources and handles restore confirmation and failure', async () => {
    await resetHarness({ section: 'transcode' })
    const transcode = root.querySelector('#section-transcode')
    expect(transcode.textContent).toContain('外部只读曲库')
    expect(transcode.querySelector('.checks')).toBeNull()

    await resetHarness({ section: 'transcode', sourceLibrary: { libraryMode: 'MANAGED' } })
    apiMock.adminResetTranscodeDefaults.mockResolvedValue({
      transcode_output_container: 'mp4',
      transcode_video_codec: 'hevc',
      direct_copy_containers: ['mkv']
    })
    const managedTranscode = root.querySelector('#section-transcode')
    expect(managedTranscode.querySelector('.checks')).not.toBeNull()

    confirmDialogMock.mockResolvedValueOnce(false)
    clickButton(managedTranscode, '恢复默认')
    await flushUi()
    expect(apiMock.adminResetTranscodeDefaults).not.toHaveBeenCalled()

    confirmDialogMock.mockResolvedValueOnce(true)
    clickButton(managedTranscode, '恢复默认')
    await flushUi()
    expect(apiMock.adminResetTranscodeDefaults).toHaveBeenCalledOnce()
    expect(managedTranscode.querySelectorAll('select')[0].value).toBe('mp4')

    apiMock.adminResetTranscodeDefaults.mockRejectedValueOnce(new Error('恢复服务错误'))
    confirmDialogMock.mockResolvedValueOnce(true)
    clickButton(managedTranscode, '恢复默认')
    await flushUi()
    expect(alertDialogMock).toHaveBeenCalledWith('恢复服务错误')

    apiMock.adminResetTranscodeDefaults.mockRejectedValueOnce({})
    confirmDialogMock.mockResolvedValueOnce(true)
    clickButton(managedTranscode, '恢复默认')
    await flushUi()
    expect(alertDialogMock).toHaveBeenCalledWith('恢复默认设置失败')
  })

  it('keeps transcode restore unavailable until the initial settings snapshot is loaded', async () => {
    let resolveSettings
    const pendingSettings = new Promise(resolve => { resolveSettings = resolve })
    await resetHarness({
      section: 'transcode',
      settings: () => pendingSettings,
      sourceLibrary: { libraryMode: 'MANAGED' }
    })
    const transcode = root.querySelector('#section-transcode')
    expect([...transcode.querySelectorAll('button')].some(button => button.textContent.includes('恢复默认'))).toBe(false)
    expect(confirmDialogMock).not.toHaveBeenCalled()

    apiMock.adminResetTranscodeDefaults.mockResolvedValueOnce({
      transcode_output_container: 'mp4',
      transcode_video_codec: 'hevc'
    })
    confirmDialogMock.mockResolvedValueOnce(true)
    resolveSettings({ transcode_output_container: 'mkv' })
    await flushUi()

    clickButton(transcode, '恢复默认')
    await flushUi()

    expect(apiMock.adminResetTranscodeDefaults).toHaveBeenCalledOnce()
    expect(transcode.querySelectorAll('select')[0].value).toBe('mp4')
  })

  it('prevents saving truncated standby song IDs and saves normalized IDs after correction', async () => {
    await resetHarness({ section: 'tv' })
    const tv = root.querySelector('#section-tv')
    const source = tv.querySelector('#standby_source select')
    source.value = 'custom'
    source.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    const ids = tv.querySelector('#standby_song_ids input')
    ids.value = Array.from({ length: 101 }, (_, index) => String(index + 1)).join(',')
    ids.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    expect(tv.textContent).toContain('已超过上限')

    clickButton(root, '保存设置')
    await flushUi()
    expect(apiMock.adminPutSettings).not.toHaveBeenCalled()
    expect(alertDialogMock).toHaveBeenCalledWith(
      expect.stringContaining('已超过上限，仅保留前 100 首'),
      expect.objectContaining({ title: '待机歌曲数量超限', tone: 'warning' })
    )

    ids.value = '12, 12，13 invalid 0 -1'
    ids.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
    clickButton(root, '保存设置')
    await flushUi()
    expect(apiMock.adminPutSettings).toHaveBeenCalledWith(expect.objectContaining({ standby_song_ids: [12, 13] }))
  })

  it('opens an empty wishes modal and safely ignores upload events without a file', async () => {
    await resetHarness({ section: 'tv' })
    const fileInput = root.querySelector('#standby_logo input[type="file"]')
    Object.defineProperty(fileInput, 'files', { configurable: true, value: [] })
    fileInput.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(apiMock.adminUploadStandbyLogo).not.toHaveBeenCalled()

    await resetHarness({ section: 'maintenance', wishes: [] })
    clickButton(root, '查看详情 / 导出')
    await flushUi()
    expect(root.querySelector('.wish-list .empty').textContent).toContain('暂无心愿记录')
    expect(root.querySelector('.wish-modal')).not.toBeNull()
  })

  it('shows fallback errors for failed AI model, AI connection, and music provider requests', async () => {
    await resetHarness({
      section: 'ai',
      aiConfig: { enabled: false, baseUrl: '', bulkModel: '' }
    })
    apiMock.adminAiModels.mockResolvedValueOnce({})
    const aiSection = root.querySelector('#section-ai')
    clickButton(aiSection, '获取模型', 0)
    await flushUi()
    expect(aiSection.querySelector('.model-list')).toBeNull()
    apiMock.adminAiModels.mockRejectedValueOnce({})
    clickButton(aiSection, '获取模型', 0)
    await flushUi()
    expect(alertDialogMock).toHaveBeenCalledWith('模型列表获取失败')

    apiMock.adminAiTestConfig.mockRejectedValueOnce({})
    clickButton(aiSection, '测试连接与模型')
    await flushUi()
    expect(aiSection.textContent).toContain('连接测试失败')

    await resetHarness({
      section: 'metadata',
      musicConfig: { enabled: true, providers: ['QQ'] }
    })
    apiMock.adminTestMusicSources.mockRejectedValueOnce({})
    const musicSection = root.querySelector('#section-metadata')
    clickButton(musicSection, '测试歌曲元数据连通性')
    await flushUi()
    expect(musicSection.textContent).toContain('平台连接测试失败')
  })

  it('uses default snapshots for absent server settings and source mode', async () => {
    await resetHarness({
      section: 'transcode',
      settings: null,
      aiConfig: null,
      musicConfig: null,
      sourceLibrary: {}
    })

    expect(apiMock.adminGetSettings).toHaveBeenCalledOnce()
    expect(root.querySelector('#section-transcode').textContent).toContain('曲库模式尚未确认')
    expect(root.querySelector('#section-transcode').querySelector('.checks')).toBeNull()
    expect(root.textContent).not.toContain('有未保存的修改')
  })

  it('renders both hardware acceleration status outcomes', async () => {
    await resetHarness({ section: 'transcode', sourceLibrary: { libraryMode: 'MANAGED' }, hardware: { available: true } })
    expect(root.querySelector('#section-transcode').textContent).toContain('硬件编码可用')

    await resetHarness({ section: 'transcode', sourceLibrary: { libraryMode: 'MANAGED' }, hardware: { available: false, reason: '' } })
    expect(root.querySelector('#section-transcode').textContent).toContain('未检测到硬件编码器')
  })

  it('exports and deletes wishes, downloads diagnostics, and reports failures without losing modal state', async () => {
    await resetHarness({
      section: 'maintenance',
      wishes: [
        { id: 1, keyword: '测试歌曲一', createdAt: '2026-10-18T12:00:00Z', createdBy: null },
        { id: 2, keyword: '测试歌曲二', createdAt: '2026-10-18T12:05:00Z', createdBy: 7 }
      ]
    })
    const originalCreate = URL.createObjectURL
    const originalRevoke = URL.revokeObjectURL
    const originalClick = HTMLAnchorElement.prototype.click
    const createObjectURL = vi.fn(() => 'blob:ktv-test')
    const revokeObjectURL = vi.fn()
    const anchorClick = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: createObjectURL })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: revokeObjectURL })
    HTMLAnchorElement.prototype.click = anchorClick
    try {
      clickButton(root, '查看详情 / 导出')
      await flushUi()
      const modal = root.querySelector('.wish-modal')
      expect(modal.textContent).toContain('游客')
      expect(modal.textContent).toContain('测试歌曲一')

      apiMock.adminExportWishesCsv.mockResolvedValueOnce(new Blob(['csv']))
      clickButton(modal, '导出 CSV')
      await flushUi()
      expect(apiMock.adminExportWishesCsv).toHaveBeenCalledOnce()
      expect(createObjectURL).toHaveBeenCalledOnce()
      expect(anchorClick).toHaveBeenCalledOnce()
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:ktv-test')

      confirmDialogMock.mockResolvedValueOnce(false)
      clickButton(modal, '删除')
      await flushUi()
      expect(apiMock.adminDeleteWish).not.toHaveBeenCalled()

      confirmDialogMock.mockResolvedValueOnce(true)
      clickButton(modal, '删除')
      await flushUi()
      expect(apiMock.adminDeleteWish).toHaveBeenCalledWith(1)
      expect(modal.textContent).not.toContain('测试歌曲一')

      confirmDialogMock.mockResolvedValueOnce(true)
      apiMock.adminDeleteWish.mockRejectedValueOnce({})
      clickButton(modal, '删除')
      await flushUi()
      expect(alertDialogMock).toHaveBeenCalledWith('删除失败')
      expect(modal.textContent).toContain('测试歌曲二')

      apiMock.adminExportWishesCsv.mockRejectedValueOnce({})
      clickButton(modal, '导出 CSV')
      await flushUi()
      expect(alertDialogMock).toHaveBeenCalledWith('导出失败')

      apiMock.adminDownloadDiagnostics.mockResolvedValueOnce(new Blob(['diagnostics']))
      clickButton(root.querySelector('#section-maintenance'), '下载诊断日志包')
      await flushUi()
      expect(apiMock.adminDownloadDiagnostics).toHaveBeenCalledOnce()
      expect(anchorClick).toHaveBeenCalledTimes(2)

      apiMock.adminDownloadDiagnostics.mockRejectedValueOnce({})
      clickButton(root.querySelector('#section-maintenance'), '下载诊断日志包')
      await flushUi()
      expect(alertDialogMock).toHaveBeenCalledWith('下载诊断包失败')
    } finally {
      if (originalCreate) Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: originalCreate })
      else delete URL.createObjectURL
      if (originalRevoke) Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: originalRevoke })
      else delete URL.revokeObjectURL
      HTMLAnchorElement.prototype.click = originalClick
    }
  })

  it('handles logo upload without a URL and falls back when an error has no message', async () => {
    await resetHarness({ section: 'tv' })
    const tv = root.querySelector('#section-tv')
    apiMock.adminUploadStandbyLogo.mockResolvedValueOnce({})
    await uploadLogo(tv)
    await flushUi()
    expect(tv.textContent).toContain('使用默认 Logo')
    expect(root.textContent).not.toContain('有未保存的修改')

    apiMock.adminUploadStandbyLogo.mockRejectedValueOnce({})
    await uploadLogo(tv)
    await flushUi()
    expect(alertDialogMock).toHaveBeenCalledWith('Logo 上传失败')
  })

  it('uses the current draft as the logo baseline when the initial settings request is pending', async () => {
    let resolveSettings
    const pendingSettings = new Promise(resolve => { resolveSettings = resolve })
    await resetHarness({ section: 'tv', settings: () => pendingSettings })
    const tv = root.querySelector('#section-tv')

    await uploadLogo(tv)
    await flushUi()
    expect(tv.textContent).toContain('已配置自定义 Logo')

    resolveSettings({ standby_logo_configured: true })
    await flushUi()
    expect(tv.textContent).toContain('已配置自定义 Logo')
  })

  it('prevents duplicate exports and diagnostic downloads while a request is pending', async () => {
    await resetHarness({ section: 'maintenance', wishes: [{ id: 1, keyword: '忙碌测试', createdAt: '' }] })
    const originalCreate = URL.createObjectURL
    const originalRevoke = URL.revokeObjectURL
    const originalClick = HTMLAnchorElement.prototype.click
    let resolveExport
    let resolveDiagnostics
    const pendingExport = new Promise(resolve => { resolveExport = resolve })
    const pendingDiagnostics = new Promise(resolve => { resolveDiagnostics = resolve })
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: vi.fn(() => 'blob:busy-test') })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: vi.fn() })
    HTMLAnchorElement.prototype.click = vi.fn()
    try {
      clickButton(root, '查看详情 / 导出')
      await flushUi()
      const exportButton = findButton(root.querySelector('.wish-modal'), '导出 CSV')
      apiMock.adminExportWishesCsv.mockImplementationOnce(() => pendingExport)
      exportButton.click()
      await flushUi()
      expect(exportButton.disabled).toBe(true)
      exportButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      expect(apiMock.adminExportWishesCsv).toHaveBeenCalledOnce()
      resolveExport(new Blob(['csv']))
      await flushUi()

      const diagnosticsButton = findButton(root.querySelector('#section-maintenance'), '下载诊断日志包')
      apiMock.adminDownloadDiagnostics.mockImplementationOnce(() => pendingDiagnostics)
      diagnosticsButton.click()
      await flushUi()
      expect(diagnosticsButton.disabled).toBe(true)
      diagnosticsButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      expect(apiMock.adminDownloadDiagnostics).toHaveBeenCalledOnce()
      resolveDiagnostics(new Blob(['diagnostics']))
      await flushUi()
    } finally {
      if (originalCreate) Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: originalCreate })
      else delete URL.createObjectURL
      if (originalRevoke) Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: originalRevoke })
      else delete URL.revokeObjectURL
      HTMLAnchorElement.prototype.click = originalClick
    }
  })

  it('guards repeated save and restore actions while their first request is pending', async () => {
    let resolveSave
    const pendingSave = new Promise(resolve => { resolveSave = resolve })
    await resetHarness({ section: 'transcode', sourceLibrary: { libraryMode: 'MANAGED' } })
    const outputWhileSaving = root.querySelector('#section-transcode select')
    outputWhileSaving.value = 'mp4'
    outputWhileSaving.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    apiMock.adminPutSettings.mockImplementationOnce(() => pendingSave)
    const saveButton = findButton(root, '保存设置')
    saveButton.click()
    await flushUi()
    expect(saveButton.disabled).toBe(true)
    saveButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(apiMock.adminPutSettings).toHaveBeenCalledOnce()
    const restoreWhileSaving = findButton(root.querySelector('#section-transcode'), '恢复默认')
    restoreWhileSaving.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(confirmDialogMock).not.toHaveBeenCalled()
    expect(apiMock.adminResetTranscodeDefaults).not.toHaveBeenCalled()
    resolveSave({ standby_welcome: '正在保存的草稿' })
    await flushUi()

    let resolveRestore
    const pendingRestore = new Promise(resolve => { resolveRestore = resolve })
    await resetHarness({ section: 'transcode', sourceLibrary: { libraryMode: 'MANAGED' } })
    const outputContainer = root.querySelector('#section-transcode select')
    outputContainer.value = 'mp4'
    outputContainer.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    apiMock.adminResetTranscodeDefaults.mockImplementationOnce(() => pendingRestore)
    confirmDialogMock.mockResolvedValueOnce(true)
    const restoreButton = findButton(root.querySelector('#section-transcode'), '恢复默认')
    restoreButton.click()
    await flushUi()
    expect(restoreButton.disabled).toBe(true)
    const disabledSaveButton = findButton(root, '保存设置')
    disabledSaveButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(apiMock.adminPutSettings).not.toHaveBeenCalled()
    resolveRestore({})
    await flushUi()
  })
})

function clickButton(container, text, index = 0) {
  const button = [...container.querySelectorAll('button')].filter(item => item.textContent.includes(text))[index]
  if (!button) throw new Error(`button containing "${text}" was not found`)
  button.click()
  return button
}

function findButton(container, text, index = 0) {
  const button = [...container.querySelectorAll('button')].filter(item => item.textContent.includes(text))[index]
  if (!button) throw new Error(`button containing "${text}" was not found`)
  return button
}

async function uploadLogo(section) {
  const fileInput = section.querySelector('#standby_logo input[type="file"]')
  Object.defineProperty(fileInput, 'files', {
    configurable: true,
    value: [new File(['logo'], 'logo.png', { type: 'image/png' })]
  })
  fileInput.dispatchEvent(new Event('change', { bubbles: true }))
}

async function flushUi() {
  for (let index = 0; index < 8; index += 1) {
    await Promise.resolve()
    await nextTick()
  }
}
