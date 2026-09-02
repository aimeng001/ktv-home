/**
 * REST API 客户端封装模块。
 * 基址为 /api，开发时由 Vite 代理到 :8080，详设§11。
 *
 * REST API client wrapper module.
 * Base URL is /api, proxied to :8080 by Vite during development. See §11.
 *
 * @module api/client
 */
import { clearAdminToken, getAdminToken } from '../stores/adminAuth'

const BASE = '/api'

/**
 * 通用 HTTP 请求封装，自动处理 JSON/FormData、错误和 204 响应。
 *
 * General HTTP request wrapper. Auto-handles JSON/FormData, errors, and 204 responses.
 *
 * @param {string} path - 请求路径（相对 BASE）/ request path (relative to BASE)
 * @param {object} [options] - fetch 配置项（如 method、body、headers）/ fetch options
 * @returns {Promise<object|string|null>} 解析后的响应体 / parsed response body
 */
async function request(path, options = {}) {
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData
  const { headers: optionHeaders, ...fetchOptions } = options
  const headers = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...(optionHeaders || {})
  }
  if (path === '/admin' || path.startsWith('/admin/')) {
    const token = getAdminToken()
    if (token) headers['X-Admin-Token'] = token
  }
  const res = await fetch(BASE + path, {
    ...fetchOptions,
    headers
  })
  if (!res.ok) {
    let body = null
    try { body = await res.json() } catch { /* ignore */ }
    if (res.status === 401 && body?.code === 'ADMIN_AUTH_REQUIRED') {
      clearAdminToken()
      window.dispatchEvent(new CustomEvent('home-ktv-admin-auth-required'))
    }
    const err = new Error(body?.message || `HTTP ${res.status}`)
    err.code = body?.code
    err.status = res.status
    throw err
  }
  if (res.status === 204) return null
  const ct = res.headers.get('content-type') || ''
  return ct.includes('application/json') ? res.json() : res.text()
}

export const api = {
  health: () => request('/health'),
  releaseInfo: () => request('/release'),

  // 搜索/曲库（P1.6/P1.7）
  // Search / song library (P1.6/P1.7)
  searchSongs: (keyword, type = '', page = 0) =>
    request(`/songs?keyword=${encodeURIComponent(keyword)}&type=${type}&page=${page}`),
  songDetail: (id) => request(`/songs/${id}`),
  lyricText: (id) => request(`/lyric/${id}`),

  // 队列/控制（P1.9~P1.12）
  // Queue / control (P1.9~P1.12)
  getQueue: () => request('/queue'),
  control: (action, params = {}, clientToken) =>
    request('/control', {
      method: 'POST',
      body: JSON.stringify({ action, params, client_token: clientToken })
    }),

  // 发现/历史/心愿（P3）
  // Discovery / history / wishes (P3)
  ranking: (days = 30) => request(`/ranking?days=${days}`),
  newSongs: () => request('/songs/new'),
  history: () => request('/history'),
  recentHistory: (clientToken, mine = false) => request(`/history/recent?clientToken=${encodeURIComponent(clientToken || '')}&mine=${mine}`),
  repeatHistory: (historyId, clientToken, force = false) => request(`/history/${historyId}/repeat`, { method: 'POST', body: JSON.stringify({ clientToken, force }) }),
  favorites: (clientToken) => request(`/favorites?clientToken=${encodeURIComponent(clientToken || '')}`),
  favoriteIds: (clientToken) => request(`/favorites/ids?clientToken=${encodeURIComponent(clientToken || '')}`),
  addFavorite: (songId, clientToken) => request(`/favorites/${songId}`, { method: 'POST', body: JSON.stringify({ clientToken }) }),
  removeFavorite: (songId, clientToken) => request(`/favorites/${songId}?clientToken=${encodeURIComponent(clientToken || '')}`, { method: 'DELETE' }),
  playlists: () => request('/playlists'),
  playlistDetail: (id) => request(`/playlists/${id}`),
  addSongToPlaylist: (id, songId) => request(`/playlists/${id}/songs`, { method: 'POST', body: JSON.stringify({ songId }) }),
  orderPlaylist: (id, clientToken) => request(`/playlists/${id}/order`, { method: 'POST', body: JSON.stringify({ clientToken }) }),
  browseArtists: () => request('/browse/artists'),
  browseArtistPage: (params = {}) => request('/browse/artists/page?' + new URLSearchParams(
    Object.entries(params).filter(([, value]) => value !== '' && value != null)
  ).toString()),
  browseArtistInitials: (gender = '') => request('/browse/artists/initials?' + new URLSearchParams(
    gender ? { gender } : {}
  ).toString()),
  browseLanguages: () => request('/browse/languages'),
  browseTags: () => request('/browse/tags'),
  browseSongs: (params = {}) => request('/browse/songs?' + new URLSearchParams(Object.entries(params).filter(([, value]) => value !== '' && value != null)).toString()),
  browseSongPage: (params = {}) => request('/browse/songs/page?' + new URLSearchParams(Object.entries(params).filter(([, value]) => value !== '' && value != null)).toString()),
  addWish: (keyword, clientToken) =>
    request('/wishes', { method: 'POST', body: JSON.stringify({ keyword, client_token: clientToken }) }),
  registerUser: (clientToken, nickname) =>
    request('/user', { method: 'POST', body: JSON.stringify({ client_token: clientToken, nickname }) }),
  roomHostStatus: (clientToken) => request(`/room/host?clientToken=${encodeURIComponent(clientToken || '')}`),
  claimRoomHost: (clientToken) => request('/room/host/claim', { method: 'POST', body: JSON.stringify({ clientToken }) }),
  releaseRoomHost: (clientToken) => request('/room/host/release', { method: 'POST', body: JSON.stringify({ clientToken }) }),

  // 管理后台（P2）
  // Admin dashboard (P2)
  adminAuthStatus: () => request('/admin/auth/status'),
  adminLogin: (password) => request('/admin/auth/login', { method: 'POST', body: JSON.stringify({ password }) }),
  adminLogout: () => request('/admin/auth/logout', { method: 'POST' }),
  adminStatus: () => request('/admin/status'),
  adminSongs: (params = {}) => request('/admin/songs?' + new URLSearchParams(
    Object.entries(params).filter(([, value]) => value !== '' && value != null)
  ).toString()),
  adminSong: (id) => request(`/admin/songs/${id}`),
  adminEditSong: (id, body) => request(`/admin/songs/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  adminUpdateAudioLayout: (fileId, body) => request(`/admin/files/${fileId}/audio-layout`, { method: 'PUT', body: JSON.stringify(body) }),
  adminSwapAudioLayout: (fileId) => request(`/admin/files/${fileId}/audio-layout/swap`, { method: 'POST' }),
  adminDeleteSong: (id) => request(`/admin/songs/${id}`, { method: 'DELETE' }),
  adminDeleteSongs: (ids) => request('/admin/songs', { method: 'DELETE', body: JSON.stringify({ ids }) }),
  adminMergeSong: (keepSongId, sourceSongId) => request(`/admin/songs/${keepSongId}/merge`, { method: 'POST', body: JSON.stringify({ sourceSongId }) }),
  adminTranscodeSong: (id) => request(`/admin/songs/${id}/transcode`, { method: 'POST' }),
  adminImports: (action = '', page = 0, size = 20) => request(`/admin/imports?action=${encodeURIComponent(action)}&page=${page}&size=${size}`),
  adminDeleteImportSource: (id) => request(`/admin/imports/${id}/source`, { method: 'DELETE' }),
  adminSourceLibrary: (params = {}) => request('/admin/source-library?' + new URLSearchParams(
    Object.entries(params).filter(([, value]) => value !== '' && value != null)
  ).toString()),
  adminStartSourceTranscode: (ids = [], all = false) => request('/admin/source-library/transcode', { method: 'POST', body: JSON.stringify({ ids, all }) }),
  adminPrioritizeSourceTranscode: (id) => request('/admin/source-library/transcode/priority', { method: 'POST', body: JSON.stringify({ id }) }),
  adminSourceTranscodeProgress: () => request('/admin/source-library/progress'),
  adminCleanupImportedSources: () => request('/admin/source-library/cleanup', { method: 'POST' }),
  adminDeleteSources: (ids = [], filters = {}) => request('/admin/source-library', {
    method: 'DELETE', body: JSON.stringify({ ids, ...filters })
  }),
  adminPreviewReparse: (songIds, rule) => request('/admin/songs/reparse/preview', { method: 'POST', body: JSON.stringify({ songIds, rule }) }),
  adminApplyReparse: (songIds, rule) => request('/admin/songs/reparse/apply', { method: 'POST', body: JSON.stringify({ songIds, rule }) }),
  adminScan: () => request('/admin/scan', { method: 'POST' }),
  adminStartScan: () => request('/admin/scan/start', { method: 'POST' }),
  adminScanProgress: () => request('/admin/scan/progress'),
  adminGetSettings: () => request('/admin/settings'),
  adminTranscodeHardware: () => request('/admin/settings/transcode-hardware'),
  adminPutSettings: (body) => request('/admin/settings', { method: 'PUT', body: JSON.stringify(body) }),
  adminResetTranscodeDefaults: () => request('/admin/settings/transcode-defaults', { method: 'POST' }),
  adminMusicSourceConfig: () => request('/admin/music-sources/config'),
  adminPutMusicSourceConfig: (body) => request('/admin/music-sources/config', { method: 'PUT', body: JSON.stringify(body) }),
  adminTestMusicSources: (providers = []) => request('/admin/music-sources/test', { method: 'POST', body: JSON.stringify({ providers }) }),
  adminSongExternalMatches: (songId, refresh = false, keyword = '', providers = []) => request(`/admin/songs/${songId}/external-matches?` + new URLSearchParams([
    ['refresh', String(refresh)],
    ...(keyword ? [['keyword', keyword]] : []),
    ...providers.map(provider => ['providers', provider])
  ]).toString()),
  adminBatchSongExternalMatches: (songIds) => request('/admin/songs/external-matches/batch', { method: 'POST', body: JSON.stringify({ songIds }) }),
  adminApplyExternalMatch: (songId, provider, externalId, fields, overrides = {}) => request(`/admin/songs/${songId}/external-matches/${encodeURIComponent(provider)}/${encodeURIComponent(externalId)}/apply`, { method: 'POST', body: JSON.stringify({ fields, overrides }) }),
  adminApplyManualMetadata: (songId, fields, overrides) => request(`/admin/songs/${songId}/metadata/manual`, { method: 'POST', body: JSON.stringify({ fields, overrides }) }),
  adminStartMetadataScrape: (body) => request('/admin/metadata-scrapes', { method: 'POST', body: JSON.stringify(body) }),
  adminLatestMetadataScrape: () => request('/admin/metadata-scrapes/latest'),
  adminMetadataScrape: (batchId, params = {}) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}?` + new URLSearchParams(
    Object.entries(params).filter(([, value]) => value !== '' && value != null)
  ).toString()),
  adminPauseMetadataScrape: (batchId) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/pause`, { method: 'POST' }),
  adminResumeMetadataScrape: (batchId) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/resume`, { method: 'POST' }),
  adminRetryMetadataScrapeItem: (batchId, itemId) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/items/${itemId}/retry`, { method: 'POST' }),
  adminApplyMetadataScrapeItem: (batchId, itemId, body) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/items/${itemId}/apply`, { method: 'POST', body: JSON.stringify(body) }),
  standbyContent: () => request('/standby/content'),
  adminUploadStandbyLogo: (file) => {
    const body = new FormData()
    body.append('file', file)
    return request('/admin/standby/logo', { method: 'POST', body })
  },
  adminWishes: () => request('/admin/wishes'),

  // ADM-04 AI 曲库与主题歌单
  // ADM-04 AI song library and themed playlists
  adminAiTasks: () => request('/admin/ai/tasks'),
  adminAiCreateTask: (songId) => request('/admin/ai/tasks', { method: 'POST', body: JSON.stringify({ songId }) }),
  adminAiCreateImportTask: (recordId) => request('/admin/ai/tasks/import-records', { method: 'POST', body: JSON.stringify({ recordId }) }),
  adminAiCreateUnclassified: (limit = 50) => request('/admin/ai/tasks/unclassified', { method: 'POST', body: JSON.stringify({ limit }) }),
  adminAiRetryTask: (id) => request(`/admin/ai/tasks/${id}/retry`, { method: 'POST' }),
  adminAiApplyTask: (id, result) => request(`/admin/ai/tasks/${id}/apply`, { method: 'POST', body: JSON.stringify(result) }),
  adminAiPlaylists: () => request('/admin/ai/playlists'),
  adminAiPlaylist: (id) => request(`/admin/ai/playlists/${id}`),
  adminAiCreatePlaylist: (body) => request('/admin/ai/playlists', { method: 'POST', body: JSON.stringify(body) }),
  adminAiSavePlaylistPreview: (body) => request('/admin/ai/playlists/from-preview', { method: 'POST', body: JSON.stringify(body) }),
  adminAiUpdatePlaylist: (id, body) => request(`/admin/ai/playlists/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  adminAiDeletePlaylist: (id) => request(`/admin/ai/playlists/${id}`, { method: 'DELETE' }),
  adminAiGeneratePlaylist: (body) => request('/admin/ai/playlists/generate', { method: 'POST', body: JSON.stringify(body) }),
  adminAiPreviewPlaylist: (body) => request('/admin/ai/playlists/preview', { method: 'POST', body: JSON.stringify(body) }),
  adminAiConfig: () => request('/admin/ai/config'),
  adminAiPutConfig: (body) => request('/admin/ai/config', { method: 'PUT', body: JSON.stringify(body) }),
  adminAiModels: () => request('/admin/ai/config/models'),
  adminArtists: (params = {}) => request('/admin/artists?' + new URLSearchParams(Object.entries(params).filter(([, value]) => value !== '' && value != null)).toString()),
  adminArtistPage: (params = {}) => request('/admin/artists/page?' + new URLSearchParams(Object.entries(params).filter(([, value]) => value !== '' && value != null)).toString()),
  adminScanLocalAvatars: () => request('/admin/artists/local-avatars/scan', { method: 'POST' }),
  adminUploadArtistAvatar: (artistKey, file) => {
    const body = new FormData()
    body.append('file', file)
    return request(`/admin/artists/${encodeURIComponent(artistKey)}/avatar`, { method: 'POST', body })
  },
  adminAnalyzeArtist: (artist) => request('/admin/artists/analyze', { method: 'POST', body: JSON.stringify({ artist }) }),
  adminAnalyzeArtists: (artists) => request('/admin/artists/analyze-batch', { method: 'POST', body: JSON.stringify({ artists }) }),
  adminApplyArtistGender: (artist, gender) => request('/admin/artists/apply', { method: 'POST', body: JSON.stringify({ artist, gender }) }),
  adminAiTestConfig: () => request('/admin/ai/config/test', { method: 'POST' }),
  adminAiRepair: () => request('/admin/ai/repair', { method: 'POST' }),
  adminAiRepairProgress: (batchId) => request(`/admin/ai/repair/${encodeURIComponent(batchId)}`),
  adminAiPauseRepair: (batchId) => request(`/admin/ai/repair/${encodeURIComponent(batchId)}/pause`, { method: 'POST' }),
  adminAiResumeRepair: (batchId) => request(`/admin/ai/repair/${encodeURIComponent(batchId)}/resume`, { method: 'POST' }),
  adminAiRetryFailedRepair: (batchId) => request(`/admin/ai/repair/${encodeURIComponent(batchId)}/retry-failed`, { method: 'POST' }),
  adminAiAddPlaylistSong: (id, songId) => request(`/admin/ai/playlists/${id}/songs`, { method: 'POST', body: JSON.stringify({ songId }) }),
  adminAiRemovePlaylistSong: (id, songId) => request(`/admin/ai/playlists/${id}/songs/${songId}`, { method: 'DELETE' }),
  adminAiUploadPlaylistCover: (id, file) => {
    const body = new FormData()
    body.append('file', file)
    return request(`/admin/ai/playlists/${id}/cover`, { method: 'POST', body })
  },
  adminAiReorderPlaylistSongs: (id, songIds) => request(`/admin/ai/playlists/${id}/songs/order`, { method: 'PUT', body: JSON.stringify({ songIds }) })
}

/**
 * 控制指令便捷封装。
 * 自动绑定当前用户 token，返回预配置的控制方法集合。
 *
 * Convenient control command wrapper.
 * Auto-binds the current user token and returns a set of pre-configured control methods.
 *
 * @param {string} clientToken - 客户端用户标识 / client user identifier
 * @returns {object} 控制方法集合 / collection of control methods
 */
export function makeControls(clientToken) {
  const c = (action, params) => api.control(action, params, clientToken)
  return {
    order: (songId, force = false) => c('order', { song_id: songId, force }),
    top: (queueId) => c('top', { queue_id: queueId }),
    cancel: (queueId) => c('cancel', { queue_id: queueId }),
    shuffle: () => c('shuffle', {}),
    play: () => c('play', {}),
    pause: () => c('pause', {}),
    stop: () => c('stop', {}),
    seek: (positionMs) => c('seek', { position_ms: Math.max(0, positionMs) }),
    next: () => c('next', {}),
    restart: () => c('restart', {}),
    setVolume: (volume) => c('set_volume', { volume }),
    mute: (muted) => c('mute', { muted }),
    setVocal: (mode) => c('set_vocal', { mode }),
    swapVocalTracks: () => c('swap_vocal_tracks', {}),
    effect: (effectId) => c('effect', { effect_id: effectId })
  }
}

export default api
