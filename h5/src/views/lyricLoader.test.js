import { describe, expect, it, vi } from 'vitest'
import { createLyricLoader } from './lyricLoader'

function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
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
})
