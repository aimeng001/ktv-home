import { afterEach, describe, expect, it, vi } from 'vitest'
import { useToast } from './useToast'

describe('useToast', () => {
  afterEach(() => {
    useToast().message.value = ''
    vi.useRealTimers()
  })

  it('uses the default display time and then clears the message', () => {
    vi.useFakeTimers()
    const { message, toast } = useToast()

    toast('已加入队列')
    vi.advanceTimersByTime(1799)
    expect(message.value).toBe('已加入队列')

    vi.advanceTimersByTime(1)
    expect(message.value).toBe('')
  })

  it('cancels the previous timeout when a newer toast replaces it', () => {
    vi.useFakeTimers()
    const { message, toast } = useToast()

    toast('旧消息', 50)
    toast('新消息', 100)
    vi.advanceTimersByTime(50)
    expect(message.value).toBe('新消息')

    vi.advanceTimersByTime(50)
    expect(message.value).toBe('')
  })
})
