// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { KtvSocket, SnapshotChunkAssembler } from './ws'

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

function snapshotChunk(overrides = {}) {
  return {
    eventType: 'sync_full',
    syncId: 'sync-1',
    index: 0,
    total: 1,
    last: true,
    header: { state: 'idle' },
    entries: [],
    ...overrides,
  }
}

describe('SnapshotChunkAssembler', () => {
  it.each([
    ['missing chunk', null, 0],
    ['non-object chunk', 'invalid', 0],
    ['oversized message', snapshotChunk(), 1_048_577],
    ['unsupported event', snapshotChunk({ eventType: 'unknown' }), 0],
    ['non-string sync id', snapshotChunk({ syncId: 1 }), 0],
    ['empty sync id', snapshotChunk({ syncId: '' }), 0],
    ['zero chunks', snapshotChunk({ total: 0 }), 0],
    ['too many chunks', snapshotChunk({ total: 65 }), 0],
    ['fractional chunk count', snapshotChunk({ total: 1.5 }), 0],
    ['negative index', snapshotChunk({ index: -1 }), 0],
    ['index beyond count', snapshotChunk({ index: 1 }), 0],
    ['fractional index', snapshotChunk({ index: 0.5 }), 0],
    ['inconsistent last marker', snapshotChunk({ last: false }), 0],
    ['non-array entries', snapshotChunk({ entries: {} }), 0],
    ['missing header', snapshotChunk({ header: null }), 0],
  ])('rejects %s and clears partial state', (_label, value, wireBytes) => {
    const assembler = new SnapshotChunkAssembler({ maxEntries: 1 })
    assembler.accept(snapshotChunk({ total: 2, last: false }), 0)

    const result = assembler.accept(value, wireBytes)

    expect(result).toBeNull()
    expect(assembler.syncId).toBeNull()
    expect(assembler.entryCount).toBe(0)
  })

  it('enforces the per-chunk and aggregate entry limits', () => {
    const perChunk = new SnapshotChunkAssembler({ maxEntries: 1 })
    expect(perChunk.accept(snapshotChunk({ entries: [1, 2] }))).toBeNull()
    expect(perChunk.syncId).toBeNull()

    const aggregate = new SnapshotChunkAssembler({ maxEntries: 1 })
    expect(aggregate.accept(snapshotChunk({ total: 2, last: false, entries: [1] }))).toBeNull()
    expect(aggregate.accept(snapshotChunk({ index: 1, total: 2, last: true, entries: [2] }))).toBeNull()
    expect(aggregate.syncId).toBeNull()
  })

  it('replaces partial state for a new sync and rejects inconsistent headers', () => {
    const assembler = new SnapshotChunkAssembler()
    assembler.accept(snapshotChunk({ total: 2, last: false, entries: [1] }))

    expect(assembler.accept(snapshotChunk({ syncId: 'sync-2', total: 3, last: false }))).toBeNull()
    expect(assembler.syncId).toBe('sync-2')
    expect(assembler.accept(snapshotChunk({ syncId: 'sync-2', index: 1, total: 3, last: false }))).toBeNull()
    expect(assembler.accept(snapshotChunk({ syncId: 'sync-2', index: 2, total: 3, last: true, header: { state: 'playing' } }))).toBeNull()
    expect(assembler.syncId).toBeNull()
  })

  it.each([
    ['event type', { eventType: 'queue_updated' }],
    ['chunk count', { total: 3, last: false }],
    ['header', { header: { state: 'playing' } }],
  ])('rejects a changed %s within one snapshot', (_label, changes) => {
    const assembler = new SnapshotChunkAssembler()
    assembler.accept(snapshotChunk({ total: 2, last: false }))

    expect(assembler.accept(snapshotChunk({ index: 1, last: true, ...changes }))).toBeNull()
    expect(assembler.syncId).toBeNull()
  })

  it('rejects a repeated chunk and assembles a single chunk before resetting for reuse', () => {
    const assembler = new SnapshotChunkAssembler()
    expect(assembler.accept(snapshotChunk({ total: 2, last: false }))).toBeNull()
    expect(assembler.accept(snapshotChunk({ total: 2, last: false }))).toBeNull()
    expect(assembler.syncId).toBeNull()

    expect(assembler.accept(snapshotChunk({ entries: [{ queueId: 1 }] }))).toEqual({
      eventType: 'sync_full',
      snapshot: { state: 'idle', list: [{ queueId: 1 }] },
    })
    expect(assembler.syncId).toBeNull()
    expect(assembler.accept(snapshotChunk())).toEqual({
      eventType: 'sync_full',
      snapshot: { state: 'idle', list: [] },
    })
  })

  it('fails closed if internal chunk state has a missing index at completion', () => {
    const assembler = new SnapshotChunkAssembler()
    assembler.accept(snapshotChunk({ total: 2, last: false }))
    assembler.chunks.delete(0)
    assembler.chunks.set(99, [])

    expect(assembler.accept(snapshotChunk({ index: 1, last: true, total: 2 }))).toBeNull()
    expect(assembler.syncId).toBeNull()
  })
})

describe('KtvSocket', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    globalThis.WebSocket = FakeWebSocket
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
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

  it('counts UTF-8 bytes for two-, three-, and four-byte characters', () => {
    const onEvent = vi.fn()
    const socket = new KtvSocket({ onEvent })
    socket.connect()
    const ws = FakeWebSocket.instances[0]

    ws.receive(JSON.stringify({ type: 'unicode', payload: 'é 中文 😀' }))

    expect(onEvent).toHaveBeenCalledWith('unicode', 'é 中文 😀')
    expect(ws.readyState).toBe(0)
  })

  it('invokes the default no-op callbacks when callers omit handlers', () => {
    const socket = new KtvSocket()
    socket.connect()
    const ws = FakeWebSocket.instances[0]

    ws.open()
    ws.receive(JSON.stringify({ type: 'event-without-listener', payload: {} }))

    expect(ws.readyState).toBe(FakeWebSocket.OPEN)
    socket.close()
  })

  it('closes a socket when a message exceeds the wire-byte limit', () => {
    const socket = new KtvSocket()
    socket.connect()
    const ws = FakeWebSocket.instances[0]

    ws.receive('x'.repeat(1_048_577))

    expect(ws.readyState).toBe(3)
    expect(socket.reconnectTimer).not.toBeNull()
    socket.close()
  })

  it('selects a secure WebSocket URL on HTTPS pages', () => {
    vi.stubGlobal('location', { protocol: 'https:', host: 'secure.example' })
    const socket = new KtvSocket()

    socket.connect()

    expect(FakeWebSocket.instances[0].url).toBe('wss://secure.example/ws?client_type=h5&protocol_version=2&platform=H5')
    socket.close()
    vi.unstubAllGlobals()
  })

  it('ignores callbacks from a superseded socket generation', () => {
    const onEvent = vi.fn()
    const onStatus = vi.fn()
    const socket = new KtvSocket({ onEvent, onStatus })
    socket.connect()
    const oldSocket = FakeWebSocket.instances[0]
    socket.connect()
    const currentSocket = FakeWebSocket.instances[1]

    oldSocket.open()
    oldSocket.receive(JSON.stringify({ type: 'stale', payload: 1 }))
    oldSocket.serverClose()
    expect(onStatus).not.toHaveBeenCalled()
    expect(onEvent).not.toHaveBeenCalled()

    currentSocket.open()
    currentSocket.receive(JSON.stringify({ type: 'current', payload: 2 }))
    expect(onStatus).toHaveBeenCalledWith(true)
    expect(onEvent).toHaveBeenCalledWith('current', 2)
    socket.close()
  })

  it('resets a partial snapshot when a full snapshot event arrives', () => {
    const onEvent = vi.fn()
    const socket = new KtvSocket({ onEvent })
    socket.connect()
    const ws = FakeWebSocket.instances[0]
    ws.receive(JSON.stringify({
      type: 'snapshot_chunk',
      payload: snapshotChunk({ total: 2, last: false, entries: [{ queueId: 1 }] }),
    }))
    ws.receive(JSON.stringify({ type: 'queue_updated', payload: { list: [] } }))
    ws.receive(JSON.stringify({
      type: 'snapshot_chunk',
      payload: snapshotChunk({ index: 1, total: 2, last: true, entries: [{ queueId: 2 }] }),
    }))

    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(onEvent).toHaveBeenCalledWith('queue_updated', { list: [] })
    socket.close()
  })

  it('increases reconnect delay to the capped interval and cancels a pending retry on close', () => {
    const socket = new KtvSocket()
    socket.connect()

    const delays = [1000, 2000, 5000, 10000, 10000]
    for (const [index, delay] of delays.entries()) {
      FakeWebSocket.instances.at(-1).serverClose()
      vi.advanceTimersByTime(delay - 1)
      expect(FakeWebSocket.instances).toHaveLength(index + 1)
      vi.advanceTimersByTime(1)
      expect(FakeWebSocket.instances).toHaveLength(index + 2)
    }

    FakeWebSocket.instances.at(-1).serverClose()
    socket.close()
    vi.advanceTimersByTime(20_000)
    expect(FakeWebSocket.instances).toHaveLength(delays.length + 1)
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

  it('applies a snapshot chunk only after all chunks arrive', () => {
    const onEvent = vi.fn()
    const socket = new KtvSocket({ onEvent })
    socket.connect()
    const ws = FakeWebSocket.instances[0]
    ws.open()
    const header = {
      playing: null,
      state: 'idle',
      volume: 60,
      muted: false,
      vocalMode: 'accompaniment',
      audioLayout: { layout: 'NORMAL_STEREO' },
      tvOnline: false,
      connectedPhones: 0,
      positionMs: 0,
      seekSequence: 0,
    }

    ws.receive(JSON.stringify({
      type: 'snapshot_chunk',
      payload: {
        eventType: 'sync_full', syncId: 'sync-1', index: 1, total: 2, last: true,
        header, entries: [{ queueId: 2, song: null, orderedBy: null, orderedByNick: null, status: 'waiting' }],
      },
    }))
    expect(onEvent).not.toHaveBeenCalled()

    ws.receive(JSON.stringify({
      type: 'snapshot_chunk',
      payload: {
        eventType: 'sync_full', syncId: 'sync-1', index: 0, total: 2, last: false,
        header, entries: [{ queueId: 1, song: null, orderedBy: null, orderedByNick: null, status: 'waiting' }],
      },
    }))

    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(onEvent).toHaveBeenCalledWith('sync_full', expect.objectContaining({
      list: expect.arrayContaining([
        expect.objectContaining({ queueId: 1 }),
        expect.objectContaining({ queueId: 2 }),
      ]),
    }))
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
