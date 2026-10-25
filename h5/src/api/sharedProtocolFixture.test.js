import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from './client'
import { SnapshotChunkAssembler } from './ws'
import { usePlayerStore } from '../stores/player'

const fixtures = new URL('../../../test-fixtures/ktv-protocol/v1/', import.meta.url)
const readFixture = (name) => readFileSync(fileURLToPath(new URL(name, fixtures)), 'utf8')
const jsonResponse = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: { get: () => 'application/json' },
  json: async () => body,
})

describe('shared protocol fixtures', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubGlobal('fetch', vi.fn())
  })

  it('assembles the server snapshot chunk into the same playback projection', () => {
    const wire = readFixture('snapshot_chunk.json')
    const envelope = JSON.parse(wire)
    const assembler = new SnapshotChunkAssembler()
    const assembled = assembler.accept(envelope.payload, new TextEncoder().encode(wire).length)
    const player = usePlayerStore()

    expect(envelope.type).toBe('snapshot_chunk')
    expect(assembled.eventType).toBe('sync_full')
    expect(assembled.snapshot).toMatchObject({
      state: 'playing',
      positionMs: 123456,
      seekSequence: 19,
      stateRevision: 86,
      audioLayout: {
        layout: 'DUAL_TRACK',
        originalTrackIndex: 0,
        accompanimentTrackIndex: 1,
      },
      playback: {
        kind: 'NATIVE',
        status: 'READY',
        sourceFileId: 7101,
        streamUrl: '/api/stream/7101',
      },
      list: [{ queueId: 4102, song: { id: 72 }, status: 'waiting' }],
    })

    player.handleEvent(assembled.eventType, assembled.snapshot)

    expect(player.nowPlaying).toMatchObject({ queueId: 4101, song: { id: 71, title: '后来' } })
    expect(player.positionMs).toBe(123456)
    expect(player.stateRevision).toBe(86)
  })

  it('keeps readiness and reversed dual-channel values from the REST fixture', async () => {
    const detail = JSON.parse(readFixture('song_detail_not_ready.json'))
    vi.mocked(fetch).mockResolvedValue(jsonResponse(detail))

    await expect(api.songDetail(72)).resolves.toMatchObject({
      id: 72,
      files: [{
        id: 7201,
        ready: false,
        audioLayout: {
          layout: 'DUAL_CHANNEL',
          originalChannel: 'RIGHT',
          accompanimentChannel: 'LEFT',
        },
      }],
    })
  })

  it('preserves the shared business error code, message, and HTTP status', async () => {
    const error = JSON.parse(readFixture('business_error_song_not_ready.json'))
    vi.mocked(fetch).mockResolvedValue(jsonResponse(error, 409))

    await expect(api.songDetail(72)).rejects.toMatchObject({
      code: 'SONG_NOT_READY',
      message: '歌曲尚未完成媒体探测',
      status: 409,
    })
  })
})
