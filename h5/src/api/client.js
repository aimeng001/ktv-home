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
import { toQuery } from './query'

const BASE = '/api'

/**
 * 读接口的兜底超时（毫秒）。
 *
 * 只用于「本地数据库查询」类接口：正常 <200ms，10 秒只为兜住「网线被拔、Wi-Fi 掉线」
 * 这类永远不返回的请求。长耗时写接口（扫描、AI、刮削、转码、融合、上传）**不加**超时，
 * 否则会从「等待」变成「后端还在跑 + 用户重试 → 重复任务」。
 *
 * Fallback timeout for read endpoints. Write endpoints that are legitimately slow
 * must stay at 0 to avoid turning a wait into a duplicated task.
 */
const READ_TIMEOUT_MS = 10_000

/**
 * 把「调用方 signal」与「超时」合并成一个可以中止的 signal。
 *
 * 不用 AbortSignal.any（Chrome 116+），项目要兼容旧 WebView。
 * timeoutMs <= 0 时直接返回调用方 signal：零开销、行为与改动前完全一致。
 *
 * Merges the caller signal with a timeout into a single abortable signal.
 * Returns the caller signal untouched when timeoutMs <= 0 so the default path
 * keeps the pre-change behaviour.
 *
 * @param {AbortSignal|undefined} callerSignal 调用方 signal / caller signal
 * @param {number} timeoutMs 超时毫秒，<=0 表示不超时 / timeout in ms, <=0 disables it
 * @returns {{signal: AbortSignal|undefined, timedOut: Function, cleanup: Function}} 合并结果 / linked result
 */
function linkAbortSignals(callerSignal, timeoutMs) {
  if (!(Number.isFinite(timeoutMs) && timeoutMs > 0)) {
    return { signal: callerSignal, timedOut: () => false, cleanup() {} }
  }

  const controller = new AbortController()
  let timedOut = false
  const onCallerAbort = () => controller.abort(callerSignal?.reason)
  if (callerSignal) {
    if (callerSignal.aborted) controller.abort(callerSignal.reason)
    else callerSignal.addEventListener('abort', onCallerAbort)
  }
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  return { signal: controller.signal, timedOut: () => timedOut, cleanup() {
    clearTimeout(timer)
    if (callerSignal) callerSignal.removeEventListener('abort', onCallerAbort)
  } }
}

/** 超时专用错误：code 固定 TIMEOUT，name 不是 AbortError（否则会被当成正常取消）。 */
function timeoutError() {
  const error = new Error('请求超时，请检查网络后重试')
  error.code = 'TIMEOUT'
  return error
}

/**
 * 读接口快捷方法：默认带 10 秒超时，调用方仍可覆盖。
 *
 * Read-endpoint helper: 10s fallback timeout by default, still overridable.
 */
function read(path, options = {}) {
  return request(path, { timeoutMs: READ_TIMEOUT_MS, ...options })
}

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
  const { headers: optionHeaders, timeoutMs = 0, signal: callerSignal, ...fetchOptions } = options
  const headers = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...(optionHeaders || {})
  }
  if (path === '/admin' || path.startsWith('/admin/')) {
    const token = getAdminToken()
    if (token) headers['X-Admin-Token'] = token
  }
  const link = linkAbortSignals(callerSignal, timeoutMs)
  let res
  try {
    res = await fetch(BASE + path, {
      ...fetchOptions,
      headers,
      signal: link.signal
    })
  } catch (error) {
    link.cleanup()
    if (link.timedOut()) throw timeoutError()
    throw error
  }
  link.cleanup()
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

async function requestBlob(path, options = {}) {
  const { headers: optionHeaders, timeoutMs = 0, signal: callerSignal, ...fetchOptions } = options
  const headers = {
    ...(optionHeaders || {})
  }
  if (path === '/admin' || path.startsWith('/admin/')) {
    const token = getAdminToken()
    if (token) headers['X-Admin-Token'] = token
  }
  const link = linkAbortSignals(callerSignal, timeoutMs)
  let res
  try {
    res = await fetch(BASE + path, {
      ...fetchOptions,
      headers,
      signal: link.signal
    })
  } catch (error) {
    link.cleanup()
    if (link.timedOut()) throw timeoutError()
    throw error
  }
  link.cleanup()
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
  return res.blob()
}

export const api = {
  health: () => request('/health'),
  releaseInfo: () => request('/release'),

  // 搜索/曲库（P1.6/P1.7）
  // Search / song library (P1.6/P1.7)
  searchSongs: (keyword, type = '', page = 0, options = {}) =>
    read(`/songs?keyword=${encodeURIComponent(keyword)}&type=${encodeURIComponent(type)}&page=${page}`, options),
  songDetail: (id) => read(`/songs/${id}`),
  lyricText: (id) => request(`/lyric/${id}`),

  // 队列/控制（P1.9~P1.12）
  // Queue / control (P1.9~P1.12)
  getQueue: () => read('/queue'),
  control: (action, params = {}, clientToken) =>
    request('/control', {
      method: 'POST',
      timeoutMs: READ_TIMEOUT_MS,
      body: JSON.stringify({ action, params, client_token: clientToken })
    }),

  // 发现/历史/心愿（P3）
  // Discovery / history / wishes (P3)
  ranking: (days = 30, options = {}) => read(`/ranking?days=${days}`, options),
  newSongs: (options = {}) => read('/songs/new', options),
  history: () => read('/history'),
  recentHistory: (clientToken, mine = false, options = {}) => read(`/history/recent?clientToken=${encodeURIComponent(clientToken || '')}&mine=${mine}`, options),
  repeatHistory: (historyId, clientToken, force = false) => request(`/history/${historyId}/repeat`, { method: 'POST', body: JSON.stringify({ clientToken, force }) }),
  favorites: (clientToken, page = null, size = 100, options = {}) => read(`/favorites?clientToken=${encodeURIComponent(clientToken || '')}${page == null ? '' : `&page=${page}&size=${size}`}`, options),
  favoriteIds: (clientToken, page = null, size = 100, options = {}) => read(`/favorites/ids?clientToken=${encodeURIComponent(clientToken || '')}${page == null ? '' : `&page=${page}&size=${size}`}`, options),
  addFavorite: (songId, clientToken) => request(`/favorites/${songId}`, { method: 'POST', body: JSON.stringify({ clientToken }) }),
  removeFavorite: (songId, clientToken) => request(`/favorites/${songId}?clientToken=${encodeURIComponent(clientToken || '')}`, { method: 'DELETE' }),
  playlists: (options = {}) => read('/playlists', options),
  playlistDetail: (id, options = {}) => read(`/playlists/${id}`, options),
  orderPlaylist: (id, clientToken) => request(`/playlists/${id}/order`, { method: 'POST', body: JSON.stringify({ clientToken }) }),
  browseArtistPage: (params = {}, options = {}) => read('/browse/artists/page?' + toQuery(params), options),
  browseArtistInitials: (gender = '', region = '', options = {}) => {
    const query = new URLSearchParams()
    if (gender) query.set('gender', gender)
    if (region) query.set('region', region)
    return read('/browse/artists/initials?' + query.toString(), options)
  },
  browseLanguages: (options = {}) => read('/browse/languages', options),
  browseTags: (options = {}) => read('/browse/tags', options),
  browseSongPage: (params = {}, options = {}) => read('/browse/songs/page?' + toQuery(params), options),
  addWish: (keyword, clientToken) =>
    request('/wishes', { method: 'POST', body: JSON.stringify({ keyword, client_token: clientToken }) }),
  registerUser: (clientToken, nickname) =>
    request('/user', { method: 'POST', body: JSON.stringify({ client_token: clientToken, nickname }) }),
  roomHostStatus: (clientToken) => read(`/room/host?clientToken=${encodeURIComponent(clientToken || '')}`),
  claimRoomHost: (clientToken) => request('/room/host/claim', { method: 'POST', body: JSON.stringify({ clientToken }) }),
  releaseRoomHost: (clientToken) => request('/room/host/release', { method: 'POST', body: JSON.stringify({ clientToken }) }),

  // 管理后台（P2）
  // Admin dashboard (P2)
  adminAuthStatus: () => request('/admin/auth/status'),
  adminLogin: (password) => request('/admin/auth/login', { method: 'POST', body: JSON.stringify({ password }) }),
  adminLogout: () => request('/admin/auth/logout', { method: 'POST' }),
  adminStatus: () => read('/admin/status'),
  adminSongs: (params = {}, options = {}) => read('/admin/songs?' + toQuery(params), options),
  adminSong: (id) => read(`/admin/songs/${id}`),
  adminEditSong: (id, body) => request(`/admin/songs/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  adminUpdateAudioLayout: (fileId, body) => request(`/admin/files/${fileId}/audio-layout`, { method: 'PUT', body: JSON.stringify(body) }),
  adminSwapAudioLayout: (fileId) => request(`/admin/files/${fileId}/audio-layout/swap`, { method: 'POST' }),
  adminDeleteSong: (id) => request(`/admin/songs/${id}`, { method: 'DELETE' }),
  adminDeleteSongs: (ids) => request('/admin/songs', { method: 'DELETE', body: JSON.stringify({ ids }) }),
  adminMergeSong: (keepSongId, sourceSongId) => request(`/admin/songs/${keepSongId}/merge`, { method: 'POST', body: JSON.stringify({ sourceSongId }) }),
  adminSourceLibrary: (params = {}, options = {}) => request('/admin/source-library?' + toQuery(params), options),
  adminStartSourceTranscode: (ids = [], all = false) => request('/admin/source-library/transcode', { method: 'POST', body: JSON.stringify({ ids, all }) }),
  adminPrioritizeSourceTranscode: (id) => request('/admin/source-library/transcode/priority', { method: 'POST', body: JSON.stringify({ id }) }),
  adminSourceTranscodeProgress: () => read('/admin/source-library/progress'),
  adminCleanupImportedSources: () => request('/admin/source-library/cleanup', { method: 'POST' }),
  adminDeleteSources: (ids = [], filters = {}) => request('/admin/source-library', {
    method: 'DELETE', body: JSON.stringify({ ids, ...filters })
  }),
  adminStartScan: () => request('/admin/scan/start', { method: 'POST' }),
  adminScanProgress: () => read('/admin/scan/progress'),
  adminDiagnostics: () => request('/admin/diagnostics'),
  adminMemoryDiagnostics: () => request('/admin/diagnostics/memory'),
  adminGetSettings: () => request('/admin/settings'),
  adminTranscodeHardware: () => request('/admin/settings/transcode-hardware'),
  adminPutSettings: (body) => request('/admin/settings', { method: 'PUT', body: JSON.stringify(body) }),
  adminResetTranscodeDefaults: () => request('/admin/settings/transcode-defaults', { method: 'POST' }),
  adminMusicSourceConfig: () => request('/admin/music-sources/config'),
  adminPutMusicSourceConfig: (body) => request('/admin/music-sources/config', { method: 'PUT', body: JSON.stringify(body) }),
  adminTestMusicSources: (providers = []) => request('/admin/music-sources/test', { method: 'POST', body: JSON.stringify({ providers }) }),
  adminTestFallbackSources: (providers = []) => request('/admin/music-sources/test-fallback', { method: 'POST', body: JSON.stringify({ providers }) }),
  adminResetMusicSourceCooldowns: () => request('/admin/music-sources/reset-cooldown', { method: 'POST' }),
  adminSongExternalMatches: (songId, refresh = false, keyword = '', providers = []) => request(`/admin/songs/${songId}/external-matches?` + new URLSearchParams([
    ['refresh', String(refresh)],
    ...(keyword ? [['keyword', keyword]] : []),
    ...providers.map(provider => ['providers', provider])
  ]).toString()),
  adminApplyExternalMatch: (songId, provider, externalId, fields, overrides = {}) => request(`/admin/songs/${songId}/external-matches/${encodeURIComponent(provider)}/${encodeURIComponent(externalId)}/apply`, { method: 'POST', body: JSON.stringify({ fields, overrides }) }),
  adminApplyManualMetadata: (songId, fields, overrides) => request(`/admin/songs/${songId}/metadata/manual`, { method: 'POST', body: JSON.stringify({ fields, overrides }) }),
  adminStartMetadataScrape: (body) => request('/admin/metadata-scrapes', { method: 'POST', body: JSON.stringify(body) }),
  adminLatestMetadataScrape: () => request('/admin/metadata-scrapes/latest'),
  adminMetadataScrape: (batchId, params = {}, options = {}) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}?` + toQuery(params), options),
  adminPauseMetadataScrape: (batchId) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/pause`, { method: 'POST' }),
  adminCancelMetadataScrape: (batchId) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/cancel`, { method: 'POST' }),
  adminResumeMetadataScrape: (batchId) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/resume`, { method: 'POST' }),
  adminRetryMetadataScrapeItem: (batchId, itemId) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/items/${itemId}/retry`, { method: 'POST' }),
  adminApplyMetadataScrapeItem: (batchId, itemId, body) => request(`/admin/metadata-scrapes/${encodeURIComponent(batchId)}/items/${itemId}/apply`, { method: 'POST', body: JSON.stringify(body) }),
  standbyContent: () => request('/standby/content'),
  adminUploadStandbyLogo: (file) => {
    const body = new FormData()
    body.append('file', file)
    return request('/admin/standby/logo', { method: 'POST', body })
  },
  adminResetStandbyLogo: () => request('/admin/standby/logo', { method: 'DELETE' }),
  adminWishes: () => request('/admin/wishes'),
  adminDeleteWish: (id) => request(`/admin/wishes/${id}`, { method: 'DELETE' }),
  adminExportWishesCsv: () => requestBlob('/admin/wishes/export'),
  adminDownloadDiagnostics: () => requestBlob('/admin/diagnostics/bundle', { method: 'POST' }),

  // ADM-04 AI 曲库与主题歌单
  // ADM-04 AI song library and themed playlists
  adminAiTasks: () => read('/admin/ai/tasks'),
  adminAiCreateTasks: (songIds) => request('/admin/ai/tasks/batch', { method: 'POST', body: JSON.stringify({ songIds }) }),
  adminAiCreateUnclassified: (limit = 50) => request('/admin/ai/tasks/unclassified', { method: 'POST', body: JSON.stringify({ limit }) }),
  adminAiRetryTask: (id) => request(`/admin/ai/tasks/${id}/retry`, { method: 'POST' }),
  adminAiApplyTask: (id, result) => request(`/admin/ai/tasks/${id}/apply`, { method: 'POST', body: JSON.stringify(result) }),
  adminAiPlaylists: () => request('/admin/ai/playlists'),
  adminAiPlaylist: (id) => request(`/admin/ai/playlists/${id}`),
  adminAiCreatePlaylist: (body) => request('/admin/ai/playlists', { method: 'POST', body: JSON.stringify(body) }),
  adminAiSavePlaylistPreview: (body) => request('/admin/ai/playlists/from-preview', { method: 'POST', body: JSON.stringify(body) }),
  adminAiUpdatePlaylist: (id, body) => request(`/admin/ai/playlists/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  adminAiDeletePlaylist: (id) => request(`/admin/ai/playlists/${id}`, { method: 'DELETE' }),
  adminAiPreviewPlaylist: (body) => request('/admin/ai/playlists/preview', { method: 'POST', body: JSON.stringify(body) }),
  adminAiConfig: () => request('/admin/ai/config'),
  adminAiPutConfig: (body) => request('/admin/ai/config', { method: 'PUT', body: JSON.stringify(body) }),
  adminAiModels: () => request('/admin/ai/config/models'),
  adminArtistPage: (params = {}, options = {}) => read('/admin/artists/page?' + toQuery(params), options),
  adminScanLocalAvatars: () => request('/admin/artists/local-avatars/scan', { method: 'POST' }),
  adminRetryMissingAvatars: (options = {}) => request('/admin/artists/avatars/retry', { method: 'POST', ...options }),
  adminArtistAvatarProgress: (options = {}) => read('/admin/artists/avatars/progress', options),
  adminScrapeArtistAvatar: (artistKey) => request(`/admin/artists/${encodeURIComponent(artistKey)}/avatar/scrape`, { method: 'POST' }),
  adminApplyArtistGenderDictionary: (options = {}) => request('/admin/artists/gender-dictionary/apply', { method: 'POST', ...options }),
  adminUploadArtistAvatar: (artistKey, file) => {
    const body = new FormData()
    body.append('file', file)
    return request(`/admin/artists/${encodeURIComponent(artistKey)}/avatar`, { method: 'POST', body })
  },
  adminAnalyzeArtist: (artist) => request('/admin/artists/analyze', { method: 'POST', body: JSON.stringify({ artist }) }),
  adminAnalyzeArtists: (artists) => request('/admin/artists/analyze-batch', { method: 'POST', body: JSON.stringify({ artists }) }),
  adminApplyArtistGender: (artist, gender) => request('/admin/artists/apply', { method: 'POST', body: JSON.stringify({ artist, gender }) }),
  adminIktvFusionStatus: (options = {}) => request('/admin/iktv-reference/fusion/status', options),
  adminIktvFusionBatch: (batchId, options = {}) => request(`/admin/iktv-reference/fusion/batches/${batchId}`, options),
  adminIktvFusionResume: (options = {}) => request('/admin/iktv-reference/fusion/resume', { method: 'POST', ...options }),
  adminIktvFusionRollback: (batchId, options = {}) => request(`/admin/iktv-reference/fusion/batches/${batchId}/rollback`, { method: 'POST', ...options }),
  adminAiTestConfig: () => request('/admin/ai/config/test', { method: 'POST' }),
  adminAiRepair: () => request('/admin/ai/repair', { method: 'POST' }),
  adminAiLatestRepair: () => request('/admin/ai/repair/latest'),
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
export function makeControls(clientToken, { onResponse } = {}) {
  const c = async (action, params) => {
    const response = await api.control(action, params, clientToken)
    onResponse?.(response)
    return response
  }
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
    setMuted: (muted) => c('mute', { muted: Boolean(muted) }),
    setVocal: (mode) => c('set_vocal', { mode }),
    swapVocalTracks: () => c('swap_vocal_tracks', {}),
    effect: (effectId) => c('effect', { effect_id: effectId })
  }
}

export default api
