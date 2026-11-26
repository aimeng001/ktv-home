// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import KtvSoftKeyboard from './KtvSoftKeyboard.vue'

describe('KtvSoftKeyboard 中文输入法直输拼音首字母兼容', () => {
  let app
  let root
  let pinia
  const updates = []

  beforeEach(() => {
    updates.length = 0
    pinia = createPinia()
    setActivePinia(pinia)
    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
    vi.clearAllMocks()
  })

  it('中文 IME 组合态输入带分词符的 y\'q\'x\'s 时剥离分词符并保留输入框组合文本', async () => {
    app = createApp(KtvSoftKeyboard, {
      modelValue: '',
      wordCount: 0,
      'onUpdate:modelValue': (val) => { updates.push(val) }
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    const input = root.querySelector('.kb-input-row input')
    expect(input).not.toBeNull()

    input.dispatchEvent(new Event('compositionstart', { bubbles: true }))
    input.value = "y'q'x's"
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    expect(updates[updates.length - 1]).toBe('yqxs')
    // Composition draft keeps DOM input intact so browser IME isn't aborted
    expect(input.value).toBe("y'q'x's")

    input.value = 'yqxs'
    input.dispatchEvent(new Event('compositionend', { bubbles: true }))
    await nextTick()
    expect(updates[updates.length - 1]).toBe('yqxs')
  })
})
