import bgDashboard from '../../assets/ktv/ktv_dashboard_background_iktv50.png'
import tileHotNew from '../../assets/ktv/ktv_ref_v50_tile_hot_new.png'
import tileTiktok from '../../assets/ktv/ktv_ref_v50_tile_tiktok.png'
import tileSinger1 from '../../assets/ktv/ktv_ref_v50_tile_singers_01.png'
import tileSinger2 from '../../assets/ktv/ktv_ref_v50_tile_singers_02.png'
import tileSinger3 from '../../assets/ktv/ktv_ref_v50_tile_singers_03.png'
import tileSong1 from '../../assets/ktv/ktv_ref_v50_tile_songs_01.png'
import tileSong2 from '../../assets/ktv/ktv_ref_v50_tile_songs_02.png'
import tileSong3 from '../../assets/ktv/ktv_ref_v50_tile_songs_03.png'
import tileVariety from '../../assets/ktv/ktv_ref_v50_tile_variety.png'
import tileLanguage from '../../assets/ktv/ktv_ref_v50_tile_language.png'
import tileTopic from '../../assets/ktv/ktv_ref_v50_tile_topic.png'
import tileFeatured from '../../assets/ktv/ktv_ref_v50_tile_featured.png'
import tileFavorite from '../../assets/ktv/ktv_ref_v50_tile_favorite.png'
import tileLocal from '../../assets/ktv/ktv_ref_v50_tile_local.png'

import varietyWsgs from '../../assets/ktv/ktv_ref_v50_variety_wsgs.png'
import varietyZghsy from '../../assets/ktv/ktv_ref_v50_variety_zghsy.png'
import varietyWwgk from '../../assets/ktv/ktv_ref_v50_variety_wwgk.png'
import varietyZghgq from '../../assets/ktv/ktv_ref_v50_variety_zghgq.png'
import varietyZgmzs from '../../assets/ktv/ktv_ref_v50_variety_zgmzs.png'
import varietyZmhs from '../../assets/ktv/ktv_ref_v50_variety_zmhs.png'
import varietyZgzqy from '../../assets/ktv/ktv_ref_v50_variety_zgzqy.png'
import varietyQnxz from '../../assets/ktv/ktv_ref_v50_variety_qnxz.png'

import iconMobileQr from '../../assets/ktv/ic_header_mobile_qr.png'
import iconSettings from '../../assets/ktv/ic_header_settings.png'
import iconSearch from '../../assets/ktv/ktv_ref_v50_settings_search.png'
import iconLibraryScan from '../../assets/ktv/ic_header_library_scan.png'
import iconNext from '../../assets/ktv/ic_player_next.png'
import iconReplay from '../../assets/ktv/ic_player_replay.png'
import iconVolDown from '../../assets/ktv/ic_volume_down.png'
import iconVolUp from '../../assets/ktv/ic_volume_up.png'
import iconToneDown from '../../assets/ktv/ktv_ref_v50_dock_tone_down.png'
import iconToneUp from '../../assets/ktv/ktv_ref_v50_dock_tone_up.png'
import iconDownload from '../../assets/ktv/ktv_ref_v50_dock_download.png'
import shadowLeft from '../../assets/ktv/bg_ktv_dock_shadow_left.png'
import shadowRight from '../../assets/ktv/bg_ktv_dock_shadow_right.png'
import standbyBanner from '../../assets/ktv/home_ktv_tv_banner.png'

export const KTV_ASSETS = {
  bgDashboard,
  iconMobileQr,
  iconSettings,
  iconSearch,
  iconLibraryScan,
  iconNext,
  iconReplay,
  iconVolDown,
  iconVolUp,
  iconToneDown,
  iconToneUp,
  iconDownload,
  shadowLeft,
  shadowRight,
  standbyBanner,
}

const SINGER_PHOTO_CAROUSEL = [tileSinger1, tileSinger2, tileSinger3]
const SONG_PHOTO_CAROUSEL = [tileSong1, tileSong2, tileSong3]

/**
 * 10 tiles matching Android TV `view_ktv_bento_dashboard.xml` and `KtvDashboardTile`.
 */
export const BENTO_TILES = [
  { id: 'hot_ranking', title: '热歌新歌', tab: 'hot_new', artwork: tileHotNew, area: 'hot', align: 'center' },
  { id: 'tiktok', title: '抖音神曲', tab: 'tiktok', artwork: tileTiktok, area: 'tiktok', align: 'center' },
  { id: 'singer', title: '歌手点歌', tab: 'singer', artwork: tileSinger1, carousel: SINGER_PHOTO_CAROUSEL, area: 'singer', align: 'start' },
  { id: 'song_name', title: '歌名点歌', tab: 'song_name', artwork: tileSong1, carousel: SONG_PHOTO_CAROUSEL, area: 'songname', align: 'start' },
  { id: 'opera', title: '综艺点歌', tab: 'opera', artwork: tileVariety, area: 'opera', align: 'end' },
  { id: 'language', title: '语种点歌', tab: 'language', artwork: tileLanguage, area: 'language', align: 'end' },
  { id: 'theme', title: '主题点歌', tab: 'theme', artwork: tileTopic, area: 'theme', align: 'end' },
  { id: 'featured', title: '精选专区', tab: 'featured', artwork: tileFeatured, area: 'featured', align: 'start' },
  { id: 'favorites', title: '我的收藏', tab: 'favorites', artwork: tileFavorite, area: 'favorites', align: 'center' },
  { id: 'local', title: '本地媒体', tab: 'local', artwork: tileLocal, area: 'local', align: 'center' },
]

export const CAPSULE_TABS = [
  { key: 'song_name', label: '歌名点歌' },
  { key: 'hd', label: '高清专区' },
  { key: 'singer', label: '歌手点歌' },
  { key: 'hot_new', label: '热歌新歌' },
  { key: 'featured', label: '精选专区' },
  { key: 'local', label: '本地媒体' },
  { key: 'favorites', label: '我的收藏' },
  { key: 'language', label: '语种点歌' },
  { key: 'theme', label: '主题点歌' },
  { key: 'opera', label: '综艺点歌' },
  { key: 'tiktok', label: '抖音神曲' },
  { key: 'recent', label: '最近唱' },
]

export const HIGH_DEFINITION_TAG = '高清HD歌曲'
export const TIKTOK_TAG = '抖音神曲'

export const VARIETY_PRESETS = [
  { key: '我是歌手', label: '我是歌手', image: varietyWsgs },
  { key: '中国好声音', label: '中国好声音', image: varietyZghsy },
  { key: '我为歌狂', label: '我为歌狂', image: varietyWwgk },
  { key: '中国好歌曲', label: '中国好歌曲', image: varietyZghgq },
  { key: '中国梦之声', label: '中国梦之声', image: varietyZgmzs },
  { key: '最美和声', label: '最美和声', image: varietyZmhs },
  { key: '中国最强音', label: '中国最强音', image: varietyZgzqy },
  { key: '全能星战', label: '全能星战', image: varietyQnxz },
]

export const T9_DIGIT_MAP = {
  2: 'ABC',
  3: 'DEF',
  4: 'GHI',
  5: 'JKL',
  6: 'MNO',
  7: 'PQRS',
  8: 'TUV',
  9: 'WXYZ',
}

export const T9_KEYS = [
  { digit: '1', letters: '1', label: '1' },
  { digit: '2', letters: 'ABC', label: '2' },
  { digit: '3', letters: 'DEF', label: '3' },
  { digit: '4', letters: 'GHI', label: '4' },
  { digit: '5', letters: 'JKL', label: '5' },
  { digit: '6', letters: 'MNO', label: '6' },
  { digit: '7', letters: 'PQRS', label: '7' },
  { digit: '8', letters: 'TUV', label: '8' },
  { digit: '9', letters: 'WXYZ', label: '9' },
  { digit: 'clear', letters: '清空', label: '清空', action: 'clear' },
  { digit: '0', letters: '0', label: '0' },
  { digit: 'back', letters: '退格', label: '⌫', action: 'backspace' },
]

export const QWERTY_ROWS = [
  ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
  ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'],
  ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'],
  ['Z', 'X', 'C', 'V', 'B', 'N', 'M'],
]

export const GENDER_FILTERS = [
  { value: '', label: '全部歌手' },
  { value: '男歌手', label: '男歌手' },
  { value: '女歌手', label: '女歌手' },
  { value: '组合', label: '组合' },
]

export const REGION_FILTERS = [
  { value: '', label: '全部地区' },
  { value: '内地', label: '内地' },
  { value: '港台', label: '港台' },
  { value: '国外', label: '国外' },
]

export const VOCAL_FORM_FILTERS = [
  { value: '', label: '全部唱法' },
  { value: '对唱', label: '对唱' },
]

export const MEDIA_FILTERS = [
  { value: '', label: '全部' },
  { value: 'KTV_VIDEO', label: 'KTV 双音轨' },
  { value: 'MV', label: 'MV' },
  { value: 'AUDIO', label: '纯音频' },
]

export function formatDurationMs(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return '--:--'
  const totalSec = Math.floor(ms / 1000)
  const min = Math.floor(totalSec / 60)
  const sec = totalSec % 60
  return `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}

export function formatTimeClock(iso) {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } catch {
    return ''
  }
}

export function formatSongTypeBadge(song) {
  const mediaType = song?.mediaType
  if (mediaType === 'KTV_VIDEO') return { text: 'KTV', cls: 'tag-ktv' }
  if (mediaType === 'MV') return { text: 'MV', cls: 'tag-mv' }
  if (mediaType === 'AUDIO') return { text: '音频', cls: 'tag-audio' }
  return song?.hasVocalTrack ? { text: 'KTV', cls: 'tag-ktv' } : { text: 'MV', cls: 'tag-mv' }
}
