// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import { alertDialog, confirmDialog, useDialog } from '../composables/useDialog'
import DialogHost from './DialogHost.vue'

describe('DialogHost rendered interactions', () => {
  let app
  let root
  let opener

  beforeEach(() => {
    useDialog().close(false)
    opener = document.createElement('button')
    opener.textContent = '打开对话框'
    document.body.appendChild(opener)
    opener.focus()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(DialogHost)
    app.mount(root)
  })

  afterEach(async () => {
    useDialog().close(false)
    await nextTick()
    await waitForTransition()
    app?.unmount()
    root?.remove()
    opener?.remove()
  })

  it('renders a warning confirmation and returns false on Escape', async () => {
    const pending = confirmDialog('确认清理合成数据？', { title: '测试确认' })
    await nextTick()
    await nextTick()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.getAttribute('aria-label')).toBe('测试确认')
    expect(dialog.querySelector('.dialog-icon').classList.contains('warning')).toBe(true)
    expect(dialog.querySelector('.dialog-btn.secondary').textContent).toBe('取消')
    expect(dialog.querySelector('[data-dialog-confirm]').textContent).toBe('确认')

    const editor = document.createElement('textarea')
    document.body.appendChild(editor)
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    editor.dispatchEvent(enter)
    expect(useDialog().state.open).toBe(true)
    expect(enter.defaultPrevented).toBe(false)
    editor.remove()

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    window.dispatchEvent(escape)
    await expect(pending).resolves.toBe(false)
    await nextTick()
    await waitForTransition()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(opener)
    expect(escape.defaultPrevented).toBe(true)
  })

  it('renders a success alert with one action and resolves true when acknowledged', async () => {
    const pending = alertDialog('操作完成', { tone: 'success', title: '完成' })
    await nextTick()
    await nextTick()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.getAttribute('aria-label')).toBe('完成')
    expect(dialog.querySelector('.dialog-icon').classList.contains('success')).toBe(true)
    expect(dialog.querySelector('.dialog-btn.secondary')).toBeNull()
    expect(dialog.querySelector('[data-dialog-confirm]').textContent).toBe('知道了')

    dialog.querySelector('[data-dialog-confirm]').click()
    await expect(pending).resolves.toBe(true)
  })
})

function waitForTransition() {
  return new Promise(resolve => setTimeout(resolve, 220))
}
