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

export function audioLayoutLabel(value) {
  return AUDIO_LAYOUT_OPTIONS.find(item => item.value === value?.layout)?.label || '普通立体声'
}

/**
 * 布局语义的来源，只读展示用（对应后端 `AudioLayoutSource`）。
 *
 * 不放进 `createAudioLayoutDraft`：那个对象会原样 PUT 给
 * `/admin/files/{id}/audio-layout`，混入只读字段等于把来源标记当业务字段写回。
 */
export const AUDIO_LAYOUT_SOURCE_LABELS = {
  LEGACY: '历史数据（未确认）',
  AUTO_DEFAULT: '自动识别',
  MANUAL: '管理员手动设置'
}

/**
 * 取布局来源的中文文案，未知或缺失返回空串（不显示这一行）。
 * @param {string} [source] 后端下发的来源码 / provenance code
 * @returns {string} 中文文案 / readable provenance label
 */
export function audioLayoutSourceLabel(source) {
  return AUDIO_LAYOUT_SOURCE_LABELS[source] || ''
}
