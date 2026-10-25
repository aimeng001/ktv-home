// @vitest-environment jsdom

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePlayerStore } from './player'

class SocketStub {
  static OPEN = 1
  static instances = []

  constructor(url) {
    this.url = url
    this.readyState = 0
    this.close = vi.fn()
    SocketStub.instances.push(this)
  }
}

describe('usePlayerStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    SocketStub.instances = []
  })

  afterEach(() => vi.unstubAllGlobals())

  const snapshot = {
    playing: { queueId: 1, song: { id: 10, title: '晴天', durationMs: 269000 }, orderedByNick: '小明' },
    list: [
      { queueId: 2, song: { id: 11, title: '七里香' }, orderedByNick: '小红', status: 'waiting' }
    ],
    state: 'playing', volume: 70, muted: false, vocalMode: 'accompaniment',
    audioLayout: {
      layout: 'DUAL_CHANNEL', originalTrackIndex: null, accompanimentTrackIndex: null,
      originalChannel: 'LEFT', accompanimentChannel: 'RIGHT'
    }
  }

  it('sync_full 应用快照', () => {
    const p = usePlayerStore()
    p.handleEvent('sync_full', snapshot)
    expect(p.state).toBe('playing')
    expect(p.volume).toBe(70)
    expect(p.nowPlaying.song.title).toBe('晴天')
    expect(p.queue).toHaveLength(1)
    expect(p.queueCount).toBe(1)
    expect(p.audioLayout.layout).toBe('DUAL_CHANNEL')
  })

  it('mirrors playback preparation status from the server snapshot', () => {
    const p = usePlayerStore()
    p.handleEvent('sync_full', {
      ...snapshot,
      playback: { kind: 'TRANSCODE', status: 'PREPARING', sourceFileId: 7, variantId: 88 }
    })

    expect(p.playback.status).toBe('PREPARING')
    expect(p.playback.variantId).toBe(88)

    p.handleEvent('player_state', { ...snapshot, playback: { kind: 'TRANSCODE', status: 'READY', streamUrl: '/api/playback/stream/88' } })
    expect(p.playback.status).toBe('READY')
  })

  it('keeps a live-transcode resolver descriptor as protocol data without changing the queue projection', () => {
    const p = usePlayerStore()
    const livePipe = {
      kind: 'LIVE_TRANSCODE',
      status: 'READY',
      sourceFileId: 7,
      variantId: null,
      streamUrl: '/api/stream/7?transcode=true',
      audioTracks: 2
    }

    p.handleEvent('sync_full', { ...snapshot, playback: livePipe })

    expect(p.playback).toEqual(livePipe)
    expect(p.nowPlaying.song.id).toBe(10)
    expect(p.positionMs).toBe(0)
    expect(p.state).toBe('playing')
  })
  it('queue_updated 刷新队列', () => {
    const p = usePlayerStore()
    p.handleEvent('sync_full', snapshot)
    p.handleEvent('queue_updated', { ...snapshot, list: [] })
    expect(p.queue).toHaveLength(0)
  })

  it('progress 更新播放位置', () => {
    const p = usePlayerStore()
    p.handleEvent('progress', { position_ms: 42000 })
    expect(p.positionMs).toBe(42000)
  })

  it('playback_seeked 使用服务端快照位置', () => {
    const p = usePlayerStore()
    p.handleEvent('playback_seeked', { ...snapshot, positionMs: 12345 })
    expect(p.positionMs).toBe(12345)
  })

  it('effect_play 记录音效', () => {
    const p = usePlayerStore()
    p.handleEvent('effect_play', { effect_id: 'clap' })
    expect(p.lastEffect).toBe('clap')
  })

  it('history_updated increments the history revision', () => {
    const p = usePlayerStore()
    expect(p.historyRevision).toBe(0)

    p.handleEvent('history_updated', { queue_id: 42 })

    expect(p.historyRevision).toBe(1)
  })

  it('connects once and safely disconnects an absent or active socket', () => {
    vi.stubGlobal('WebSocket', SocketStub)
    const p = usePlayerStore()
    p.disconnect()
    p.connect()
    const socket = p.socket
    p.connect()

    expect(SocketStub.instances).toHaveLength(1)
    expect(p.socket).toBe(socket)
    p.disconnect()
    expect(socket.closed).toBe(true)
    expect(p.socket).toBeNull()
    p.disconnect()
  })

  it('ignores progress events without a numeric position and defaults missing effect ids', () => {
    const p = usePlayerStore()
    p.positionMs = 123
    p.handleEvent('progress', null)
    p.handleEvent('progress', { position_ms: '456' })
    expect(p.positionMs).toBe(123)

    p.handleEvent('effect_play', {})
    expect(p.lastEffect).toBeNull()
    p.handleEvent('unknown-event', {})
    expect(p.positionMs).toBe(123)
  })

  it('无播放时 nowPlaying 为 null', () => {
    const p = usePlayerStore()
    p.handleEvent('sync_full', { ...snapshot, playing: null })
    expect(p.nowPlaying).toBeNull()
  })

  it('uses the server-reported TV status and keeps it unknown before the first status', () => {
    const p = usePlayerStore()
    expect(p.tvOnline).toBeNull()
    p.handleEvent('sync_full', { ...snapshot, tvOnline: false })
    expect(p.tvOnline).toBe(false)
    p.handleEvent('sync_full', { ...snapshot })
    expect(p.tvOnline).toBe(false)
  })

  it('derives ordered song ids from the current server snapshot', () => {
    const p = usePlayerStore()
    p.handleEvent('sync_full', snapshot)
    expect([...p.orderedSongIds]).toEqual([10, 11])
    p.handleEvent('queue_updated', { ...snapshot, playing: null, list: [] })
    expect([...p.orderedSongIds]).toEqual([])
  })

  it('tolerates missing queues and non-song queue entries when deriving ordered ids', () => {
    const p = usePlayerStore()
    p.queue = null
    expect([...p.orderedSongIds]).toEqual([])

    p.queue = [null, {}, { song: { id: 'not-a-number' } }, { song: { id: '12' } }]
    expect([...p.orderedSongIds]).toEqual([12])
  })

  it('keeps newer playback state when an older control response arrives after the websocket snapshot', () => {
    const p = usePlayerStore()
    const newer = { ...snapshot, state: 'paused', volume: 90, stateRevision: 20 }
    const stale = {
      ...snapshot,
      playing: null,
      list: [],
      state: 'idle',
      volume: 10,
      stateRevision: 19
    }
    p.handleEvent('queue_updated', newer)

    p.applyControlResponse(stale)

    expect(p.stateRevision).toBe(20)
    expect(p.state).toBe('paused')
    expect(p.volume).toBe(90)
    expect(p.nowPlaying.queueId).toBe(snapshot.playing.queueId)
    expect(p.queue).toEqual(snapshot.list)
  })

  it('rejects legacy snapshots after the first revisioned snapshot', () => {
    const p = usePlayerStore()
    p.handleEvent('sync_full', { ...snapshot, stateRevision: 20, list: [] })

    p.applySnapshot({ ...snapshot, list: snapshot.list })

    expect(p.stateRevision).toBe(20)
    expect(p.queue).toEqual([])
  })

  it('ignores empty and unsafe snapshots after revisioned state', () => {
    const p = usePlayerStore()
    expect(p.applySnapshot(null)).toBeUndefined()

    p.handleEvent('sync_full', {
      ...snapshot,
      stateRevision: 10,
      positionMs: 500,
      volume: 42,
      muted: true,
      vocalMode: 'original',
      tvOnline: true,
      connectedPhones: 2,
      playback: { kind: 'FILE', status: 'READY' }
    })
    const previous = {
      positionMs: p.positionMs,
      volume: p.volume,
      muted: p.muted,
      vocalMode: p.vocalMode,
      tvOnline: p.tvOnline,
      connectedPhones: p.connectedPhones,
      playback: p.playback
    }

    expect(p.applySnapshot({ ...snapshot, stateRevision: 10.5 })).toBe(false)
    expect(p.applySnapshot({ ...snapshot, stateRevision: 9 })).toBe(false)
    expect(p.applySnapshot({ ...snapshot, stateRevision: 0 })).toBe(false)
    expect(p.$state).toMatchObject(previous)
    expect(p.stateRevision).toBe(10)
  })

  it('applies compatibility defaults and preserves optional values for partial snapshots', () => {
    const p = usePlayerStore()
    p.positionMs = 321
    p.volume = 44
    p.vocalMode = 'original'
    p.audioLayout = { layout: 'DUAL_TRACK' }
    p.tvOnline = true

    expect(p.applySnapshot({})).toBe(true)

    expect(p.nowPlaying).toBeNull()
    expect(p.state).toBe('idle')
    expect(p.positionMs).toBe(321)
    expect(p.volume).toBe(44)
    expect(p.muted).toBe(false)
    expect(p.vocalMode).toBe('original')
    expect(p.audioLayout).toEqual({ layout: 'DUAL_TRACK' })
    expect(p.queue).toEqual([])
    expect(p.tvOnline).toBe(true)
    expect(p.connectedPhones).toBe(0)
    expect(p.playback).toEqual({ kind: 'NONE', status: 'IDLE' })
  })

  it('accepts wrapped control snapshots and ignores invalid response shapes', () => {
    const p = usePlayerStore()
    p.applyControlResponse({ snapshot: { ...snapshot, stateRevision: 2, list: [] } })
    expect(p.stateRevision).toBe(2)
    expect(p.queue).toEqual([])

    p.applyControlResponse({ snapshot: { list: 'not-an-array', state: 'playing' } })
    p.applyControlResponse(null)
    expect(p.state).toBe('playing')
    expect(p.queue).toEqual([])
  })

  it('ignores an out-of-order room host event', () => {
    const p = usePlayerStore()
    p.handleEvent('room_host_changed', { claimed: true, hostUserId: 9, revision: 3 })
    p.handleEvent('room_host_changed', { claimed: false, hostUserId: null, revision: 2 })
    expect(p.roomHost.claimed).toBe(true)
    expect(p.roomHost.hostUserId).toBe(9)
    expect(p.roomHost.revision).toBe(3)
  })

  it('does not let a legacy host event overwrite a revisioned state', () => {
    const p = usePlayerStore()
    p.handleEvent('room_host_changed', { claimed: true, hostUserId: 9, revision: 3 })
    p.handleEvent('room_host_changed', { claimed: false, hostUserId: null })
    expect(p.roomHost.claimed).toBe(true)
    expect(p.roomHost.hostUserId).toBe(9)
    expect(p.roomHost.revision).toBe(3)
  })

  it('ignores non-object room-host events and accepts an unrevisioned first event only at revision zero', () => {
    const p = usePlayerStore()
    p.handleEvent('room_host_changed', null)
    p.handleEvent('room_host_changed', 'invalid')
    expect(p.roomHost.revision).toBe(0)

    p.handleEvent('room_host_changed', { claimed: true, hostUserId: 7, revision: 'invalid' })
    expect(p.roomHost.claimed).toBe(true)
    expect(p.roomHost.revision).toBe(0)

    p.handleEvent('room_host_changed', { claimed: true, hostUserId: 7, revision: 1 })
    p.handleEvent('room_host_changed', { claimed: false, hostUserId: null, revision: 'invalid' })
    expect(p.roomHost.claimed).toBe(true)
    expect(p.roomHost.revision).toBe(1)
  })

  it('derives isHost locally based on userStore serverUserId', () => {
    const p = usePlayerStore()
    const { useUserStore } = require('./user')
    const u = useUserStore()
    u.serverUserId = 8

    p.handleEvent('room_host_changed', { claimed: true, hostUserId: 7, isHost: true, revision: 4 })
    expect(p.roomHost.isHost).toBe(false)

    u.serverUserId = 7
    p.handleEvent('room_host_changed', { claimed: true, hostUserId: 7, isHost: false, revision: 5 })
    expect(p.roomHost.isHost).toBe(true)
  })
})
