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
    adminUploadStandbyLogo: vi.fn()
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

  beforeEach(async () => {
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
    apiMock.adminAiConfig.mockResolvedValue({ enabled: false, baseUrl: '', bulkModel: '' })
    apiMock.adminMusicSourceConfig.mockResolvedValue({ enabled: false, providers: [] })
    apiMock.adminTranscodeHardware.mockResolvedValue({ available: false, reason: 'test' })
    apiMock.adminWishes.mockResolvedValue([])
    apiMock.adminSourceLibrary.mockResolvedValue({ libraryMode: 'EXTERNAL_READ_ONLY' })
    apiMock.releaseInfo.mockResolvedValue({ version: 'test' })
    apiMock.adminUploadStandbyLogo.mockResolvedValue({ logoUrl: '/api/standby/logo' })

    const router = createRouter({
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
    await router.push('/settings?section=tv')
    await router.isReady()
    app.mount(root)
    await flushUi()
  })

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
})

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
