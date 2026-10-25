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

    document.body.querySelector('[data-dialog-confirm]').click()
    await flushUi()
    expect(await result).toBe(true)
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
    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
    await nextTick()
    expect(useDialog().state.open).toBe(true)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await flushUi()
    expect(await result).toBe(false)
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
