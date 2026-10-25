import { describe, expect, it, vi } from 'vitest'
import { createLyricLoader } from './lyricLoader'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((r, j) => { resolve = r; reject = j })
  return { promise, resolve, reject }
}

function response(text) {
  return { ok: true, status: 200, text: async () => text }
}

describe('lyric loader', () => {
  it('ignores a late response from the previous song', async () => {
    const first = deferred()
    const second = deferred()
    const fetchImpl = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    let currentSong = { id: 1, lyricType: 'LRC' }
    const loaded = []
    const errors = []
    const loader = createLyricLoader({
      fetchImpl,
      parse: text => text,
      getCurrentSong: () => currentSong,
      onSuccess: value => loaded.push(value),
      onError: error => errors.push(error)
    })

    const oldLoad = loader.load(1)
    currentSong = { id: 2, lyricType: 'LRC' }
    const newLoad = loader.load(2)

    second.resolve(response('新歌'))
    await newLoad
    first.resolve(response('旧歌'))
    await oldLoad

    expect(loaded).toEqual(['新歌'])
    expect(errors).toEqual([])
    expect(fetchImpl).toHaveBeenNthCalledWith(1, '/api/lyric/1', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(fetchImpl).toHaveBeenNthCalledWith(2, '/api/lyric/2', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it.each([
    ['empty id', 0, { id: 0, lyricType: 'LRC' }],
    ['missing current song', 1, null],
    ['mismatched song id', 1, { id: 2, lyricType: 'LRC' }],
    ['song without lyrics', 1, { id: 1, lyricType: 'none' }],
  ])('skips %s without fetching', async (_label, id, song) => {
    const fetchImpl = vi.fn()
    const onReset = vi.fn()
    const loader = createLyricLoader({ fetchImpl, getCurrentSong: () => song, onReset })

    await expect(loader.load(id)).resolves.toEqual({ status: 'skipped' })
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(onReset).toHaveBeenCalledOnce()
  })

  it('reports non-success HTTP responses through the error callback', async () => {
    const error = vi.fn()
    const loader = createLyricLoader({
      fetchImpl: vi.fn().mockResolvedValue({ ok: false, status: 503 }),
      getCurrentSong: () => ({ id: 1, lyricType: 'LRC' }),
      onError: error
    })

    const result = await loader.load(1)

    expect(result.status).toBe('error')
    expect(result.error.message).toBe('HTTP 503')
    expect(error).toHaveBeenCalledWith(result.error)
  })

  it('reports parser and fetch failures only while the request is still current', async () => {
    const errors = []
    const loader = createLyricLoader({
      fetchImpl: vi.fn().mockResolvedValue(response('bad lyrics')),
      parse: () => { throw new Error('parse failed') },
      getCurrentSong: () => ({ id: 1, lyricType: 'LRC' }),
      onError: error => errors.push(error)
    })

    const result = await loader.load(1)

    expect(result.status).toBe('error')
    expect(result.error.message).toBe('parse failed')
    expect(errors).toEqual([result.error])
  })

  it('treats AbortError as stale and aborts active work on dispose', async () => {
    const fetchImpl = vi.fn((_url, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(Object.assign(new Error('cancelled'), { name: 'AbortError' })), { once: true })
    }))
    const currentSong = { id: 1, lyricType: 'LRC' }
    const error = vi.fn()
    const loader = createLyricLoader({ fetchImpl, getCurrentSong: () => currentSong, onError: error })

    const pending = loader.load(1)
    loader.dispose()

    await expect(pending).resolves.toEqual({ status: 'stale' })
    expect(error).not.toHaveBeenCalled()
  })
})
