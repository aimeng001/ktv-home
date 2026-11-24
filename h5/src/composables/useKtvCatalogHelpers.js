/**
 * Pure filtering and sorting helpers extracted from KtvThreeColumnBrowser.vue
 * for testability and component modularity.
 */
export const HOT_NEW_MODES = [
  { value: 'all', label: '全部热歌' },
  { value: 'ranking', label: '30天飙升榜' },
  { value: 'new_arrivals', label: '最新入库' },
]

export const SORT_OPTIONS = [
  { value: 'hot', label: '按热度' },
  { value: 'title', label: '按歌名' },
  { value: 'new', label: '最新入库' },
]

export function filterAndSortClientSongs(list, options = {}) {
  const {
    keyword = '',
    mediaFilter = '',
    vocalFormFilter = '',
    wordCount = 0,
    activeTab = '',
    songSort = 'hot',
  } = options

  const kw = String(keyword || '').trim().toLowerCase()
  const filtered = (Array.isArray(list) ? list : []).filter((song) => {
    if (mediaFilter && song.mediaType !== mediaFilter) return false
    if (vocalFormFilter && (song.vocalForm || '') !== vocalFormFilter) return false
    if (wordCount > 0) {
      const len = (song.title || '').trim().length
      if (wordCount < 6 && len !== wordCount) return false
      if (wordCount >= 6 && len < 6) return false
    }
    if (kw) {
      const hay = [
        song.title,
        song.artist,
        song.titlePinyin,
        song.titleInitials,
        song.artistPinyin,
        song.artistInitials,
        song.pinyin,
        song.pinyinAbbr,
      ].filter(Boolean).join(' ').toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })

  if (activeTab === 'favorites') {
    const copy = [...filtered]
    if (songSort === 'title') {
      copy.sort((a, b) => String(a.title || '').localeCompare(String(b.title || ''), 'zh-CN'))
    } else if (songSort === 'new') {
      copy.sort((a, b) => Number(b.id || 0) - Number(a.id || 0))
    } else if (songSort === 'hot') {
      copy.sort((a, b) => Number(b.playCount || 0) - Number(a.playCount || 0))
    }
    return copy
  }
  return filtered
}
