// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    addFavorite: vi.fn(),
    removeFavorite: vi.fn()
  }
}))

vi.mock('../api/client', () => ({ default: apiMock }))

import SongRow from './SongRow.vue'
import { useFavoritesStore } from '../stores/favorites'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'

let app
let root

describe('SongRow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.addFavorite.mockResolvedValue(undefined)
    apiMock.removeFavorite.mockResolvedValue(undefined)
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
    useToast().message.value = ''
  })

  it('renders rank, cover, media tag, extra information, and a safe highlighted title', async () => {
    const song = {
      id: 'song-1',
      title: '<img src=x onerror=alert(1)>MV之歌',
      artist: '歌手',
      mediaType: 'MV',
      coverUrl: '/cover.png'
    }
    await mountSongRow({ song, rank: 2, keyword: 'mv', extra: '国语' })

    expect(root.querySelector('.rank').classList.contains('top')).toBe(true)
    expect(root.querySelector('.cover img').getAttribute('src')).toBe('/cover.png')
    expect(root.querySelector('.tag').textContent).toBe('MV版')
    expect(root.querySelector('.tag').classList.contains('tag-mv')).toBe(true)
    expect(root.querySelector('.s').textContent).toBe('歌手 · 国语')
    expect(root.querySelector('.t img')).toBeNull()
    expect(root.querySelector('.t .hl').textContent).toBe('MV')
  })

  it('uses the empty cover, hides a zero rank, and escapes titles without a match', async () => {
    await mountSongRow({
      song: { id: 'song-2', title: '<晴天 & 夜>', artist: '歌手', mediaType: 'AUDIO' },
      rank: 0,
      keyword: 'not found'
    })

    expect(root.querySelector('.rank')).toBeNull()
    expect(root.querySelector('.cover').classList.contains('empty')).toBe(true)
    expect(root.querySelector('.tag').textContent).toBe('音频版')
    expect(root.querySelector('.t .hl')).toBeNull()
    expect(root.querySelector('.t').innerHTML).toContain('&lt;晴天 &amp; 夜&gt;')
  })

  it('falls back to the audio tag class for an unknown media type', async () => {
    await mountSongRow({ song: { id: 3, title: '歌曲', artist: '歌手', mediaType: 'OTHER' }, rank: 4 })

    expect(root.querySelector('.rank').classList.contains('top')).toBe(false)
    expect(root.querySelector('.tag').textContent).toBe('')
    expect(root.querySelector('.tag').classList.contains('tag-audio')).toBe(true)
  })

  it('uses an empty title safely and falls back to the generic cover description', async () => {
    await mountSongRow({ song: { id: 9, title: '', artist: '歌手', coverUrl: '/cover.png' } })

    expect(root.querySelector('.cover img').getAttribute('alt')).toBe('歌曲封面')
    expect(root.querySelector('.t').textContent).toBe('')
    expect(root.querySelector('.t img')).toBeNull()
  })

  it('emits the full song when the order button is selected', async () => {
    const song = { id: 4, title: '歌曲', artist: '歌手', mediaType: 'KTV_VIDEO' }
    const onOrder = vi.fn()
    await mountSongRow({ song, onOrder })

    expect(root.querySelector('.tag').textContent).toBe('KTV版')
    root.querySelector('button.order-btn').click()

    expect(onOrder).toHaveBeenCalledWith(song)
  })

  it('disables ordering when already queued and removes an existing favorite', async () => {
    const { favorites } = await mountSongRow({
      song: { id: 'song-5', title: '歌曲', artist: '歌手' },
      ordered: true,
      favoriteIds: ['song-5']
    })

    expect(root.querySelector('button.order-btn').disabled).toBe(true)
    expect(root.querySelector('button.order-btn').getAttribute('aria-label')).toBe('已点')
    expect(root.querySelector('button.favorite-btn').getAttribute('aria-label')).toBe('取消收藏')
    root.querySelector('button.favorite-btn').click()
    await flushUi()

    expect(apiMock.removeFavorite).toHaveBeenCalledWith('song-5', 'local-token')
    expect(favorites.has('song-5')).toBe(false)
    expect(root.querySelector('button.favorite-btn').getAttribute('aria-label')).toBe('收藏')
    expect(useToast().message.value).toBe('已取消收藏')
  })

  it('adds a favorite and keeps the button busy until the request resolves', async () => {
    let resolveRequest
    apiMock.addFavorite.mockReturnValue(new Promise(resolve => { resolveRequest = resolve }))
    const { favorites } = await mountSongRow({ song: { id: 'song-6', title: '歌曲', artist: '歌手' } })

    const button = root.querySelector('button.favorite-btn')
    button.click()
    await nextTick()
    expect(button.disabled).toBe(true)

    resolveRequest()
    await flushUi()

    expect(apiMock.addFavorite).toHaveBeenCalledWith('song-6', 'local-token')
    expect(favorites.has('song-6')).toBe(true)
    expect(button.disabled).toBe(false)
    expect(button.getAttribute('aria-label')).toBe('取消收藏')
    expect(useToast().message.value).toBe('已加入收藏')
  })

  it('shows a useful failure message and always releases the busy state', async () => {
    apiMock.addFavorite.mockRejectedValueOnce(new Error('收藏接口不可用'))
    await mountSongRow({ song: { id: 'song-7', title: '歌曲', artist: '歌手' } })
    const button = root.querySelector('button.favorite-btn')

    button.click()
    await flushUi()

    expect(useToast().message.value).toBe('收藏接口不可用')
    expect(button.disabled).toBe(false)
  })

  it('uses the fallback error text when a favorite failure has no message', async () => {
    apiMock.addFavorite.mockRejectedValueOnce({})
    await mountSongRow({ song: { id: 'song-8', title: '歌曲', artist: '歌手' } })

    root.querySelector('button.favorite-btn').click()
    await flushUi()

    expect(useToast().message.value).toBe('收藏操作失败')
  })
})

async function mountSongRow(props) {
  const { favoriteIds, ...componentProps } = props
  const pinia = createPinia()
  setActivePinia(pinia)
  const favorites = useFavoritesStore(pinia)
  favorites.ids = favoriteIds || []
  const user = useUserStore(pinia)
  user.clientToken = 'local-token'

  root = document.createElement('div')
  document.body.appendChild(root)
  app = createApp(SongRow, {
    song: { id: 'default', title: '歌曲', artist: '歌手', mediaType: 'AUDIO', ...componentProps.song },
    ...componentProps
  })
  app.use(pinia)
  app.mount(root)
  await flushUi()
  return { favorites, user }
}

async function flushUi() {
  for (let index = 0; index < 8; index += 1) {
    await Promise.resolve()
    await nextTick()
  }
}
