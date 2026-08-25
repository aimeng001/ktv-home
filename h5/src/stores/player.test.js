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

  it('effect_play 记录音效', () => {
    const p = usePlayerStore()
    p.handleEvent('effect_play', { effect_id: 'clap' })
    expect(p.lastEffect).toBe('clap')
  })

  it('无播放时 nowPlaying 为 null', () => {
    const p = usePlayerStore()
    p.handleEvent('sync_full', { ...snapshot, playing: null })
    expect(p.nowPlaying).toBeNull()
  })
})
