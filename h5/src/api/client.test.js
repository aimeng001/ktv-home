// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, makeControls } from './client'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body
  }
}

describe('api client admin authentication', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends the admin token only to admin endpoints', async () => {
    localStorage.setItem('home-ktv.admin.token', 'session-token')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }))

    await api.adminStatus()
    expect(fetchMock.mock.calls[0][1].headers['X-Admin-Token']).toBe('session-token')

    await api.searchSongs('周杰伦')
    expect(fetchMock.mock.calls[1][1].headers['X-Admin-Token']).toBeUndefined()
  })

  it('passes the same AbortSignal from searchSongs to fetch', async () => {
    const signal = new AbortController().signal
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse([]))

    await api.searchSongs('晴天', '', 0, { signal })

    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({ signal }))
  })

  it('clears the token and notifies the app when the server requires login', async () => {
    localStorage.setItem('home-ktv.admin.token', 'expired-token')
    const required = vi.fn()
    window.addEventListener('home-ktv-admin-auth-required', required)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({
      code: 'ADMIN_AUTH_REQUIRED',
      message: '请先登录管理后台'
    }, 401))

    await expect(api.adminStatus()).rejects.toMatchObject({
      code: 'ADMIN_AUTH_REQUIRED',
      status: 401
    })
    expect(localStorage.getItem('home-ktv.admin.token')).toBeNull()
    expect(required).toHaveBeenCalledTimes(1)
    window.removeEventListener('home-ktv-admin-auth-required', required)
  })

  it('serializes control commands with the client token and clamps negative seeks', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }))
    const controls = makeControls('client-123')

    await controls.seek(-50)
    await controls.setVocal('original')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/control')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      action: 'seek',
      params: { position_ms: 0 },
      client_token: 'client-123'
    })
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
      action: 'set_vocal',
      params: { mode: 'original' },
      client_token: 'client-123'
    })
  })

  it('does not add a JSON content type to multipart uploads', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }))
    const file = new File(['logo'], 'logo.png', { type: 'image/png' })

    await api.adminUploadStandbyLogo(file)

    const [, options] = fetchMock.mock.calls[0]
    expect(options.headers['Content-Type']).toBeUndefined()
    expect(options.body).toBeInstanceOf(FormData)
    expect(options.body.get('file').name).toBe('logo.png')
  })

  it('publishes a control response to the caller-owned player projection', async () => {
    const response = { list: [{ queueId: 7 }], state: 'paused', stateRevision: 3 }
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse(response))
    const onResponse = vi.fn()
    const controls = makeControls('client-123', { onResponse })

    await controls.pause()

    expect(onResponse).toHaveBeenCalledWith(response)
  })

  it('saves an AI playlist preview with one atomic request', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ id: 9 }))

    await api.adminAiSavePlaylistPreview({
      name: '聚会歌单', description: '说明', theme: 'AI 策划', publicVisible: true, songIds: [1, 2]
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/ai/playlists/from-preview')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      name: '聚会歌单', description: '说明', theme: 'AI 策划', publicVisible: true, songIds: [1, 2]
    })
  })

  it('encodes metadata scrape identifiers and uploads artist and playlist cover files as multipart data', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }))
    const avatar = new File(['artist-avatar'], 'avatar.png', { type: 'image/png' })
    const playlistCover = new File(['playlist-cover'], 'cover.jpg', { type: 'image/jpeg' })

    await api.adminMetadataScrape('批次/一', { page: 2, status: 'failed', ignored: '' })
    await api.adminUploadArtistAvatar('周杰伦/王菲', avatar)
    await api.adminAiUploadPlaylistCover(17, playlistCover)

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/admin/metadata-scrapes/%E6%89%B9%E6%AC%A1%2F%E4%B8%80?page=2&status=failed'
    )
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/artists/%E5%91%A8%E6%9D%B0%E4%BC%A6%2F%E7%8E%8B%E8%8F%B2/avatar')
    expect(fetchMock.mock.calls[2][0]).toBe('/api/admin/ai/playlists/17/cover')
    for (const [, options] of fetchMock.mock.calls.slice(1)) {
      expect(options.method).toBe('POST')
      expect(options.headers['Content-Type']).toBeUndefined()
      expect(options.body).toBeInstanceOf(FormData)
      expect(options.body.get('file')).toBeInstanceOf(File)
    }
    expect(fetchMock.mock.calls[1][1].body.get('file').name).toBe('avatar.png')
    expect(fetchMock.mock.calls[2][1].body.get('file').name).toBe('cover.jpg')
  })

  it('serializes admin filters, source deletion payloads, and repeated provider search parameters', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }))
    const signal = new AbortController().signal

    await api.adminSongs({ keyword: '周杰伦', status: '', page: 0, ignored: null }, { signal })
    await api.adminSourceLibrary({ status: 'pending', format: 'rmvb', deleted: false }, { signal })
    await api.adminDeleteSources([4, 9], { status: 'failed', sourceDeleted: false })
    await api.adminSongExternalMatches(12, true, '周杰伦 & 王菲', ['netease', 'qq'])
    await api.adminSongExternalMatches(13)

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/songs?keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&page=0')
    expect(fetchMock.mock.calls[0][1].signal).toBe(signal)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/source-library?status=pending&format=rmvb&deleted=false')
    expect(fetchMock.mock.calls[1][1].signal).toBe(signal)
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({
      ids: [4, 9], status: 'failed', sourceDeleted: false
    })
    expect(fetchMock.mock.calls[3][0]).toBe(
      '/api/admin/songs/12/external-matches?refresh=true&keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6+%26+%E7%8E%8B%E8%8F%B2&providers=netease&providers=qq'
    )
    expect(fetchMock.mock.calls[4][0]).toBe('/api/admin/songs/13/external-matches?refresh=false')
  })

  it('requests the paged artist directory with stable query parameters', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ items: [], total: 0, page: 0, size: 50 }))

    await api.adminArtistPage({ keyword: '周杰伦', gender: '男歌手', reviewed: false, page: 1, size: 50 })

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/artists/page?keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&gender=%E7%94%B7%E6%AD%8C%E6%89%8B&reviewed=false&page=1&size=50')
  })

  it('requests the public artist page and its small initial index', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ items: [], total: 0, page: 0, size: 30 }))
      .mockResolvedValueOnce(jsonResponse(['A', 'Z']))

    await api.browseArtistPage({ gender: '男歌手', initial: 'Z', page: 1, size: 30 })
    await api.browseArtistInitials('男歌手')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/browse/artists/page?gender=%E7%94%B7%E6%AD%8C%E6%89%8B&initial=Z&page=1&size=30')
    expect(fetchMock.mock.calls[1][0]).toBe('/api/browse/artists/initials?gender=%E7%94%B7%E6%AD%8C%E6%89%8B')
  })

  it('omits the gender filter when requesting all artist initials', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse([]))

    await api.browseArtistInitials()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/browse/artists/initials?')
  })

  it('requests a bounded public song page', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ items: [], total: 7285, page: 1, size: 50 })
    )

    await api.browseSongPage({ artist: '周杰伦', sort: 'title', page: 1, size: 50 })

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/browse/songs/page?artist=%E5%91%A8%E6%9D%B0%E4%BC%A6&sort=title&page=1&size=50'
    )
  })

  it('adminScanLocalAvatars issues a POST request to /admin/artists/local-avatars/scan', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ success: true, matched: 12 })
    )

    const result = await api.adminScanLocalAvatars()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/artists/local-avatars/scan')
    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
    expect(result).toEqual({ success: true, matched: 12 })
  })

  it('adminRetryMissingAvatars sends the bounded server-side retry request and preserves abort signals', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ queued: 12, alreadyPending: 3, nextWorkAt: '2026-09-17T00:00:00Z' })
    )
    const signal = new AbortController().signal

    const result = await api.adminRetryMissingAvatars({ signal })

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/artists/avatars/retry')
    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({ method: 'POST', signal }))
    expect(result.queued).toBe(12)
  })

  it('adminDeleteWish issues DELETE to /api/admin/wishes/{id}', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ status: 'ok' }))

    await api.adminDeleteWish(123)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/wishes/123')
    expect(fetchMock.mock.calls[0][1].method).toBe('DELETE')
  })

  it('adminExportWishesCsv issues GET to /api/admin/wishes/export returning a blob', async () => {
    const blob = new Blob(['csv-content'], { type: 'text/csv' })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => 'text/csv; charset=UTF-8' },
      blob: async () => blob
    })

    const result = await api.adminExportWishesCsv()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/wishes/export')
    expect(result).toBe(blob)
  })

  it('adminDownloadDiagnostics issues POST to /api/admin/diagnostics/bundle returning a blob', async () => {
    const blob = new Blob(['zip-content'], { type: 'application/zip' })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => 'application/zip' },
      blob: async () => blob
    })

    const result = await api.adminDownloadDiagnostics()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/diagnostics/bundle')
    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
    expect(result).toBe(blob)
  })

  it('returns null for 204 and reads non-JSON success bodies as text', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce({ ok: true, status: 204, headers: { get: () => '' } })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => null },
        text: async () => 'plain response'
      })

    await expect(api.adminLogout()).resolves.toBeNull()
    await expect(api.health()).resolves.toBe('plain response')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('falls back to the HTTP status when an error body cannot be parsed', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      status: 502,
      json: async () => { throw new Error('not JSON') }
    })

    await expect(api.health()).rejects.toMatchObject({ message: 'HTTP 502', status: 502 })
  })

  it('does not clear the admin session for unrelated 401 errors', async () => {
    localStorage.setItem('home-ktv.admin.token', 'still-valid')
    const required = vi.fn()
    window.addEventListener('home-ktv-admin-auth-required', required)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ code: 'FORBIDDEN', message: 'forbidden' }, 401))

    await expect(api.adminStatus()).rejects.toMatchObject({ code: 'FORBIDDEN', status: 401 })
    expect(localStorage.getItem('home-ktv.admin.token')).toBe('still-valid')
    expect(required).not.toHaveBeenCalled()
    window.removeEventListener('home-ktv-admin-auth-required', required)
  })

  it('sends the admin token on blob requests and propagates blob endpoint errors', async () => {
    localStorage.setItem('home-ktv.admin.token', 'blob-token')
    const blobFetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      status: 200,
      headers: { get: () => 'text/csv' },
      blob: async () => new Blob(['csv'])
    })

    await api.adminExportWishesCsv()
    expect(blobFetch.mock.calls[0][1].headers['X-Admin-Token']).toBe('blob-token')

    blobFetch.mockResolvedValueOnce(jsonResponse({ message: 'export unavailable' }, 503))
    await expect(api.adminExportWishesCsv()).rejects.toMatchObject({
      message: 'export unavailable',
      status: 503
    })
  })

  it('uses the HTTP status when a blob error body cannot be parsed', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => { throw new Error('not JSON') }
    })

    await expect(api.adminExportWishesCsv()).rejects.toMatchObject({ message: 'HTTP 500', status: 500 })
  })

  it('clears an expired admin token when a blob endpoint requires authentication', async () => {
    localStorage.setItem('home-ktv.admin.token', 'expired-blob-token')
    const required = vi.fn()
    window.addEventListener('home-ktv-admin-auth-required', required)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ code: 'ADMIN_AUTH_REQUIRED' }, 401))

    await expect(api.adminExportWishesCsv()).rejects.toMatchObject({
      code: 'ADMIN_AUTH_REQUIRED',
      status: 401
    })
    expect(localStorage.getItem('home-ktv.admin.token')).toBeNull()
    expect(required).toHaveBeenCalledOnce()
    window.removeEventListener('home-ktv-admin-auth-required', required)
  })

  it('keeps standard request headers when FormData is unavailable in the runtime', async () => {
    vi.stubGlobal('FormData', undefined)
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }))

    await api.health()

    expect(fetchMock.mock.calls[0][1].headers['Content-Type']).toBe('application/json')
  })
})
