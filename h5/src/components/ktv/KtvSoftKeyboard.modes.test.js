// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import KtvSoftKeyboard from './KtvSoftKeyboard.vue'

describe('KtvSoftKeyboard 三模切换与数字点歌', () => {
  let app
  let root
  let pinia
  let updatedValue = ''
  let updatedWordCount = null

  beforeEach(() => {
    updatedValue = ''
    updatedWordCount = null
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

  async function mountKeyboard(initialValue = '', initialWordCount = 0) {
    app = createApp(KtvSoftKeyboard, {
      modelValue: initialValue,
      wordCount: initialWordCount,
      'onUpdate:modelValue': (val) => { updatedValue = val },
      'onUpdate:wordCount': (val) => { updatedWordCount = val }
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()
  }

  it('支持在【全拼 26 键】、【T9 九宫格】与【数字小键盘】三模之间切换', async () => {
    await mountKeyboard()
    const modeButtons = root.querySelectorAll('.kb-mode-tabs button')
    expect(modeButtons.length).toBe(3)
    expect(modeButtons[0].textContent).toContain('全拼')
    expect(modeButtons[1].textContent).toContain('T9')
    expect(modeButtons[2].textContent).toContain('数字')

    // 默认 qwerty 包含 26 键
    expect(root.querySelector('.qwerty-wrap')).not.toBeNull()
    expect(root.querySelector('.number-wrap')).toBeNull()

    // 点击切换为数字小键盘
    modeButtons[2].click()
    await nextTick()
    expect(root.querySelector('.number-wrap')).not.toBeNull()
    expect(root.querySelector('.qwerty-wrap')).toBeNull()
  })

  it('数字小键盘模式直接输入纯数字编号点歌，并重置 wordCount 避免冲突', async () => {
    await mountKeyboard('', 3)
    const modeButtons = root.querySelectorAll('.kb-mode-tabs button')
    modeButtons[2].click() // 切换到数字小键盘
    await nextTick()

    // 切换到数字键盘时自动重置 wordCount 为 0
    expect(updatedWordCount).toBe(0)

    // 点击数字键 '8', '1', '0'
    const numKeys = root.querySelectorAll('.number-key')
    expect(numKeys.length).toBe(12)

    // 找到数字 '8' 按键并点击
    const key8 = [...numKeys].find(k => k.textContent.trim() === '8')
    expect(key8).not.toBeNull()
    key8.click()
    expect(updatedValue).toBe('8')
  })
})
