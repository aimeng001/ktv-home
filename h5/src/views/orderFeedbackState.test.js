import { describe, it, expect } from 'vitest'
import { formatOrderToast } from './orderFeedbackState'

describe('order feedback contract', () => {
  it('formats queue rank message using QueueSnapshot list field', () => {
    const snapshot = {
      playing: null,
      list: [{ queueId: 1 }, { queueId: 2 }, { queueId: 3 }],
      state: 'idle'
    }
    expect(formatOrderToast(snapshot)).toBe('已加入待播（第 3 位）')
  })

  it('formats single queued song rank message', () => {
    const snapshot = {
      playing: { queueId: 1 },
      list: [{ queueId: 2 }],
      state: 'playing'
    }
    expect(formatOrderToast(snapshot)).toBe('已加入待播（第 1 位）')
  })

  it('falls back to default message when snapshot has no list or empty list', () => {
    expect(formatOrderToast({ list: [] })).toBe('已加入队列')
    expect(formatOrderToast({})).toBe('已加入队列')
    expect(formatOrderToast(null)).toBe('已加入队列')
    expect(formatOrderToast(undefined)).toBe('已加入队列')
  })
})
