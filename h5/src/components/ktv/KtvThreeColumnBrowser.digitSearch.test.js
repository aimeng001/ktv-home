// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    searchSongs: vi.fn(async () => []),
    browseSongPage: vi.fn(async () => ({
      items: [
        { id: 810234, catalogNumber: '810234', title: '吻别', artist: '张学友', durationMs: 300000 }
      ],
      total: 1
    })),
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

describe('KtvThreeColumnBrowser 6 位数字点歌编号与纯数字搜索', () => {
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

  it('歌曲卡片上展示 6 位数字点歌编号 No.810234', async () => {
    app = createApp(KtvThreeColumnBrowser, {
      initialTab: 'song_name',
    })
    app.use(pinia)
    app.mount(root)
    await nextTick()
    await nextTick()

    const songNumEl = root.querySelector('.song-num')
    expect(songNumEl).not.toBeNull()
    expect(songNumEl.textContent).toBe('No.810234')
  })
})
