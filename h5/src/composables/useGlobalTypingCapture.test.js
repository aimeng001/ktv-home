// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useGlobalTypingCapture } from './useGlobalTypingCapture'

describe('useGlobalTypingCapture 键盘盲打全局捕获', () => {
  let onChar
  let onBackspace
  let isBlocked
  let capture

  beforeEach(() => {
    document.body.innerHTML = ''
    onChar = vi.fn()
    onBackspace = vi.fn()
    isBlocked = vi.fn(() => false)
  })

  afterEach(() => {
    capture?.dispose()
  })

  it('在普通页面状态下敲击单个英文字母，触发 onChar 并转为大写', () => {
    capture = useGlobalTypingCapture({ onChar, onBackspace, isBlocked })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', bubbles: true }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'N', bubbles: true }))

    expect(onChar).toHaveBeenCalledTimes(2)
    expect(onChar).toHaveBeenNthCalledWith(1, 'S')
    expect(onChar).toHaveBeenNthCalledWith(2, 'N')
  })

  it('敲击 Backspace 触发 onBackspace', () => {
    capture = useGlobalTypingCapture({ onChar, onBackspace, isBlocked })
    const event = new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true, cancelable: true })
    window.dispatchEvent(event)

    expect(onBackspace).toHaveBeenCalledTimes(1)
    expect(event.defaultPrevented).toBe(true)
  })

  it('当 activeElement 为 INPUT 或 TEXTAREA 时，绝不截获键盘输入', () => {
    capture = useGlobalTypingCapture({ onChar, onBackspace, isBlocked })
    const input = document.createElement('input')
    document.body.appendChild(input)
    input.focus()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true }))

    expect(onChar).not.toHaveBeenCalled()
    expect(onBackspace).not.toHaveBeenCalled()
  })

  it('当处于对话框或活动遮罩内时，不截获输入', () => {
    capture = useGlobalTypingCapture({ onChar, onBackspace, isBlocked })
    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'dialog')
    const button = document.createElement('button')
    dialog.appendChild(button)
    document.body.appendChild(dialog)
    button.focus()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', bubbles: true }))

    expect(onChar).not.toHaveBeenCalled()
  })

  it('当携带 Ctrl/Alt/Meta 组合键时，不截获（保护系统快捷键）', () => {
    capture = useGlobalTypingCapture({ onChar, onBackspace, isBlocked })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'c', ctrlKey: true, bubbles: true }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'v', metaKey: true, bubbles: true }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'r', altKey: true, bubbles: true }))

    expect(onChar).not.toHaveBeenCalled()
  })

  it('当 isBlocked 返回 true 时，不截获输入', () => {
    isBlocked.mockReturnValue(true)
    capture = useGlobalTypingCapture({ onChar, onBackspace, isBlocked })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', bubbles: true }))

    expect(onChar).not.toHaveBeenCalled()
  })

  it('当处于 select 元素或 .modal-mask 内时，不截获输入', () => {
    capture = useGlobalTypingCapture({ onChar, onBackspace, isBlocked })
    const select = document.createElement('select')
    document.body.appendChild(select)
    select.focus()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'w', bubbles: true }))
    expect(onChar).not.toHaveBeenCalled()
  })
})
