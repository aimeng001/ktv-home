// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import DialogHost from './DialogHost.vue'
import { alertDialog, confirmDialog, useDialog } from '../composables/useDialog'

let app
let root

describe('DialogHost mounted behavior', () => {
  beforeEach(() => {
    useDialog().close(false)
  })

  afterEach(() => {
    useDialog().close(false)
    app?.unmount()
    root?.remove()
    document.body.querySelectorAll('.dialog-mask').forEach(node => node.remove())
  })

  it('focuses the confirm action and restores the opener after confirmation', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    await mount()

    const result = confirmDialog('确定清理吗？', { title: '清理确认' })
    await flushUi()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.getAttribute('aria-label')).toBe('清理确认')
    expect(document.activeElement.hasAttribute('data-dialog-confirm')).toBe(true)
    // 确认框默认使用 warning 色调图标，并渲染「取消 / 确认」两个动作。
    expect(dialog.querySelector('.dialog-icon').classList.contains('warning')).toBe(true)
    expect(dialog.querySelector('.dialog-btn.secondary').textContent).toBe('取消')
    expect(dialog.querySelector('[data-dialog-confirm]').textContent).toBe('确认')

    document.body.querySelector('[data-dialog-confirm]').click()
    await flushUi()
    expect(await result).toBe(true)
    await waitForTransition()
    // 确认后弹窗必须真正从 DOM 移除，而不是仅隐藏。
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(opener)
    opener.remove()
  })

  it('does not try to restore focus to a non-HTML opener', async () => {
    const opener = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    opener.setAttribute('tabindex', '0')
    document.body.appendChild(opener)
    opener.focus()
    expect(document.activeElement).toBe(opener)
    await mount()

    const result = alertDialog('操作完成')
    await flushUi()
    expect(document.activeElement.hasAttribute('data-dialog-confirm')).toBe(true)
    document.body.querySelector('[data-dialog-confirm]').click()
    await flushUi()

    expect(await result).toBe(true)
    expect(document.activeElement).not.toBe(opener)
    opener.remove()
  })

  it('does not restore focus to an opener removed while the dialog is open', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    await mount()

    const result = confirmDialog('确认？')
    await flushUi()
    opener.remove()
    document.body.querySelector('.dialog-btn.secondary').click()
    await flushUi()

    expect(await result).toBe(false)
    expect(opener.isConnected).toBe(false)
    expect(document.activeElement).not.toBe(opener)
  })

  it('renders alert as a single button and ignores Enter inside a textarea', async () => {
    await mount()
    const result = alertDialog('操作失败', { title: '失败提示', tone: 'error' })
    await flushUi()
    const actions = document.body.querySelectorAll('.dialog-actions button')
    expect(actions).toHaveLength(1)
    expect(document.body.querySelector('.dialog-icon.error')).not.toBeNull()
    expect(actions[0].textContent).toBe('知道了')

    const textarea = document.createElement('textarea')
    document.body.querySelector('[role="dialog"]').appendChild(textarea)
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    textarea.dispatchEvent(enter)
    await nextTick()
    expect(useDialog().state.open).toBe(true)
    // textarea 里的 Enter 应交给控件自己处理，宿主不得 preventDefault。
    expect(enter.defaultPrevented).toBe(false)

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    window.dispatchEvent(escape)
    await flushUi()
    expect(await result).toBe(false)
    // Escape 必须被宿主 preventDefault，否则浏览器默认行为会穿透到页面。
    expect(escape.defaultPrevented).toBe(true)

    // 同一用例内再验证 success 色调：图标切换为 success，且只保留「知道了」一个动作。
    const successResult = alertDialog('操作完成', { title: '完成', tone: 'success' })
    await flushUi()
    const successDialog = Array.from(document.body.querySelectorAll('[role="dialog"]')).pop()
    expect(successDialog.getAttribute('aria-label')).toBe('完成')
    expect(successDialog.querySelector('.dialog-icon').classList.contains('success')).toBe(true)
    expect(successDialog.querySelector('.dialog-btn.secondary')).toBeNull()
    expect(successDialog.querySelector('[data-dialog-confirm]').textContent).toBe('知道了')

    successDialog.querySelector('[data-dialog-confirm]').click()
    await flushUi()
    expect(await successResult).toBe(true)
  })

  it('keeps keyboard focus in the dialog and restores the background inert state', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    await mount()

    const result = confirmDialog('删除这首歌曲？')
    await flushUi()
    const dialog = document.body.querySelector('[role="dialog"]')
    const closeButton = document.body.querySelector('.dialog-close')
    const confirmButton = document.body.querySelector('[data-dialog-confirm]')

    expect(opener.inert).toBe(true)
    closeButton.focus()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(confirmButton)

    confirmButton.focus()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(closeButton)

    document.body.querySelector('.dialog-btn.secondary').click()
    await flushUi()
    expect(await result).toBe(false)
    expect(opener.inert).toBe(false)
    opener.remove()
  })

  it('does not confirm a destructive action when Enter is dispatched outside the dialog', async () => {
    const outsideButton = document.createElement('button')
    document.body.appendChild(outsideButton)
    await mount()

    const result = confirmDialog('确认删除？')
    await flushUi()
    outsideButton.focus()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
    await flushUi()

    expect(useDialog().state.open).toBe(true)
    document.body.querySelector('.dialog-btn.secondary').click()
    await flushUi()
    expect(await result).toBe(false)
    outsideButton.remove()
  })

  it('cancels confirmation from the secondary button or outside mask click', async () => {
    await mount()
    const first = confirmDialog('确认？')
    await flushUi()
    document.body.querySelector('.dialog-btn.secondary').click()
    await flushUi()
    expect(await first).toBe(false)

    const second = confirmDialog('再次确认？')
    await flushUi()
    document.body.querySelector('.dialog-mask').dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushUi()
    expect(await second).toBe(false)
  })

  it('removes the global keyboard handler when the host unmounts', async () => {
    await mount()
    const result = confirmDialog('确认？')
    await flushUi()

    app.unmount()
    app = null
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await flushUi()

    expect(useDialog().state.open).toBe(true)
    useDialog().close(false)
    expect(await result).toBe(false)
  })
})

async function mount() {
  root = document.createElement('div')
  document.body.appendChild(root)
  app = createApp(DialogHost)
  app.mount(root)
  await nextTick()
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

/** 等待 .dialog-fade 的 180ms 离场过渡结束，避免断言 DOM 移除时假红。 */
function waitForTransition() {
  return new Promise(resolve => setTimeout(resolve, 220))
}
