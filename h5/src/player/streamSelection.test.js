import { describe, expect, it } from 'vitest'
import { buildWebStreamSpec, isBrowserNativeFormat, resolveTargetAudioTrack } from './streamSelection'

describe('streamSelection', () => {
  it('returns empty spec when playback is missing or not ready', () => {
    expect(buildWebStreamSpec({ playback: null })).toEqual({
      url: '', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: false
    })
    expect(buildWebStreamSpec({ playback: { status: 'PREPARING', sourceFileId: 7 } }).url).toBe('')
  })

  it('uses native fast-path for browser-compatible MP4 single/default track and audio files', () => {
    const mp4 = {
      kind: 'NATIVE', status: 'READY', sourceFileId: 42,
      streamUrl: '/api/stream/42', audioTracks: 1, vocalTrackIndex: 0
    }
    expect(isBrowserNativeFormat(mp4, { format: 'mp4' })).toBe(true)
    expect(buildWebStreamSpec({ playback: mp4, song: { format: 'mp4' } })).toEqual({
      url: '/api/stream/42', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: false
    })

    const dualChannel = { layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' }
    expect(buildWebStreamSpec({
      playback: mp4,
      audioLayout: dualChannel,
      vocalMode: 'original',
      song: { format: 'mp4' }
    })).toEqual({
      url: '/api/stream/42', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: false
    })

    const audio = { kind: 'NATIVE', status: 'READY', sourceFileId: 99, streamUrl: '/api/stream/99', audioTracks: 1 }
    expect(buildWebStreamSpec({ playback: audio, song: { mediaType: 'AUDIO', format: 'flac' } })).toEqual({
      url: '/api/stream/99', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: true
    })
  })

  it('routes browser-incompatible containers and DUAL_TRACK selections to pipe:1 with seek start', () => {
    const mkv = {
      kind: 'NATIVE', status: 'READY', sourceFileId: 12,
      streamUrl: '/api/stream/12', audioTracks: 2, vocalTrackIndex: 1
    }
    const dualTrack = { layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 }

    const mkvSpec = buildWebStreamSpec({
      playback: mkv, audioLayout: dualTrack, vocalMode: 'original', startSeconds: 45.2, song: { format: 'mkv' }
    })
    expect(mkvSpec).toEqual({
      url: '/api/stream/12?transcode=true&audioTrack=0&start=45.200',
      liveTranscode: true,
      trackSwitchedByServer: true,
      isAudioOnly: false
    })

    const mp4DualTrack = buildWebStreamSpec({
      playback: { ...mkv, sourceFileId: 15, streamUrl: '/api/stream/15' },
      audioLayout: dualTrack,
      vocalMode: 'original',
      startSeconds: 10.5,
      song: { format: 'mp4' }
    })
    expect(mp4DualTrack).toEqual({
      url: '/api/stream/15?audioTrack=0&start=10.500',
      liveTranscode: true,
      trackSwitchedByServer: true,
      isAudioOnly: false
    })
  })

  it('forces live transcode when browser decoder fallback triggers', () => {
    const mp4 = { kind: 'NATIVE', status: 'READY', sourceFileId: 8, streamUrl: '/api/stream/8', audioTracks: 2, vocalTrackIndex: 1 }
    const dualTrack = { layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 }
    const spec = buildWebStreamSpec({
      playback: mp4, audioLayout: dualTrack, vocalMode: 'accompaniment',
      startSeconds: 22.0, forceTranscode: true, song: { format: 'mp4' }
    })
    expect(spec.url).toBe('/api/stream/8?transcode=true&audioTrack=1&start=22.000')
    expect(spec.liveTranscode).toBe(true)
  })

  it('resolves DUAL_TRACK index safely and ignores non-dual layouts', () => {
    expect(resolveTargetAudioTrack(null, 'original')).toBeNull()
    expect(resolveTargetAudioTrack({ layout: 'NORMAL_STEREO' }, 'original')).toBeNull()
    expect(resolveTargetAudioTrack({ layout: 'DUAL_TRACK', originalTrackIndex: -1, accompanimentTrackIndex: 'bad' }, 'original')).toBeNull()
    expect(resolveTargetAudioTrack({ layout: 'DUAL_TRACK', originalTrackIndex: 1, accompanimentTrackIndex: 0 }, 'original')).toBe(1)
    expect(resolveTargetAudioTrack({ layout: 'DUAL_TRACK', originalTrackIndex: 1, accompanimentTrackIndex: 0 }, 'accompaniment')).toBe(0)
  })

  it('handles missing playback metadata, LIVE_TRANSCODE hints, and URL-extension fallback safely', () => {
    expect(isBrowserNativeFormat(null)).toBe(false)
    expect(isBrowserNativeFormat({ kind: 'LIVE_TRANSCODE', streamUrl: '/api/stream/1.mp4' })).toBe(false)
    expect(isBrowserNativeFormat({ kind: 'NATIVE', streamUrl: '/api/stream/1.mp4' })).toBe(true)
    expect(isBrowserNativeFormat({ kind: 'NATIVE', streamUrl: '' }, {})).toBe(false)

    expect(buildWebStreamSpec()).toEqual({
      url: '', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: false
    })

    const singleTrackMp4 = {
      kind: 'NATIVE', status: 'READY', sourceFileId: 18,
      streamUrl: '/api/stream/18.mp4', audioTracks: 1
    }
    const dualTrack = { layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 }
    expect(buildWebStreamSpec({
      playback: singleTrackMp4,
      audioLayout: dualTrack,
      vocalMode: 'accompaniment'
    })).toEqual({
      url: '/api/stream/18', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: false
    })
  })
})
