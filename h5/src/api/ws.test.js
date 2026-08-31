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
    this.sent.push(value)
  }

  close() {
    this.readyState = 3
    this.onclose?.()
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.()
  }

  receive(value) {
    this.onmessage?.({ data: value })
  }

  serverClose() {
    this.readyState = 3
    this.onclose?.()
  }
}

describe('KtvSocket', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    globalThis.WebSocket = FakeWebSocket
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('connects, forwards valid events, ignores pong and malformed messages, and sends heartbeat', () => {
    const onEvent = vi.fn()
    const onStatus = vi.fn()
    const socket = new KtvSocket({ onEvent, onStatus })

    socket.connect()
    const ws = FakeWebSocket.instances[0]
    expect(ws.url).toBe(`ws://${location.host}/ws?client_type=h5&protocol_version=2&platform=H5`)

    ws.open()
    expect(onStatus).toHaveBeenCalledWith(true)

    ws.receive('{not-json')
    ws.receive(JSON.stringify({ type: 'pong' }))
    ws.receive(JSON.stringify({ type: 'progress', payload: { position_ms: 4321 } }))
    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(onEvent).toHaveBeenCalledWith('progress', { position_ms: 4321 })

    vi.advanceTimersByTime(25_000)
    expect(ws.sent).toEqual([JSON.stringify({ type: 'ping' })])
  })

  it('reconnects after an unexpected close with backoff', () => {
    const onStatus = vi.fn()
    const socket = new KtvSocket({ onStatus })

    socket.connect()
    const first = FakeWebSocket.instances[0]
    first.serverClose()
    expect(onStatus).toHaveBeenLastCalledWith(false)
    expect(FakeWebSocket.instances).toHaveLength(1)

    vi.advanceTimersByTime(999)
    expect(FakeWebSocket.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FakeWebSocket.instances).toHaveLength(2)

    socket.close()
    vi.advanceTimersByTime(10_000)
    expect(FakeWebSocket.instances).toHaveLength(2)
  })

  it('does not schedule duplicate reconnects for repeated close notifications', () => {
    const socket = new KtvSocket()

    socket.connect()
    const first = FakeWebSocket.instances[0]
    first.serverClose()
    first.serverClose()

    vi.advanceTimersByTime(1000)
    expect(FakeWebSocket.instances).toHaveLength(2)
    vi.advanceTimersByTime(1000)
    expect(FakeWebSocket.instances).toHaveLength(2)
  })

  it('only sends while the WebSocket is open and stops reconnecting after close', () => {
    const socket = new KtvSocket()
    socket.connect()
    const ws = FakeWebSocket.instances[0]

    socket.send({ type: 'before-open' })
    expect(ws.sent).toHaveLength(0)

    ws.open()
    socket.send({ type: 'control', payload: { action: 'play' } })
    expect(ws.sent).toEqual([
      JSON.stringify({ type: 'control', payload: { action: 'play' } }),
    ])

    socket.close()
    vi.advanceTimersByTime(10_000)
    expect(FakeWebSocket.instances).toHaveLength(1)
  })
})
