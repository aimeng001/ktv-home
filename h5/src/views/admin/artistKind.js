/**
 * 歌手目录类型的展示文案。
 *
 * 后端 `ArtistKind` 有五值（PERSON / GROUP / VARIOUS / UNATTRIBUTED / UNKNOWN），
 * 之前页面用「不是 VARIOUS 就写未归属」的二分法，导致组合被渲染成「未归属」。
 */
export const ARTIST_KIND_LABELS = {
  PERSON: '个人',
  GROUP: '组合',
  VARIOUS: '群星',
  UNATTRIBUTED: '未归属',
  UNKNOWN: '未分类'
}

/**
 * 取歌手类型的展示文案。
 * @param {string} [kind] 后端下发的类型码 / the kind code from the backend
 * @returns {string} 中文文案；空值返回空串，无法识别的码统一记为「未分类」
 */
export function artistKindLabel(kind) {
  if (!kind) return ''
  return ARTIST_KIND_LABELS[kind] || ARTIST_KIND_LABELS.UNKNOWN
}
