// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick, ref } from 'vue'
import KtvSoftKeyboard from './KtvSoftKeyboard.vue'

describe('KtvSoftKeyboard word count filtering', () => {
  let app
  let root

  beforeEach(() => {
    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root?.parentNode) root.parentNode.removeChild(root)
  })

  it('renders word count capsule bar with all 7 presets', async () => {
    app = createApp(KtvSoftKeyboard, {
      modelValue: '',
      wordCount: 0,
    })
    app.mount(root)
    await nextTick()

    const chips = root.querySelectorAll('.word-count-chip')
    expect(chips.length).toBe(7)
    const labels = Array.from(chips).map(c => c.textContent.trim())
    expect(labels).toEqual(['全部', '1字', '2字', '3字', '4字', '5字', '多字'])
  })

  it('highlights the active word count chip matching prop', async () => {
    app = createApp(KtvSoftKeyboard, {
      modelValue: '',
      wordCount: 3,
    })
    app.mount(root)
    await nextTick()

    const activeChip = root.querySelector('.word-count-chip.on')
    expect(activeChip).not.toBeNull()
    expect(activeChip.textContent.trim()).toBe('3字')
  })

  it('emits update:wordCount when a word count chip is clicked', async () => {
    let captured = null
    app = createApp(KtvSoftKeyboard, {
      modelValue: '',
      wordCount: 0,
      'onUpdate:wordCount': (val) => { captured = val },
    })
    app.mount(root)
    await nextTick()

    const chips = root.querySelectorAll('.word-count-chip')
    chips[2].click() // '2字'
    await nextTick()

    expect(captured).toBe(2)
  })
})
