export const AUDIO_LAYOUT_OPTIONS = [
  { value: 'NORMAL_STEREO', label: '普通立体声' },
  { value: 'DUAL_TRACK', label: '双独立音轨' },
  { value: 'DUAL_CHANNEL', label: '单音轨左右声道' }
]

export const AUDIO_CHANNEL_OPTIONS = [
  { value: 'LEFT', label: '左声道（L）' },
  { value: 'RIGHT', label: '右声道（R）' }
]

export function createAudioLayoutDraft(value = {}) {
  const layout = AUDIO_LAYOUT_OPTIONS.some(item => item.value === value.layout)
    ? value.layout : 'NORMAL_STEREO'
  return {
    layout,
    originalTrackIndex: value.originalTrackIndex ?? null,
    accompanimentTrackIndex: value.accompanimentTrackIndex ?? null,
    originalChannel: value.originalChannel || 'LEFT',
    accompanimentChannel: value.accompanimentChannel || 'RIGHT'
  }
}

export function swapAudioLayout(value) {
  const next = { ...value }
  if (value.layout === 'DUAL_CHANNEL') {
    next.originalChannel = value.accompanimentChannel
    next.accompanimentChannel = value.originalChannel
  } else if (value.layout === 'DUAL_TRACK') {
    next.originalTrackIndex = value.accompanimentTrackIndex
    next.accompanimentTrackIndex = value.originalTrackIndex
  }
  return next
}

export function audioLayoutLabel(value) {
  return AUDIO_LAYOUT_OPTIONS.find(item => item.value === value?.layout)?.label || '普通立体声'
}
