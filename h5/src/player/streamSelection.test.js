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

  it('routes browser-incompatible containers and DUAL_TRACK selections to pipe:1 with mergeTracks', () => {
    const mkv = {
      kind: 'NATIVE', status: 'READY', sourceFileId: 12,
      streamUrl: '/api/stream/12', audioTracks: 2, vocalTrackIndex: 1
    }
    const dualTrack = { layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 }

    const mkvSpecOrig = buildWebStreamSpec({
      playback: mkv, audioLayout: dualTrack, vocalMode: 'original', startSeconds: 45.2, song: { format: 'mkv' }
    })
    const mkvSpecAcc = buildWebStreamSpec({
      playback: mkv, audioLayout: dualTrack, vocalMode: 'accompaniment', startSeconds: 45.2, song: { format: 'mkv' }
    })
    expect(mkvSpecOrig.url).toBe('/api/stream/12?transcode=true&mergeTracks=true&start=45.200')
    expect(mkvSpecAcc.url).toBe(mkvSpecOrig.url)
    expect(mkvSpecOrig.trackSwitchedByServer).toBe(true)

    const mp4DualTrack = buildWebStreamSpec({
      playback: { ...mkv, sourceFileId: 15, streamUrl: '/api/stream/15' },
      audioLayout: dualTrack,
      vocalMode: 'original',
      startSeconds: 10.5,
      song: { format: 'mp4' }
    })
    expect(mp4DualTrack.url).toBe('/api/stream/15?transcode=true&mergeTracks=true&start=10.500')
    expect(mp4DualTrack.liveTranscode).toBe(true)
  })

  it('forces live transcode when browser decoder fallback triggers', () => {
    const mp4 = { kind: 'NATIVE', status: 'READY', sourceFileId: 8, streamUrl: '/api/stream/8', audioTracks: 2, vocalTrackIndex: 1 }
    const dualTrack = { layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 }
    const spec = buildWebStreamSpec({
      playback: mp4, audioLayout: dualTrack, vocalMode: 'accompaniment',
      startSeconds: 22.0, forceTranscode: true, song: { format: 'mp4' }
    })
    expect(spec.url).toBe('/api/stream/8?transcode=true&mergeTracks=true&start=22.000')
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

  it('uses playback.format for extensionless /api/stream/{id} URLs and keeps matroska on live transcode', () => {
    const serverNativeMp4 = {
      kind: 'NATIVE', status: 'READY', sourceFileId: 71,
      streamUrl: '/api/stream/71', audioTracks: 1, format: 'mp4'
    }
    expect(buildWebStreamSpec({ playback: serverNativeMp4, song: { mediaType: 'KTV_VIDEO' } })).toEqual({
      url: '/api/stream/71', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: false
    })

    const matroskaWithWebmAlias = {
      kind: 'NATIVE', status: 'READY', sourceFileId: 72,
      streamUrl: '/api/stream/72', audioTracks: 1, format: 'matroska,webm'
    }
    expect(buildWebStreamSpec({ playback: matroskaWithWebmAlias, song: { mediaType: 'MV' } })).toEqual({
      url: '/api/stream/72?transcode=true', liveTranscode: true, trackSwitchedByServer: false, isAudioOnly: false
    })
  })

  it('appends softVideo=true when softVideo fallback is requested', () => {
    const mp4 = { kind: 'NATIVE', status: 'READY', sourceFileId: 88, streamUrl: '/api/stream/88', audioTracks: 1 }
    const spec = buildWebStreamSpec({
      playback: mp4,
      softVideo: true,
      song: { format: 'mp4' }
    })
    expect(spec.url).toBe('/api/stream/88?transcode=true&softVideo=true')
    expect(spec.liveTranscode).toBe(true)
  })

  it('forces live transcode for MP4 containing AC3/DTS/MP2 audio or HEVC video', () => {
    const mp4Ac3 = {
      kind: 'NATIVE', status: 'READY', sourceFileId: 91,
      streamUrl: '/api/stream/91', audioTracks: 1, format: 'mp4',
      videoCodec: 'h264', audioCodec: 'ac3'
    }
    const specAc3 = buildWebStreamSpec({ playback: mp4Ac3, song: { format: 'mp4' } })
    expect(specAc3.liveTranscode).toBe(true)
    expect(specAc3.url).toBe('/api/stream/91?transcode=true')

    const mp4Hevc = {
      kind: 'NATIVE', status: 'READY', sourceFileId: 92,
      streamUrl: '/api/stream/92', audioTracks: 1, format: 'mp4',
      videoCodec: 'hevc', audioCodec: 'aac'
    }
    expect(buildWebStreamSpec({ playback: mp4Hevc, song: { format: 'mp4' } }).liveTranscode).toBe(true)
  })
})
