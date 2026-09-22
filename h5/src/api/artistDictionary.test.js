// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body
  }
}

describe('artist gender dictionary client', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('posts the bounded database classification action and preserves cancellation', async () => {
    const signal = new AbortController().signal
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ success: true, changed: 12 }),
    )

    const result = await api.adminApplyArtistGenderDictionary({ signal })

    expect(result).toEqual({ success: true, changed: 12 })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/artists/gender-dictionary/apply')
    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({ method: 'POST', signal }))
  })
})
