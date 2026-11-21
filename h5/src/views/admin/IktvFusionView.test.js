// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, nextTick } from 'vue'

const { apiMock, confirmMock, alertMock } = vi.hoisted(() => ({
  apiMock: {
    adminIktvFusionStatus: vi.fn(),
    adminIktvFusionBatch: vi.fn(),
    adminIktvFusionResume: vi.fn(),
    adminIktvFusionRollback: vi.fn()
  },
  confirmMock: vi.fn(),
  alertMock: vi.fn()
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../composables/useDialog', () => ({ confirmDialog: confirmMock, alertDialog: alertMock }))
vi.mock('./AdminLayout.vue', () => ({ default: defineComponent({ template: '<div class="admin-layout"><slot /></div>' }) }))

import IktvFusionView from './IktvFusionView.vue'

function buttonByText(root, text) {
  return [...root.querySelectorAll('button')].find(btn => (btn.textContent || '').includes(text)) || null
}

describe('IktvFusionView', () => {
  let app
  let root

  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.adminIktvFusionStatus.mockResolvedValue({
      activeSnapshotVersion: 'v12',
      pausedSnapshotVersion: null,
      pausedByBatchId: null,
      pauseReason: null,
      pausedAt: null
    })
    apiMock.adminIktvFusionBatch.mockResolvedValue({
      id: 7, state: 'COMPLETED', publicationState: 'PUBLISHED',
      totalCount: 100, processedCount: 100, matchedCount: 80, unmatchedCount: 20,
      conflictCount: 2, protectedCount: 1, failedCount: 0, rollbackConflictCount: 0
    })
    apiMock.adminIktvFusionResume.mockResolvedValue({ batchId: 7, conflictRows: 0 })
    apiMock.adminIktvFusionRollback.mockResolvedValue({
      batchId: 7, state: 'ROLLED_BACK',
      catalogNumbersChanged: 12, categoriesChanged: 12,
      conflictingCategoriesOrNumbers: 0, conflictRows: 0
    })
    confirmMock.mockResolvedValue(true)
    alertMock.mockResolvedValue(undefined)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
    app = null
    root = null
  })

  async function mountView() {
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(IktvFusionView)
    app.mount(root)
    await nextTick()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }

  it('加载并展示当前快照版本', async () => {
    await mountView()
    expect(root.textContent).toContain('v12')
  })

  it('无快照时显示空态', async () => {
    apiMock.adminIktvFusionStatus.mockResolvedValue({ activeSnapshotVersion: null })
    await mountView()
    expect(root.textContent).toContain('尚未')
  })

  it('非暂停态恢复按钮禁用', async () => {
    await mountView()
    expect(buttonByText(root, '恢复融合').disabled).toBe(true)
  })

  it('暂停态恢复按钮可用', async () => {
    apiMock.adminIktvFusionStatus.mockResolvedValue({
      activeSnapshotVersion: 'v12',
      pausedSnapshotVersion: 'v11',
      pausedByBatchId: 6,
      pauseReason: '元数据冲突',
      pausedAt: '2026-11-20T10:00:00Z'
    })
    await mountView()
    expect(buttonByText(root, '恢复融合').disabled).toBe(false)
  })

  it('点击回滚弹出二次确认且不立即调用接口', async () => {
    let resolveConfirm
    confirmMock.mockReturnValue(new Promise(resolve => { resolveConfirm = resolve }))
    await mountView()
    const input = root.querySelector('.batch-input input')
    input.value = '7'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    buttonByText(root, '回滚').click()
    await nextTick()
    expect(confirmMock).toHaveBeenCalled()
    expect(apiMock.adminIktvFusionRollback).not.toHaveBeenCalled()
    resolveConfirm(false)
  })

  it('确认文案包含分类影响提示', async () => {
    await mountView()
    const input = root.querySelector('.batch-input input')
    input.value = '7'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    buttonByText(root, '回滚').click()
    await nextTick()
    const text = String(confirmMock.mock.calls.at(-1)[0])
    expect(text).toContain('分类')
    expect(text).toContain('点歌端')
  })

  it('确认后才调用回滚接口', async () => {
    await mountView()
    const input = root.querySelector('.batch-input input')
    input.value = '7'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    buttonByText(root, '回滚').click()
    await nextTick()
    await nextTick()
    expect(apiMock.adminIktvFusionRollback).toHaveBeenCalledWith(7)
  })

  it('取消确认不调用接口', async () => {
    confirmMock.mockResolvedValue(false)
    await mountView()
    const input = root.querySelector('.batch-input input')
    input.value = '7'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    buttonByText(root, '回滚').click()
    await nextTick()
    await nextTick()
    expect(apiMock.adminIktvFusionRollback).not.toHaveBeenCalled()
  })

  it('回滚结果显示冲突计数', async () => {
    apiMock.adminIktvFusionRollback.mockResolvedValue({
      batchId: 7, state: 'ROLLED_BACK',
      catalogNumbersChanged: 12, categoriesChanged: 12,
      conflictingCategoriesOrNumbers: 3, conflictRows: 3
    })
    await mountView()
    const input = root.querySelector('.batch-input input')
    input.value = '7'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    buttonByText(root, '回滚').click()
    await nextTick()
    await nextTick()
    await nextTick()
    expect(root.textContent).toContain('3')
    expect(root.textContent).toContain('冲突')
  })

  it('恢复融合成功后显示「恢复结果」，不复用「回滚结果」卡片', async () => {
    apiMock.adminIktvFusionStatus.mockResolvedValue({
      activeSnapshotVersion: 'v12',
      pausedSnapshotVersion: 'v11',
      pausedByBatchId: 6,
      pauseReason: '元数据冲突',
      pausedAt: '2026-11-20T10:00:00Z'
    })
    apiMock.adminIktvFusionResume.mockResolvedValue({
      batchId: 7, state: 'RESUMED', catalogNumbersChanged: 3, categoriesChanged: 2, conflictRows: 0
    })
    await mountView()

    buttonByText(root, '恢复融合').click()
    await nextTick()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()

    expect(root.textContent).toContain('恢复结果')
    expect(root.textContent).not.toContain('回滚结果')
  })

  it('接口失败时提示错误', async () => {
    apiMock.adminIktvFusionRollback.mockRejectedValue(new Error('rollback failed'))
    await mountView()
    const input = root.querySelector('.batch-input input')
    input.value = '7'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    buttonByText(root, '回滚').click()
    await nextTick()
    await nextTick()
    await nextTick()
    expect(alertMock).toHaveBeenCalled()
  })

  it('状态拉取失败时显示错误与重试', async () => {
    apiMock.adminIktvFusionStatus.mockRejectedValue(new Error('status down'))
    await mountView()
    expect(root.textContent).toContain('重试')
  })
})
