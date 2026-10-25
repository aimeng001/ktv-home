// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, nextTick } from 'vue'

const { apiMock, alertMock } = vi.hoisted(() => ({
  apiMock: {
    adminStatus: vi.fn(), getQueue: vi.fn(), adminSourceTranscodeProgress: vi.fn(),
    adminSourceLibrary: vi.fn(), adminScanProgress: vi.fn(), adminStartScan: vi.fn(),
    adminDownloadDiagnostics: vi.fn()
  },
  alertMock: vi.fn()
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../composables/useDialog', () => ({ alertDialog: alertMock }))
vi.mock('./AdminLayout.vue', () => ({ default: defineComponent({ template: '<div class="admin-layout"><slot /></div>' }) }))

import DashboardView from './DashboardView.vue'

describe('DashboardView', () => {
  let app
  let root
  let urlMethodDescriptors

  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.adminStatus.mockResolvedValue({ totalSongs: 7, unrecognizedCount: 2, connectedClients: 1, ktvCount: 5, mvCount: 1, audioCount: 1 })
    apiMock.getQueue.mockResolvedValue({ state: 'playing' })
    apiMock.adminSourceTranscodeProgress.mockResolvedValue({ running: false, finishedAt: '', transcoded: 0, failed: 0 })
    apiMock.adminSourceLibrary.mockImplementation(async params => params.status === 'pending'
      ? { libraryMode: 'MANAGED', total: 3 }
      : { libraryMode: 'MANAGED', total: 12 })
    apiMock.adminScanProgress.mockResolvedValue({ running: false })
    apiMock.adminStartScan.mockResolvedValue({ running: true, phase: 'DISCOVERING', total: 0 })
    apiMock.adminDownloadDiagnostics.mockResolvedValue(new Blob(['diagnostics']))
    alertMock.mockResolvedValue(undefined)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
    app = null
    root = null
    if (urlMethodDescriptors) {
      for (const [name, descriptor] of Object.entries(urlMethodDescriptors)) {
        if (descriptor) Object.defineProperty(URL, name, descriptor)
        else delete URL[name]
      }
      urlMethodDescriptors = null
    }
    vi.restoreAllMocks()
  })

  it('loads service, queue, transcode, source, pending, and scan summaries', async () => {
    apiMock.getQueue.mockResolvedValueOnce({ state: 'paused' })
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: false, finishedAt: '2026-10-18T01:02:03Z', transcoded: 4, failed: 1 })
    await mount()

    expect(apiMock.adminStatus).toHaveBeenCalledOnce()
    expect(apiMock.getQueue).toHaveBeenCalledOnce()
    expect(apiMock.adminSourceTranscodeProgress).toHaveBeenCalledOnce()
    expect(apiMock.adminSourceLibrary).toHaveBeenNthCalledWith(1, { page: 0, size: 1 })
    expect(apiMock.adminSourceLibrary).toHaveBeenNthCalledWith(2, { status: 'pending', page: 0, size: 1 })
    expect(apiMock.adminScanProgress).toHaveBeenCalledOnce()
    expect(root.querySelector('.stats').textContent).toContain('12')
    expect(root.querySelector('.stats').textContent).toContain('3')
    expect(root.querySelector('.stats').textContent).toContain('7')
    expect(root.querySelector('.stats').textContent).toContain('2')
    expect(root.textContent).toContain('已暂停')
    expect(root.textContent).toContain('上次完成：成功 4，失败 1')
    expect(root.textContent).toContain('当前连接 1 台客户端')
  })

  it('keeps successful summaries visible when one dashboard service fails and clears the error after retry', async () => {
    apiMock.adminStatus.mockRejectedValueOnce(new Error('status endpoint unavailable'))
    await mount()

    expect(root.querySelector('[role="alert"]').textContent).toContain('status endpoint unavailable')
    expect(root.querySelector('.stats').textContent).toContain('12')
    root.querySelector('[role="alert"] .text-btn').click()
    await flushUi()

    expect(root.querySelector('[role="alert"]')).toBeNull()
    expect(root.querySelector('.stats').textContent).toContain('7')
  })

  it('uses safe defaults when response sections or failure messages are missing', async () => {
    apiMock.adminStatus.mockRejectedValueOnce({})
    apiMock.getQueue.mockRejectedValueOnce(new Error('queue unavailable'))
    apiMock.adminSourceLibrary.mockImplementation(async params => params.status === 'pending'
      ? Promise.reject({})
      : { total: 0 })
    await mount()

    expect(root.querySelector('[role="alert"]').textContent).toContain('请求失败')
    expect(root.querySelector('[role="alert"]').textContent).toContain('queue unavailable')
    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('0')
    expect(root.querySelector('tbody tr:nth-child(3)').textContent).toContain('空闲')
    expect(root.querySelector('tbody tr:nth-child(2)').textContent).toContain('暂无批量转码记录')
  })

  it('keeps zero source totals when the source endpoint returns no record', async () => {
    apiMock.adminSourceLibrary.mockImplementation(async params => params.status === 'pending'
      ? { total: 0 }
      : Promise.reject(new Error('source endpoint unavailable')))
    await mount()

    expect(root.querySelector('[role="alert"]').textContent).toContain('source endpoint unavailable')
    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('0')
    expect(root.querySelector('.stats article:first-child').textContent).toContain('原始素材')
  })

  it('keeps a zero total when a managed source response explicitly reports zero', async () => {
    apiMock.adminSourceLibrary.mockResolvedValue({ libraryMode: 'MANAGED', total: 0 })
    await mount()

    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('0')
    expect(root.querySelector('.stats article:first-child').textContent).toContain('原始素材')
  })

  it('uses external read-only labels and scan totals when the source API reports external mode', async () => {
    apiMock.adminStatus.mockResolvedValueOnce({ externalIndexedFiles: 0, totalSongs: 8 })
    apiMock.adminSourceLibrary.mockImplementation(async params => params.status === 'pending'
      ? { libraryMode: 'EXTERNAL_READ_ONLY', total: 99 }
      : { libraryMode: 'EXTERNAL_READ_ONLY', total: 0 })
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: false, total: 42, added: 2, updated: 3 })
    await mount()

    expect(root.querySelector('.stats').textContent).toContain('外部曲库文件')
    expect(root.querySelector('.stats').textContent).toContain('42')
    expect(root.querySelector('.stats').textContent).toContain('源文件转码')
    expect(root.querySelector('.stats').textContent).toContain('只读模式已禁用')
    expect(root.querySelectorAll('tbody tr')).toHaveLength(3)
    expect(root.textContent).toContain('外部 NAS 源文件不可转码')
    expect(root.textContent).not.toContain('查看进度')
  })

  it('uses the server indexed-file count when an external scan count is available', async () => {
    apiMock.adminStatus.mockResolvedValueOnce({ externalIndexedFiles: 13, totalSongs: 8 })
    apiMock.adminSourceLibrary.mockImplementation(async () => ({ libraryMode: 'EXTERNAL_READ_ONLY', total: 99 }))
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: false, total: 42, added: 2, updated: 3 })
    await mount()

    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('13')
  })

  it('uses zero when an external library has no indexed or scan total', async () => {
    apiMock.adminStatus.mockResolvedValueOnce({ externalIndexedFiles: 0, totalSongs: 8 })
    apiMock.adminSourceLibrary.mockImplementation(async () => ({ libraryMode: 'EXTERNAL_READ_ONLY', total: 99 }))
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: false })
    await mount()

    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('0')
    expect(root.querySelector('.stats article:first-child').textContent).toContain('外部曲库文件')
  })

  it('renders initial media-probe progress and active transcode details', async () => {
    let poll
    vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 90
    })
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: true, total: 4, completed: 2, currentFile: '' })
    apiMock.adminScanProgress.mockResolvedValueOnce({
      running: true, phase: 'MEDIA_PROBE', discovered: 8, fastIndexed: 8, probeQueued: 4,
      probeCompleted: 2, probedPerSecond: 5, estimatedRemainingSeconds: 65, currentFile: 'probe.mp4', failed: 1
    })
    await mount()

    expect(typeof poll).toBe('function')
    expect(root.querySelector('.scan-progress').textContent).toContain('正在读取媒体信息')
    expect(root.querySelector('.scan-progress').textContent).toContain('媒体探测 2 / 4')
    expect(root.querySelector('.scan-progress').textContent).toContain('速率 5 首/秒')
    expect(root.querySelector('.scan-progress').textContent).toContain('预计剩余 1分5秒')
    expect(root.querySelector('.scan-progress').textContent).toContain('失败/受阻 1')
    expect(root.querySelector('tbody tr:nth-child(2)').textContent).toContain('2/4，当前：准备中')
  })

  it('uses the fast-index phase hint when a scan resumes from its initial snapshot', async () => {
    vi.spyOn(window, 'setInterval').mockImplementation(() => 87)
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: true, phase: 'FAST_INDEX', discovered: 4, fastIndexed: 1 })
    await mount()

    expect(root.querySelector('.scan-progress').textContent).toContain('正在保存基础曲库')
    expect(root.querySelector('.scan-progress b').textContent).toBe('13%')
  })

  it('shows zero defaults for an active probe and formats a last transcode with zero counts', async () => {
    vi.spyOn(window, 'setInterval').mockImplementation(() => 88)
    apiMock.adminSourceTranscodeProgress.mockResolvedValueOnce({ running: false, finishedAt: '2026-10-18T01:02:03Z' })
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: true, phase: 'MEDIA_PROBE', probeQueued: 0, probeCompleted: 0 })
    await mount()

    expect(root.querySelector('.progress-meta').textContent).toContain('媒体探测 0 / 0')
    expect(root.querySelector('.progress-meta').textContent).not.toContain('预计剩余')
    expect(root.querySelector('tbody tr:nth-child(2)').textContent).toContain('上次完成：成功 0，失败 0')
  })

  it('uses external scan totals when service status is unavailable', async () => {
    apiMock.adminStatus.mockRejectedValueOnce(new Error('status unavailable'))
    apiMock.adminSourceLibrary.mockImplementation(async () => ({ libraryMode: 'EXTERNAL_READ_ONLY', total: 0 }))
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: false, total: 42, added: 2 })
    await mount()

    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('42')
    expect(root.querySelector('.stats article:first-child').textContent).toContain('外部曲库文件')
  })

  it('uses the default scan hint for an unknown server phase', async () => {
    vi.spyOn(window, 'setInterval').mockImplementation(() => 86)
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: true, phase: 'FUTURE_PHASE', total: 4 })
    await mount()

    expect(root.querySelector('.scan-progress').textContent).toContain('正在扫描源路径')
  })

  it('keeps the managed library mode when the source response omits it', async () => {
    apiMock.adminSourceLibrary.mockImplementation(async params => params.status === 'pending'
      ? { total: 2 }
      : { total: 9 })
    await mount()

    expect(root.querySelector('.stats article:first-child').textContent).toContain('原始素材')
    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('9')
  })

  it('retains external read-only mode when a refreshed source response omits the mode', async () => {
    let sourceListCalls = 0
    apiMock.adminStatus.mockRejectedValueOnce(new Error('status unavailable'))
    apiMock.adminStatus.mockResolvedValue({ externalIndexedFiles: null, totalSongs: 8 })
    apiMock.adminSourceLibrary.mockImplementation(async params => {
      if (params.status === 'pending') return { libraryMode: 'EXTERNAL_READ_ONLY', total: 3 }
      sourceListCalls += 1
      return sourceListCalls === 1
        ? { libraryMode: 'EXTERNAL_READ_ONLY', total: 99 }
        : { total: 5 }
    })
    apiMock.adminScanProgress.mockResolvedValue({ running: false, total: 42, added: 2 })
    await mount()

    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('42')
    root.querySelector('.error-notice .text-btn').click()
    await flushUi()

    expect(root.querySelector('.stats article:first-child').textContent).toContain('外部曲库文件')
    expect(root.querySelector('.stats article:first-child strong').textContent).toBe('42')
  })

  it('renders failed scan details with a safe fallback when the server omits an error message', async () => {
    apiMock.adminScanProgress.mockResolvedValueOnce({
      running: false, phase: 'FAILED', state: 'FAILED', finishedAt: '2026-10-18T01:02:03Z', total: 4
    })
    await mount()

    expect(root.querySelector('.scan-progress').classList.contains('failed')).toBe(true)
    expect(root.querySelector('.scan-progress').textContent).toContain('扫描失败')
    expect(root.querySelector('.scan-progress .error-msg').textContent).toContain('曲库文件读取受阻')
    expect(root.querySelector('.scan-progress b').textContent).toBe('0%')
  })

  it('preserves partial-scan details when the completion refresh returns the terminal record', async () => {
    let poll
    let resolveRefresh
    vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 89
    })
    await mount()
    root.querySelector('.page-head .primary').click()
    await flushUi()
    apiMock.adminScanProgress
      .mockResolvedValueOnce({ running: false, state: 'PARTIAL', phase: 'COMPLETED', total: 2, completed: 1 })
      .mockReturnValueOnce(new Promise(resolve => { resolveRefresh = resolve }))

    const pendingPoll = poll()
    await flushUi()

    expect(root.querySelector('.scan-progress').textContent).toContain('部分扫描完成')
    expect(root.querySelector('.scan-progress').textContent).not.toContain('完成于')
    resolveRefresh({ running: false, state: 'PARTIAL', phase: 'COMPLETED', finishedAt: '2026-10-18T01:02:03Z', total: 2, completed: 1 })
    await pendingPoll
    await flushUi()

    expect(root.querySelector('.scan-progress').textContent).toContain('完成于')
    expect(root.querySelector('.scan-progress b').textContent).toBe('100%')
  })

  it('keeps the newest dashboard load when an older request resolves late', async () => {
    let resolveOldStatus
    await mount()
    apiMock.adminStatus.mockReturnValueOnce(new Promise(resolve => { resolveOldStatus = resolve }))

    root.querySelector('.panel-head .text-btn').click()
    await flushUi()
    apiMock.adminStatus.mockResolvedValueOnce({ totalSongs: 99, unrecognizedCount: 1 })
    root.querySelector('.panel-head .text-btn').click()
    await flushUi()
    resolveOldStatus({ totalSongs: 101, unrecognizedCount: 3 })
    await flushUi()

    expect(root.querySelector('.stats article:nth-child(2) strong').textContent).toBe('99')
    expect(root.querySelector('.stats article:nth-child(4) strong').textContent).toBe('1')
  })

  it('starts scanning, polls to completion, stops the timer, and reloads dashboard summaries', async () => {
    let poll
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 91
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    await mount()
    apiMock.adminScanProgress.mockResolvedValueOnce({
      running: false, phase: 'COMPLETED', state: 'COMPLETED', finishedAt: '2026-10-18T01:02:03Z',
      total: 2, completed: 2, copied: 1, pendingTranscode: 1
    })

    root.querySelector('.page-head .primary').click()
    await flushUi()
    expect(apiMock.adminStartScan).toHaveBeenCalledOnce()
    expect(root.querySelector('.page-head .primary').disabled).toBe(true)
    expect(typeof poll).toBe('function')
    await poll()
    await flushUi()

    expect(clearIntervalSpy).toHaveBeenCalledWith(91)
    expect(apiMock.adminScanProgress).toHaveBeenCalledTimes(3)
    expect(apiMock.adminStatus).toHaveBeenCalledTimes(2)
    expect(root.querySelector('.scan-progress').textContent).toContain('扫描完成')
    expect(root.querySelector('.scan-progress b').textContent).toBe('100%')
    expect(root.querySelector('.page-head .primary').disabled).toBe(false)
  })

  it('marks scanning failed and stops polling after three consecutive progress errors', async () => {
    let poll
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 92
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    await mount()
    root.querySelector('.page-head .primary').click()
    await flushUi()
    apiMock.adminScanProgress.mockRejectedValueOnce({})
    apiMock.adminScanProgress.mockRejectedValue(new Error('temporary scan API outage'))

    await poll()
    expect(root.querySelector('[role="alert"]').textContent).toContain('扫描进度读取失败，正在重试')
    await poll()
    await poll()

    expect(clearIntervalSpy).toHaveBeenCalledWith(92)
    expect(root.querySelector('.scan-progress').classList.contains('failed')).toBe(true)
    expect(root.querySelector('.scan-progress').textContent).toContain('扫描进度连续读取失败')
    expect(root.querySelector('[role="alert"]').textContent).toContain('temporary scan API outage')
    expect(root.querySelector('.page-head .primary').disabled).toBe(false)
    expect(setIntervalSpy).toHaveBeenCalledOnce()
  })

  it('discards an in-flight scan response after the dashboard unmounts', async () => {
    let poll
    let resolvePendingPoll
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 93
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: true, phase: 'FAST_INDEX', total: 1 })
    await mount()
    apiMock.adminScanProgress.mockReturnValueOnce(new Promise(resolve => { resolvePendingPoll = resolve }))
    const pendingPoll = poll()
    app.unmount()
    app = null
    resolvePendingPoll({ running: false, phase: 'COMPLETED', completed: 1 })
    await pendingPoll

    expect(clearIntervalSpy).toHaveBeenCalledWith(93)
    expect(apiMock.adminStatus).toHaveBeenCalledOnce()
    expect(apiMock.adminScanProgress).toHaveBeenCalledTimes(2)
    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
  })

  it('coalesces overlapping scan polls and clears the queued retry on unmount', async () => {
    let poll
    let resolvePendingPoll
    let resolveNextPoll
    let queuedRetry
    let nextTimerId = 95
    const setIntervalSpy = vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 94
    })
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    const setTimeoutSpy = vi.spyOn(window, 'setTimeout').mockImplementation(callback => {
      queuedRetry = callback
      return nextTimerId++
    })
    const clearTimeoutSpy = vi.spyOn(window, 'clearTimeout')
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: true, phase: 'FAST_INDEX', total: 1 })
    await mount()
    apiMock.adminScanProgress.mockReturnValueOnce(new Promise(resolve => { resolvePendingPoll = resolve }))

    const pendingPoll = poll()
    await poll()
    expect(apiMock.adminScanProgress).toHaveBeenCalledTimes(2)
    resolvePendingPoll({ running: true, phase: 'FAST_INDEX', total: 1, fastIndexed: 1 })
    await pendingPoll
    await flushUi()
    expect(typeof queuedRetry).toBe('function')

    apiMock.adminScanProgress.mockReturnValueOnce(new Promise(resolve => { resolveNextPoll = resolve }))
    const secondPendingPoll = poll()
    await poll()
    resolveNextPoll({ running: true, phase: 'FAST_INDEX', total: 1, fastIndexed: 1 })
    await secondPendingPoll
    await flushUi()
    expect(clearTimeoutSpy).toHaveBeenCalledWith(95)
    expect(setTimeoutSpy).toHaveBeenCalledTimes(2)

    app.unmount()
    app = null
    expect(clearIntervalSpy).toHaveBeenCalledWith(94)
    expect(clearTimeoutSpy).toHaveBeenCalledWith(96)
    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
    setTimeoutSpy.mockRestore()
    clearTimeoutSpy.mockRestore()
  })

  it('ignores an in-flight scan error after the dashboard unmounts', async () => {
    let poll
    let rejectPendingPoll
    vi.spyOn(window, 'setInterval').mockImplementation(callback => {
      poll = callback
      return 96
    })
    apiMock.adminScanProgress.mockResolvedValueOnce({ running: true, phase: 'FAST_INDEX', total: 1 })
    await mount()
    apiMock.adminScanProgress.mockReturnValueOnce(new Promise((_, reject) => { rejectPendingPoll = reject }))
    const pendingPoll = poll()
    app.unmount()
    app = null
    rejectPendingPoll(new Error('late scan failure'))
    await pendingPoll

    expect(apiMock.adminStatus).toHaveBeenCalledOnce()
    expect(apiMock.adminScanProgress).toHaveBeenCalledTimes(2)
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('reports a fallback when the scan cannot be started', async () => {
    apiMock.adminStartScan.mockRejectedValueOnce({})
    await mount()

    root.querySelector('.page-head .primary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('扫描失败')
    expect(root.querySelector('.page-head .primary').disabled).toBe(false)
  })

  it('does not issue duplicate scan starts when the user clicks twice during startup', async () => {
    let resolveStart
    apiMock.adminStartScan.mockReturnValueOnce(new Promise(resolve => { resolveStart = resolve }))
    await mount()

    const start = root.querySelector('.page-head .primary')
    start.click()
    start.click()

    expect(apiMock.adminStartScan).toHaveBeenCalledOnce()
    resolveStart({ running: true, phase: 'DISCOVERING', total: 0 })
    await flushUi()
  })

  it('ignores duplicate diagnostics downloads while the first request is pending', async () => {
    let rejectDownload
    apiMock.adminDownloadDiagnostics.mockReturnValueOnce(new Promise((_, reject) => { rejectDownload = reject }))
    await mount()

    const download = root.querySelector('.header-actions .secondary')
    download.click()
    download.click()

    expect(apiMock.adminDownloadDiagnostics).toHaveBeenCalledOnce()
    rejectDownload({})
    await flushUi()
    expect(alertMock).toHaveBeenCalledWith('下载诊断包失败')
    expect(download.disabled).toBe(false)
  })

  it('downloads diagnostics and revokes the temporary object URL', async () => {
    const createObjectURL = vi.fn(() => 'blob:diagnostics')
    const revokeObjectURL = vi.fn()
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    urlMethodDescriptors = {
      createObjectURL: Object.getOwnPropertyDescriptor(URL, 'createObjectURL'),
      revokeObjectURL: Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL')
    }
    URL.createObjectURL = createObjectURL
    URL.revokeObjectURL = revokeObjectURL
    await mount()

    root.querySelector('.header-actions .secondary').click()
    await flushUi()

    expect(apiMock.adminDownloadDiagnostics).toHaveBeenCalledOnce()
    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob))
    expect(anchorClick).toHaveBeenCalledOnce()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:diagnostics')
    expect(document.querySelector('a[download="home-ktv-diagnostics.zip"]')).toBeNull()
    expect(root.querySelector('.header-actions .secondary').disabled).toBe(false)
  })

  it('shows a fallback diagnostics error and always releases the busy state', async () => {
    apiMock.adminDownloadDiagnostics.mockRejectedValueOnce({})
    await mount()

    root.querySelector('.header-actions .secondary').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('下载诊断包失败')
    expect(root.querySelector('.header-actions .secondary').disabled).toBe(false)
  })

  async function mount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(DashboardView)
    app.component('router-link', defineComponent({ template: '<a><slot /></a>' }))
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
