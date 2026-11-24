import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePlayerStore } from '../stores/player'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const fixturesDir = path.resolve(__dirname, '../../../contracts/wire-fixtures')

describe('cross-client wire contract fixtures', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('applies queue_snapshot_playing golden fixture faithfully', () => {
    const raw = fs.readFileSync(path.join(fixturesDir, 'queue_snapshot_playing.json'), 'utf8')
    const snapshot = JSON.parse(raw)
    const store = usePlayerStore()

    expect(store.applySnapshot(snapshot)).toBe(true)
    expect(store.state).toBe('playing')
    expect(store.stateRevision).toBe(9001)
    expect(store.nowPlaying?.song?.title).toBe('晴天')
    expect(store.playback.format).toBe('mkv')
    expect(store.playback.videoCodec).toBe('h264')
    expect(store.playback.audioCodec).toBe('aac')
  })

  it('validates playback_descriptor_variants golden fixture', () => {
    const raw = fs.readFileSync(path.join(fixturesDir, 'playback_descriptor_variants.json'), 'utf8')
    const variants = JSON.parse(raw)
    expect(variants).toHaveLength(2)
    expect(variants[1].kind).toBe('LIVE_TRANSCODE')
    expect(variants[1].format).toBe('rmvb')
    expect(variants[1].videoCodec).toBe('rv40')
  })
})
