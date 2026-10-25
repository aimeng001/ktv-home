// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'

const { apiMock, alertMock, confirmMock } = vi.hoisted(() => ({
  apiMock: {
    adminAiTasks: vi.fn(), adminAiConfig: vi.fn(), adminAiCreateTask: vi.fn(),
    adminAiCreateUnclassified: vi.fn(), adminAiRepair: vi.fn(), adminAiRepairProgress: vi.fn(),
    adminAiPauseRepair: vi.fn(), adminAiResumeRepair: vi.fn(), adminAiRetryFailedRepair: vi.fn(),
    adminAiRetryTask: vi.fn(), adminAiApplyTask: vi.fn(), adminMergeSong: vi.fn()
  },
  alertMock: vi.fn(), confirmMock: vi.fn()
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../composables/useDialog', () => ({ alertDialog: alertMock, confirmDialog: confirmMock }))

import AiTaskPanel from './AiTaskPanel.vue'

describe('AiTaskPanel', () => {
  let app
  let root
  let tasks
  let appliedEvents

  beforeEach(() => {
    vi.clearAllMocks()
    tasks = []
    appliedEvents = 0
    apiMock.adminAiTasks.mockImplementation(async () => tasks)
    apiMock.adminAiConfig.mockResolvedValue({ enabled: true, baseUrl: 'http://ai.local', bulkModel: 'mock-model' })
    apiMock.adminAiCreateTask.mockResolvedValue({ id: 1 })
    apiMock.adminAiApplyTask.mockResolvedValue({})
    apiMock.adminAiRetryTask.mockResolvedValue({})
    apiMock.adminAiRepair.mockResolvedValue({ batchId: 'batch-1', created: 3, dispatchRejected: 1 })
    apiMock.adminAiRepairProgress.mockResolvedValue({ total: 3, completed: 1, review: 1, failed: 1, running: true, paused: false })
    apiMock.adminAiPauseRepair.mockResolvedValue({ total: 3, completed: 1, review: 1, failed: 1, running: false, paused: true })
    apiMock.adminAiResumeRepair.mockResolvedValue({})
    apiMock.adminAiRetryFailedRepair.mockResolvedValue({})
    confirmMock.mockResolvedValue(true)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('shows unconfigured/error states and keeps AI actions disabled', async () => {
    apiMock.adminAiConfig.mockResolvedValue({ enabled: false })
    apiMock.adminAiTasks.mockRejectedValue(new Error('任务服务不可用'))
    await mount()

    expect(root.textContent).toContain('AI 尚未配置')
    expect(root.textContent).toContain('AI 任务读取失败：任务服务不可用')
    expect(root.querySelector('.batch-tools .button.ghost').disabled).toBe(true)
    root.querySelector('.error-notice button').click()
    await flushUi()
    expect(apiMock.adminAiTasks).toHaveBeenCalledTimes(2)
  })

  it('uses a fallback message when a task endpoint rejects without an error message', async () => {
    apiMock.adminAiTasks.mockRejectedValue(new Error(''))
    await mount()

    expect(root.textContent).toContain('AI 任务读取失败：读取失败')
  })

  it('creates selected tasks with per-song failure reporting and refreshes the task list', async () => {
    apiMock.adminAiCreateTask.mockImplementation(async id => {
      if (id === 2) throw new Error('此曲已存在任务')
      return { id: 101 }
    })
    await mount({ selectedIds: [1, 2] })
    root.querySelector('.batch-tools .button:not(.ghost)').click()
    await flushUi()

    expect(apiMock.adminAiCreateTask).toHaveBeenCalledTimes(2)
    expect(root.textContent).toContain('已创建 1 个任务，跳过 1 首')
    expect(apiMock.adminAiTasks).toHaveBeenCalledTimes(2)
  })

  it('reports selected-task creation without a skipped count when every request succeeds', async () => {
    await mount({ selectedIds: [1, 2] })
    findButton('为选中歌曲生成').click()
    await flushUi()

    expect(apiMock.adminAiCreateTask).toHaveBeenCalledTimes(2)
    expect(root.textContent).toContain('已创建 2 个任务')
    expect(root.textContent).not.toContain('跳过')
  })

  it('creates unclassified tasks using the requested batch limit', async () => {
    apiMock.adminAiCreateUnclassified.mockResolvedValue({ created: 4 })
    await mount()
    const limit = root.querySelector('.batch-tools input[type="number"]')
    limit.value = '12'
    limit.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    findButton('分析未分类歌曲').click()
    await flushUi()

    expect(apiMock.adminAiCreateUnclassified).toHaveBeenCalledWith(12)
    expect(root.textContent).toContain('已创建 4 个任务')
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('reports failures from unclassified task creation and releases the busy state', async () => {
    apiMock.adminAiCreateUnclassified.mockRejectedValue(new Error('AI 队列暂不可用'))
    await mount()
    findButton('分析未分类歌曲').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('AI 队列暂不可用')
    expect(findButton('分析未分类歌曲').disabled).toBe(false)
  })

  it('shows a generic operation error when the rejected error has no message', async () => {
    apiMock.adminAiCreateUnclassified.mockRejectedValue(new Error(''))
    await mount()
    findButton('分析未分类歌曲').click()
    await flushUi()

    expect(alertMock).toHaveBeenCalledWith('操作失败')
  })

  it('applies reviewed fields after splitting tags and reports the applied event', async () => {
    tasks = [{
      id: 8, status: 'review', targetType: 'SONG', songId: 42, targetTitle: '原始歌名', targetArtist: '原始歌手',
      model: 'mock-model', createdAt: '2026-01-01T00:00:00Z',
      resultJson: JSON.stringify({ title: '建议歌名', artist: '建议歌手', confidence: 0.92, genres: ['流行'], themes: ['旅行'], reason: '依据充分' })
    }]
    await mount()
    const article = root.querySelector('.task')
    const inputs = article.querySelectorAll('.result-grid input')
    inputs[0].value = '采用歌名'
    inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[6].value = '流行，，经典, , rock，'
    inputs[6].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[7].value = '旅行,成长'
    inputs[7].dispatchEvent(new Event('input', { bubbles: true }))
    article.querySelector('textarea').value = '依据充分'
    article.querySelector('textarea').dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    article.querySelector('.actions .button.small').click()
    await flushUi()

    expect(apiMock.adminAiApplyTask).toHaveBeenCalledWith(8, expect.objectContaining({
      title: '采用歌名', artist: '建议歌手', genres: ['流行', '经典', 'rock'],
      themes: ['旅行', '成长'], reason: '依据充分', confidence: 0.92
    }))
    expect(appliedEvents).toBe(1)
  })

  it('applies an empty review without creating blank genre or theme tags', async () => {
    tasks = [{ id: 19, status: 'review', targetType: 'SONG', songId: 53, resultJson: '{}' }]
    await mount()
    root.querySelector('.result .actions .button.small').click()
    await flushUi()

    expect(apiMock.adminAiApplyTask).toHaveBeenCalledWith(19, expect.objectContaining({
      genres: [], themes: []
    }))
    expect(appliedEvents).toBe(1)
  })

  it('retries failed tasks and pauses, resumes, and retries a repair batch', async () => {
    tasks = [{ id: 9, status: 'failed', targetType: 'IMPORT_RECORD', targetId: 90, errorMessage: '原始错误' }]
    await mount()
    root.querySelector('.task-actions button').click()
    await flushUi()
    expect(apiMock.adminAiRetryTask).toHaveBeenCalledWith(9)

    root.querySelector('.head-actions button[title="刷新任务"]').click()
    await flushUi()
    root.querySelector('.batch-tools button:last-child').click()
    await flushUi()
    expect(apiMock.adminAiRepair).toHaveBeenCalledOnce()
    expect(root.textContent).toContain('修复批次')
    expect(root.textContent).toContain('1 个未能入队')

    const pauseButton = [...root.querySelectorAll('.repair-status button')].find(button => button.textContent.includes('暂停'))
    expect(pauseButton).toBeTruthy()
    expect(pauseButton.disabled).toBe(false)
    pauseButton.click()
    await flushUi()
    expect(apiMock.adminAiPauseRepair).toHaveBeenCalledWith('batch-1')
    expect(root.textContent).toContain('继续')
    root.querySelector('.repair-status button').click()
    await flushUi()
    expect(apiMock.adminAiResumeRepair).toHaveBeenCalledWith('batch-1')
    expect(apiMock.adminAiRepairProgress).toHaveBeenCalled()
  })

  it('retries failed repair items in the active batch', async () => {
    apiMock.adminAiRepair.mockResolvedValue({ batchId: 'batch-retry', created: 2, dispatchRejected: 0 })
    await mount()
    findButton('修复现有曲库').click()
    await flushUi()

    findButton('重试失败项').click()
    await flushUi()

    expect(apiMock.adminAiRetryFailedRepair).toHaveBeenCalledWith('batch-retry')
    expect(apiMock.adminAiRepairProgress).toHaveBeenCalledWith('batch-retry')
    expect(root.textContent).toContain('2 个存量修复任务')
    expect(root.textContent).not.toContain('未能入队')
  })

  it('merges a duplicate song only after confirmation and emits the applied event', async () => {
    tasks = [{
      id: 15, status: 'review', targetType: 'SONG', songId: 42, targetTitle: '重复歌曲',
      resultJson: JSON.stringify({ title: '重复歌曲', artist: '歌手' })
    }]
    await mount()
    const mergeId = root.querySelector('.merge-id')
    mergeId.value = '88'
    mergeId.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    findButton('合并重复歌曲').click()
    await flushUi()

    expect(confirmMock).toHaveBeenCalledWith(
      '《重复歌曲》的文件源和全部引用将迁移到歌曲 #88。',
      { title: '合并重复歌曲', tone: 'warning' }
    )
    expect(apiMock.adminMergeSong).toHaveBeenCalledWith(88, 42)
    expect(appliedEvents).toBe(1)
  })

  it('leaves duplicate songs unchanged when merge confirmation is declined', async () => {
    confirmMock.mockResolvedValue(false)
    tasks = [{ id: 16, status: 'review', targetType: 'SONG', songId: 43, resultJson: '{}' }]
    await mount()
    const mergeId = root.querySelector('.merge-id')
    mergeId.value = '89'
    mergeId.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    findButton('合并重复歌曲').click()
    await flushUi()

    expect(confirmMock).toHaveBeenCalledOnce()
    expect(apiMock.adminMergeSong).not.toHaveBeenCalled()
    expect(appliedEvents).toBe(0)
  })

  it('shows safe fallbacks for malformed AI results and incomplete import task data', async () => {
    tasks = [{ id: 17, status: 'queued', targetType: 'IMPORT_RECORD', targetId: 91, resultJson: '{broken json' }]
    await mount()

    const task = root.querySelector('.task')
    expect(task.querySelector('.task-name strong').textContent).toBe('待识别文件')
    expect(task.querySelector('.task-name span').textContent).toBe('未知歌手')
    expect(task.querySelector('.status').textContent).toBe('queued')
    expect(task.querySelector('.meta').textContent).toContain('导入记录 #91')
    expect(task.querySelector('.meta').textContent).toContain('模型 —')
    expect(task.querySelector('.confidence-low').textContent).toBe('0%')
  })

  it('renders labels for every known task state and preserves unknown states', async () => {
    const states = [
      ['pending', '待处理'], ['processing', '处理中'], ['paused', '已暂停'],
      ['review', '待人工复核'], ['applied', '人工已应用'],
      ['auto_applied', '高置信自动应用'], ['failed', '失败'], ['future_state', 'future_state']
    ]
    tasks = states.map(([status], index) => ({
      id: 30 + index, status, targetType: 'SONG', songId: 60 + index, resultJson: '{}'
    }))
    await mount()

    expect([...root.querySelectorAll('.task .status')].map(node => node.textContent)).toEqual(
      states.map(([, label]) => label)
    )
  })

  it('translates incompatible AI response errors into actionable guidance', async () => {
    tasks = [{ id: 18, status: 'failed', targetType: 'SONG', songId: 52, errorMessage: 'Cannot deserialize response payload' }]
    await mount()

    expect(root.querySelector('.error').textContent).toContain('AI 返回的字段格式不兼容，可重试')
    expect(root.querySelector('.error').textContent).not.toContain('Cannot deserialize')
  })

  async function mount(props = {}) {
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({
      render: () => h(AiTaskPanel, { ...props, onApplied: () => { appliedEvents++ } })
    })
    app.component('router-link', { props: ['to'], template: '<a><slot /></a>' })
    app.mount(root)
    await flushUi()
  }

  function findButton(text) {
    const button = [...root.querySelectorAll('button')].find(item => item.textContent.includes(text))
    expect(button, `button containing "${text}" should be present`).toBeTruthy()
    return button
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
