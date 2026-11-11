// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ToastHost from './ToastHost.vue'
import { useToast } from '../composables/useToast'

describe('ToastHost', () => {
  let app
  let root

  afterEach(() => {
    app?.unmount()
    root?.remove()
    useToast().toast('', 1)
    vi.useRealTimers()
  })

  it('shows the current global toast and automatically dismisses it', async () => {
    vi.useFakeTimers()
    await mount()
    const announcer = root.querySelector('[role="status"]')
    expect(announcer).not.toBeNull()
    expect(announcer.textContent).toBe('')
    expect(announcer.getAttribute('aria-live')).toBe('polite')
    expect(announcer.getAttribute('aria-atomic')).toBe('true')

    useToast().toast('已加入队列', 250)
    await nextTick()

    const toast = root.querySelector('.proto-toast')
    expect(toast.textContent).toBe('已加入队列')
    expect(toast.getAttribute('aria-hidden')).toBe('true')
    expect(root.querySelector('[role="status"]')).toBe(announcer)
    expect(announcer.textContent).toBe('已加入队列')
    await vi.advanceTimersByTimeAsync(249)
    await nextTick()
    expect(root.querySelector('.proto-toast')).not.toBeNull()
    await vi.advanceTimersByTimeAsync(1)
    await vi.runAllTimersAsync()
    await nextTick()
    expect(root.querySelector('.proto-toast')).toBeNull()
    expect(root.querySelector('[role="status"]')).toBe(announcer)
    expect(announcer.textContent).toBe('')
  })

  it('replaces the message and resets its dismissal timer', async () => {
    vi.useFakeTimers()
    await mount()
    const { toast } = useToast()
    toast('旧消息', 100)
    await nextTick()
    await vi.advanceTimersByTimeAsync(80)
    toast('新消息', 100)
    await nextTick()
    await vi.advanceTimersByTimeAsync(80)
    await nextTick()

    expect(root.querySelector('.proto-toast').textContent).toBe('新消息')
    expect(root.querySelectorAll('[role="status"]')).toHaveLength(1)
    expect(root.querySelector('[role="status"]').textContent).toBe('新消息')
    await vi.advanceTimersByTimeAsync(20)
    await vi.runAllTimersAsync()
    await nextTick()
    expect(root.querySelector('.proto-toast')).toBeNull()
  })

  async function mount() {
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(ToastHost)
    app.mount(root)
    await nextTick()
  }
})
