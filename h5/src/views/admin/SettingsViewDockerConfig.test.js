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
    adminMemoryDiagnostics: vi.fn(),
    adminPutSettings: vi.fn(),
    adminAiPutConfig: vi.fn(),
    adminPutMusicSourceConfig: vi.fn(),
    adminUploadStandbyLogo: vi.fn(),
    adminAiModels: vi.fn(),
    adminAiTestConfig: vi.fn(),
    adminTestMusicSources: vi.fn(),
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

describe('SettingsView Docker settings and runtime diagnostics', () => {
  let app
  let root
  let router

  async function mountHarness({ section = 'basic', libraryMode = 'EXTERNAL_READ_ONLY', memoryDiag = null } = {}) {
    app?.unmount()
    root?.remove()
    app = undefined
    root = undefined
    router = undefined
    vi.clearAllMocks()

    apiMock.adminGetSettings.mockResolvedValue({
      library_watch_enabled: true,
      library_filename_metadata_preferred: true,
      discovery_name: '一号包厢',
      discovery_enabled: true,
      display_address: '192.168.1.100:8080',
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
      standby_welcome: '欢迎光临',
      standby_subtitle: '手机点歌',
      standby_logo_configured: false,
      standby_carousel: true,
      standby_source: 'mixed',
      standby_song_ids: [],
      standby_interval_sec: 8,
      anti_burn: true,
      mini_qr: true,
      tv_video_scale_mode: 'zoom'
    })

    apiMock.adminAiConfig.mockResolvedValue({
      enabled: true,
      baseUrl: 'http://192.168.1.50:11434/v1',
      bulkModel: 'qwen2.5:7b',
      reasoningModel: 'deepseek-r1:7b',
      timeoutSeconds: 30,
      identityThreshold: 0.97,
      classificationThreshold: 0.92,
      jsonMode: 'AUTO',
      bulkConcurrency: 2,
      reasoningConcurrency: 1,
      allowPrivateNetwork: true,
      apiKeyConfigured: false,
      sources: {}
    })

    apiMock.adminMusicSourceConfig.mockResolvedValue({
      enabled: true,
      providers: ['QQ', 'NETEASE'],
      resultLimit: 20,
      timeoutSeconds: 5,
      searchCacheHours: 6,
      concurrencyLimit: 2,
      requestIntervalMs: 1500,
      autoApplyThreshold: 0.95,
      dailyLimit: 600,
      providerStatus: [
        { provider: 'QQ', displayName: 'QQ 音乐', healthy: true, requestCount: 42, dailyLimit: 600 }
      ]
    })

    apiMock.adminTranscodeHardware.mockResolvedValue({ available: false, reason: 'none' })
    apiMock.adminWishes.mockResolvedValue([])
    apiMock.adminSourceLibrary.mockResolvedValue({ libraryMode })
    apiMock.releaseInfo.mockResolvedValue({ version: '1.0.28' })
    apiMock.adminMemoryDiagnostics.mockResolvedValue(memoryDiag || {
      heapUsedBytes: 536870912, // 512 MB
      heapCommittedBytes: 1073741824, // 1024 MB
      heapMaxBytes: 2147483648 // 2048 MB -> 25%
    })

    apiMock.adminPutSettings.mockImplementation(async (payload) => ({ ...payload }))
    apiMock.adminAiPutConfig.mockImplementation(async (payload) => ({ ...payload }))
    apiMock.adminPutMusicSourceConfig.mockImplementation(async (payload) => ({ ...payload }))

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div>home</div>' } },
        { path: '/admin/settings', component: SettingsView },
        { path: '/admin/ktv-library', name: 'admin-ktv-library', component: { template: '<div />' } },
        { path: '/:pathMatch(.*)*', name: 'fallback', component: { template: '<div />' } }
      ]
    })

    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(router)

    await router.push(`/admin/settings?section=${section}`)
    app.mount(root)
    await nextTick()
    await flushAsync()
  }

  async function flushAsync() {
    for (let i = 0; i < 6; i++) {
      await Promise.resolve()
      await nextTick()
    }
  }

  afterEach(() => {
    app?.unmount()
    root?.remove()
    app = undefined
    root = undefined
    router = undefined
  })

  it('renders basic section with filename metadata preference, room name, and discovery toggle', async () => {
    await mountHarness({ section: 'basic' })

    const filenameToggle = root.querySelector('#library_filename_metadata_preferred button.switch')
    expect(filenameToggle).not.toBeNull()
    expect(filenameToggle.getAttribute('aria-label')).toBe('已开启')

    const roomInput = root.querySelector('#discovery_name input')
    expect(roomInput).not.toBeNull()
    expect(roomInput.value).toBe('一号包厢')

    const discoveryToggle = root.querySelector('#discovery_enabled button.switch')
    expect(discoveryToggle).not.toBeNull()
    expect(discoveryToggle.getAttribute('aria-label')).toBe('已开启')
  })

  it('renders AI advanced section with allowPrivateNetwork toggle reflecting initial state', async () => {
    await mountHarness({ section: 'ai' })

    const privateToggle = root.querySelector('#ai_allow_private_network button.switch')
    expect(privateToggle).not.toBeNull()
    expect(privateToggle.getAttribute('aria-label')).toBe('已开启')
  })

  it('renders music metadata section with dailyLimit input and provider usage row', async () => {
    await mountHarness({ section: 'metadata' })

    const dailyLimitInput = root.querySelector('#music_sources_daily_limit input')
    expect(dailyLimitInput).not.toBeNull()
    expect(Number(dailyLimitInput.value)).toBe(600)

    const providerHealth = root.querySelector('.provider-health')
    expect(providerHealth).not.toBeNull()
    expect(providerHealth.textContent).toContain('42 / 600')
  })

  it('renders JVM memory diagnostic board with percentage and byte calculations', async () => {
    await mountHarness({
      section: 'maintenance',
      memoryDiag: {
        heapUsedBytes: 536870912,
        heapCommittedBytes: 1073741824,
        heapMaxBytes: 2147483648
      }
    })

    const memoryPercent = root.querySelector('.memory-percent')
    expect(memoryPercent).not.toBeNull()
    expect(memoryPercent.textContent).toBe('25%')

    const memoryMeta = root.querySelector('.memory-meta')
    expect(memoryMeta.textContent).toContain('512 MB')
    expect(memoryMeta.textContent).toContain('1.0 GB')
    expect(memoryMeta.textContent).toContain('2.0 GB')
  })

  it('dynamically switches storage badge between EXTERNAL_READ_ONLY and MANAGED modes', async () => {
    await mountHarness({ section: 'maintenance', libraryMode: 'EXTERNAL_READ_ONLY' })
    const readOnlyBadge = root.querySelector('#section-maintenance .status-badge')
    expect(readOnlyBadge.textContent).toContain('EXTERNAL_READ_ONLY')

    await mountHarness({ section: 'maintenance', libraryMode: 'MANAGED' })
    const managedBadge = root.querySelector('#section-maintenance .status-badge')
    expect(managedBadge.textContent).toContain('MANAGED')
  })

  it('tracks dirty changes and persists modified docker settings', async () => {
    await mountHarness({ section: 'basic' })

    const roomInput = root.querySelector('#discovery_name input')
    roomInput.value = '豪华VIP包间'
    roomInput.dispatchEvent(new Event('input'))
    await flushAsync()

    const saveBar = root.querySelector('.save-bar')
    expect(saveBar).not.toBeNull()
    expect(saveBar.textContent).toContain('有未保存的修改')

    const saveButton = root.querySelector('.save-bar button:last-child')
    saveButton.click()
    await flushAsync()

    expect(apiMock.adminPutSettings).toHaveBeenCalledWith(
      expect.objectContaining({ discovery_name: '豪华VIP包间' })
    )
  })
})
