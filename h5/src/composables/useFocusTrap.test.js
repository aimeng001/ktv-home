// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref } from 'vue'
import { useFocusTrap } from './useFocusTrap'

function mountTrap() {
  const open = ref(false)
  const host = document.createElement('div')
  document.body.appendChild(host)

  const app = createApp(defineComponent({
    setup() {
      const box = ref(null)
      useFocusTrap(box, () => open.value)
      return () => h('div', { ref: box, tabindex: '-1' }, open.value
        ? [h('button', { id: 'first' }, '一'), h('button', { id: 'last' }, '二')]
        : [])
    }
  }))
  app.mount(host)
  return { app, host, open }
}

function tab({ shiftKey = false } = {}) {
  const event = new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true, cancelable: true })
  document.dispatchEvent(event)
  return event
}

describe('useFocusTrap', () => {
  let app

  beforeEach(() => {
    document.body.innerHTML = ''
  })

  afterEach(() => {
    app?.unmount()
    app = null
    document.body.innerHTML = ''
  })

  it('打开时把焦点放到容器第一个可聚焦元素', async () => {
    const mounted = mountTrap()
    app = mounted.app
    mounted.open.value = true
    await nextTick()
    await nextTick()

    expect(document.activeElement?.id).toBe('first')
  })

  it('Tab 到最后一个时绕回第一个，Shift+Tab 反向绕回', async () => {
    const mounted = mountTrap()
    app = mounted.app
    mounted.open.value = true
    await nextTick()
    await nextTick()

    document.getElementById('last').focus()
    expect(tab().defaultPrevented).toBe(true)
    expect(document.activeElement?.id).toBe('first')

    expect(tab({ shiftKey: true }).defaultPrevented).toBe(true)
    expect(document.activeElement?.id).toBe('last')
  })

  it('焦点在容器中间时交还给浏览器默认行为', async () => {
    const mounted = mountTrap()
    app = mounted.app
    mounted.open.value = true
    await nextTick()
    await nextTick()

    document.getElementById('first').focus()
    expect(tab().defaultPrevented).toBe(false)
  })

  it('关闭后把焦点还给打开它的元素', async () => {
    const opener = document.createElement('button')
    opener.id = 'opener'
    document.body.appendChild(opener)
    opener.focus()

    const mounted = mountTrap()
    app = mounted.app
    mounted.open.value = true
    await nextTick()
    await nextTick()
    expect(document.activeElement?.id).toBe('first')

    mounted.open.value = false
    await nextTick()
    await nextTick()
    expect(document.activeElement?.id).toBe('opener')
  })

  it('上层有全局对话框时让位，不抢焦点', async () => {
    const mask = document.createElement('div')
    mask.className = 'dialog-mask'
    mask.innerHTML = '<section class="dialog-card"></section>'
    document.body.appendChild(mask)

    const mounted = mountTrap()
    app = mounted.app
    mounted.open.value = true
    await nextTick()
    await nextTick()

    document.getElementById('last').focus()
    // DialogHost 已把背景置 inert，此时若仍 preventDefault 就会「按 Tab 没反应」。
    expect(tab().defaultPrevented).toBe(false)
    expect(document.activeElement?.id).toBe('last')
  })

  it('卸载后不再拦截 Tab', async () => {
    const mounted = mountTrap()
    mounted.open.value = true
    await nextTick()
    await nextTick()

    mounted.app.unmount()
    document.getElementById('last')?.focus()

    expect(tab().defaultPrevented).toBe(false)
  })
})
