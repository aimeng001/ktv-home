// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    searchSongs: vi.fn(async () => []),
    browseSongPage: vi.fn(async () => ({ items: [], total: 0 })),
    ranking: vi.fn(async () => []),
    newSongs: vi.fn(async () => []),
    browseArtistPage: vi.fn(async () => ({ items: [], total: 0 })),
    browseArtistInitials: vi.fn(async () => []),
    playlists: vi.fn(async () => []),
    favorites: vi.fn(async () => ({ items: [], total: 0 })),
    recentHistory: vi.fn(async () => []),
  }
}))

vi.mock('../../api/client', () => ({
  default: apiMock,
  makeControls: () => ({ orderSong: vi.fn() })
}))

vi.mock('../../composables/useToast', async () => {
  const { ref } = await import('vue')
  return { useToast: () => ({ message: ref(''), toast: vi.fn() }) }
})

import KtvThreeColumnBrowser from './KtvThreeColumnBrowser.vue'

describe('KtvThreeColumnBrowser wordCount integration', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root?.parentNode) root.parentNode.removeChild(root)
    vi.clearAllMocks()
  })

  it('passes wordCount param to api.browseSongPage when word count chip is clicked', async () => {
    app = createApp(KtvThreeColumnBrowser, {
      initialTab: 'song_name',
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()

    const chips = root.querySelectorAll('.word-count-chip')
    expect(chips.length).toBe(7)

    // Click '3字' (index 3)
    chips[3].click()
    await nextTick()

    const calls = apiMock.browseSongPage.mock.calls
    expect(calls.length).toBeGreaterThan(0)
    const lastCall = calls[calls.length - 1]
    expect(lastCall[0]).toMatchObject({
      wordCount: 3,
    })
  })

  it('filters songs by wordCount when chip is clicked', async () => {
    apiMock.browseSongPage.mockImplementation(async (params) => {
      if (params.wordCount === 2) {
        return { items: [{ id: 2, title: '吻别', artist: '张学友' }], total: 1 }
      }
      return {
        items: [
          { id: 1, title: '海阔天空', artist: 'Beyond' },
          { id: 2, title: '吻别', artist: '张学友' },
        ],
        total: 2
      }
    })

    app = createApp(KtvThreeColumnBrowser, {
      initialTab: 'song_name',
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()

    const chips = root.querySelectorAll('.word-count-chip')
    chips[2].click() // '2字'
    await nextTick()
    await nextTick()

    const titles = [...root.querySelectorAll('.card-title')].map(el => el.textContent.trim())
    expect(titles).toEqual(['吻别'])
  })
})
