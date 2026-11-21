// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'

function abortError() {
  const error = new Error('Aborted')
  error.name = 'AbortError'
  return error
}

/** fetch 桩：永不返回，只有 signal 中止时才 reject。 */
function hangingFetch() {
  return vi.fn((url, init) => new Promise((resolve, reject) => {
    if (init?.signal?.aborted) {
      reject(abortError())
      return
    }
    init?.signal?.addEventListener('abort', () => reject(abortError()))
  }))
}

function jsonResponse(body) {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => body
  }
}

describe('request 超时策略', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('默认不超时：未显式声明的接口保持今天的行为', async () => {
    const fetchMock = hangingFetch()
    vi.stubGlobal('fetch', fetchMock)

    const pending = api.adminStartScan()
    let settled = false
    pending.then(() => { settled = true }, () => { settled = true })

    await vi.advanceTimersByTimeAsync(120_000)

    expect(settled).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })

  it('读接口在 10 秒后超时，错误码为 TIMEOUT', async () => {
    vi.stubGlobal('fetch', hangingFetch())

    const pending = api.getQueue()
    const assertion = expect(pending).rejects.toMatchObject({ code: 'TIMEOUT' })

    await vi.advanceTimersByTimeAsync(10_000)

    await assertion
    vi.unstubAllGlobals()
  })

  it('超时错误不能被误判为 AbortError（否则切歌逻辑会把它当成正常取消）', async () => {
    vi.stubGlobal('fetch', hangingFetch())

    const pending = api.getQueue()
    const caught = pending.catch((error) => error)

    await vi.advanceTimersByTimeAsync(10_000)
    const error = await caught

    expect(error.code).toBe('TIMEOUT')
    expect(error.name).not.toBe('AbortError')
    expect(error.message).toContain('超时')
    vi.unstubAllGlobals()
  })

  it('调用方自己的 signal 仍然可以中止请求', async () => {
    vi.stubGlobal('fetch', hangingFetch())
    const controller = new AbortController()

    const pending = api.searchSongs('周杰伦', '', 0, { signal: controller.signal })
    const assertion = expect(pending).rejects.toMatchObject({ name: 'AbortError' })

    controller.abort()

    await assertion
    vi.unstubAllGlobals()
  })

  it('成功路径不残留定时器与监听器', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ ok: true })))

    await api.getQueue()

    expect(vi.getTimerCount()).toBe(0)
    vi.unstubAllGlobals()
  })

  it('timeoutMs 不会被透传进 fetch 的 init', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    await api.getQueue()

    const init = fetchMock.mock.calls[0][1]
    expect(init).not.toHaveProperty('timeoutMs')
    expect(init.signal).toBeDefined()
    vi.unstubAllGlobals()
  })

  it('长耗时写接口不会被 10 秒砍断（扫描/诊断/AI 批量）', async () => {
    const fetchMock = hangingFetch()
    vi.stubGlobal('fetch', fetchMock)

    const pending = api.adminAiRepair()
    let settled = false
    pending.then(() => { settled = true }, () => { settled = true })

    await vi.advanceTimersByTimeAsync(60_000)

    expect(settled).toBe(false)
    vi.unstubAllGlobals()
  })
})
