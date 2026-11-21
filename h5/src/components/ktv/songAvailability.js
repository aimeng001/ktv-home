/**
 * 歌曲不可点原因（后端下发的是错误码，不是中文原因）。
 *
 * 后端 `SongAvailabilityPolicy` 只产出这两个码：
 * - `SONG_NOT_READY`：快速索引已建行但媒体还没就绪
 * - `PENDING_PROBE`：还在媒体探测
 * 直接显示原文会让用户看到「不可点：SONG_NOT_READY」。
 */
export const UNAVAILABLE_REASON_LABELS = {
  SONG_NOT_READY: '正在扫描或媒体探测未完成',
  PENDING_PROBE: '媒体信息探测中'
}

/**
 * 取不可点原因的中文文案，未知码兜底为通用说明。
 * @param {string} [reason] 后端下发的错误码 / the reason code from the backend
 * @returns {string} 中文文案 / human readable reason
 */
export function unavailableReasonLabel(reason) {
  if (!reason) return '当前不可点'
  return UNAVAILABLE_REASON_LABELS[reason] || '当前不可点'
}
