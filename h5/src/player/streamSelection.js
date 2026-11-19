/**
 * Browser stream selection and zero-disk-cache fallback resolution.
 * Uses native Range streaming for browser-compatible MP4/WebM/audio tracks,
 * and falls back to the backend FFmpeg pipe:1 stream for incompatible containers,
 * runtime decoder failures, or server-side DUAL_TRACK audio selection.
 */

const BROWSER_NATIVE_EXT_RE = /\.(mp4|m4v|webm|mp3|flac|m4a|wav|ogg)(?:$|\?)/i
const AUDIO_EXT_RE = /\.(mp3|flac|m4a|wav|ogg)(?:$|\?)/i
const NATIVE_FORMAT_TOKENS = new Set(['mp4', 'm4v', 'mov', 'webm', 'mp3', 'flac', 'm4a', 'aac', 'ogg', 'wav'])

export function resolveTargetAudioTrack(audioLayout, vocalMode) {
  if (!audioLayout || audioLayout.layout !== 'DUAL_TRACK') return null
  const wantsOriginal = vocalMode === 'original'
  const candidate = wantsOriginal ? audioLayout.originalTrackIndex : audioLayout.accompanimentTrackIndex
  return Number.isInteger(candidate) && candidate >= 0 ? candidate : null
}

export function isAudioOnlyPlayback(playback, song) {
  if (song?.mediaType === 'AUDIO') return true
  const pathHint = playback?.streamUrl || ''
  return AUDIO_EXT_RE.test(pathHint)
}

export function isBrowserNativeFormat(playback, song) {
  if (!playback || playback.kind === 'LIVE_TRANSCODE') return false
  const fmt = String(playback.format || song?.format || '').trim().toLowerCase()
  if (fmt) {
    const tokens = fmt.split(/[\s,;/|]+/).filter(Boolean)
    if (tokens.includes('matroska') || tokens.includes('mkv')) return false
    if (tokens.some(token => NATIVE_FORMAT_TOKENS.has(token))) return true
  }
  const url = String(playback.streamUrl || '')
  return BROWSER_NATIVE_EXT_RE.test(url)
}

/**
 * Computes the stream URL and transport behavior for the active browser `<video>`/`<audio>` element.
 */
export function buildWebStreamSpec({
  playback,
  audioLayout,
  vocalMode = 'accompaniment',
  startSeconds = 0,
  forceTranscode = false,
  song = null,
} = {}) {
  if (!playback || playback.status !== 'READY' || !playback.sourceFileId) {
    return { url: '', liveTranscode: false, trackSwitchedByServer: false, isAudioOnly: false }
  }

  const fileId = playback.sourceFileId
  const audioOnly = isAudioOnlyPlayback(playback, song)
  const targetTrack = resolveTargetAudioTrack(audioLayout, vocalMode)
  const needsLiveTranscode = Boolean(forceTranscode) || playback.kind === 'LIVE_TRANSCODE' || (!audioOnly && !isBrowserNativeFormat(playback, song))
  const defaultTrack = Number.isInteger(playback.vocalTrackIndex)
    ? playback.vocalTrackIndex
    : audioLayout?.accompanimentTrackIndex
  const trackNeedsServerSwitch = !audioOnly
    && audioLayout?.layout === 'DUAL_TRACK'
    && targetTrack !== null
    && (needsLiveTranscode || targetTrack !== defaultTrack || playback.audioTracks > 1)

  if (!needsLiveTranscode && !trackNeedsServerSwitch) {
    return {
      url: `/api/stream/${fileId}`,
      liveTranscode: false,
      trackSwitchedByServer: false,
      isAudioOnly: audioOnly,
    }
  }

  const params = new URLSearchParams()
  if (needsLiveTranscode) params.set('transcode', 'true')
  if (trackNeedsServerSwitch && targetTrack !== null) params.set('audioTrack', String(targetTrack))
  const clampedStart = Number.isFinite(startSeconds) && startSeconds > 0.5 ? startSeconds : 0
  if (clampedStart > 0) params.set('start', clampedStart.toFixed(3))

  return {
    url: `/api/stream/${fileId}?${params.toString()}`,
    liveTranscode: true,
    trackSwitchedByServer: trackNeedsServerSwitch,
    isAudioOnly: audioOnly,
  }
}
