// @vitest-environment jsdom

import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePlayerStore } from './player'

describe('usePlayerStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

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

  it('rejects a stale control response after a newer websocket snapshot', () => {
    const p = usePlayerStore()
    p.handleEvent('queue_updated', { ...snapshot, stateRevision: 20, list: [] })

    p.applyControlResponse({ ...snapshot, stateRevision: 19, list: snapshot.list })

    expect(p.stateRevision).toBe(20)
    expect(p.queue).toEqual([])
  })

  it('rejects legacy snapshots after the first revisioned snapshot', () => {
    const p = usePlayerStore()
    p.handleEvent('sync_full', { ...snapshot, stateRevision: 20, list: [] })

    p.applySnapshot({ ...snapshot, list: snapshot.list })

    expect(p.stateRevision).toBe(20)
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
