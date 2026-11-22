/**
 * 歌手头像莫兰迪调色盘与确定性哈希配色算法
 * 为无真实肖像照片的长尾歌手提供优雅、视觉统一且零磁盘开销的首字徽章。
 */

export const MORANDI_PALETTES = [
  { backgroundColor: '#dbeafe', color: '#1e40af' }, // 雾霾蓝
  { backgroundColor: '#dcfce7', color: '#166534' }, // 鼠尾草绿
  { backgroundColor: '#f3e8ff', color: '#6b21a8' }, // 淡雅紫
  { backgroundColor: '#fef3c7', color: '#92400e' }, // 暖琥珀
  { backgroundColor: '#ffe4e6', color: '#9f1239' }, // 浅桃粉
  { backgroundColor: '#e0e7ff', color: '#3730a3' }, // 浅靛青
  { backgroundColor: '#ccfbf1', color: '#115e59' }, // 灰湖绿
  { backgroundColor: '#ffedd5', color: '#9a3412' }, // 暖杏橙
  { backgroundColor: '#fae8ff', color: '#86198f' }, // 藕荷紫
  { backgroundColor: '#f1f5f9', color: '#334155' }  // 高级冷灰
]

/**
 * 经典 DJB2 确定性字符串哈希算法
 */
export function hashString(str) {
  if (!str) return 0
  let hash = 5381
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) + hash) + str.charCodeAt(i)
    hash |= 0 // 转换为 32-bit 整数
  }
  return Math.abs(hash)
}

/**
 * 根据歌手名称返回对应的莫兰迪背景色与文字色样式对象
 * @param {string} [name] 歌手名
 * @returns {{ backgroundColor: string, color: string }}
 */
export function getArtistAvatarStyle(name) {
  const safeName = (name || '').trim()
  if (!safeName) {
    return { backgroundColor: MORANDI_PALETTES[0].backgroundColor, color: MORANDI_PALETTES[0].color }
  }
  const index = hashString(safeName) % MORANDI_PALETTES.length
  return {
    backgroundColor: MORANDI_PALETTES[index].backgroundColor,
    color: MORANDI_PALETTES[index].color
  }
}
