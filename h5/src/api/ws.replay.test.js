// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { KtvSocket } from './ws'

class FakeWebSocket {
  static OPEN = 1
  static instances = []

  constructor(url) {
    this.url = url
    this.readyState = 0
    this.sent = []
    FakeWebSocket.instances.push(this)
  }

  send(value) {
    this.sent.push(JSON.parse(value))
  }

  close() {
    this.readyState = 3
    this.onclose?.()
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.()
  }
}

describe('KtvSocket 上报重放', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('send 在连接未打开时返回 false', () => {
    const socket = new KtvSocket()
    socket.connect()

    expect(socket.send({ type: 'finished' })).toBe(false)
  })

  it('send 在连接打开时返回 true 并真的发出去', () => {
    const socket = new KtvSocket()
    socket.connect()
    const ws = FakeWebSocket.instances[0]
    ws.open()

    expect(socket.send({ type: 'finished', payload: { queue_id: 7 } })).toBe(true)
    expect(ws.sent).toEqual([{ type: 'finished', payload: { queue_id: 7 } }])
  })

  it('queueReport 按 kind + queueId 去重，不会无界增长', () => {
    const socket = new KtvSocket()

    socket.queueReport({ kind: 'finished', queueId: 7 })
    socket.queueReport({ kind: 'finished', queueId: 7 })
    socket.queueReport({ kind: 'play_error', queueId: 7 })
    for (let id = 1; id <= 40; id++) socket.queueReport({ kind: 'finished', queueId: id })

    expect(socket.pendingReportCount).toBe(20)
  })

  it('replayPending 先清空队列再重放，重放中再次失败不会重复堆积', () => {
    const socket = new KtvSocket()
    socket.queueReport({ kind: 'finished', queueId: 7 })

    const replay = vi.fn(() => { socket.queueReport({ kind: 'finished', queueId: 7 }) })
    const replayed = socket.replayPending(replay)

    expect(replayed).toBe(1)
    expect(replay).toHaveBeenCalledWith({ kind: 'finished', queueId: 7 })
    expect(socket.pendingReportCount).toBe(1)
  })

  it('replayPending 单条抛错不影响其余条目，且不把异常抛给调用方', () => {
    const socket = new KtvSocket()
    socket.queueReport({ kind: 'finished', queueId: 1 })
    socket.queueReport({ kind: 'finished', queueId: 2 })

    const replay = vi.fn((item) => { if (item.queueId === 1) throw new Error('boom') })
    expect(() => socket.replayPending(replay)).not.toThrow()
    expect(replay).toHaveBeenCalledTimes(2)
    expect(socket.pendingReportCount).toBe(0)
  })

  it('clearPendingReports 丢弃全部待重放条目', () => {
    const socket = new KtvSocket()
    socket.queueReport({ kind: 'finished', queueId: 7 })

    socket.clearPendingReports()

    expect(socket.pendingReportCount).toBe(0)
    expect(socket.replayPending(vi.fn())).toBe(0)
  })
})
