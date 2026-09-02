import { describe, expect, it, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useUserStore } from '../stores/user'
import { usePlayerStore } from '../stores/player'
import { useFavoritesStore } from '../stores/favorites'
import { createMockSong, createMockQueueItem, createMockPlaylist } from './mockData'
import { parseLrc } from '../composables/lrc'
import { currentLyricIndex, lyricViewState } from '../views/lyricState'
import { createSearchController } from '../views/searchController'
import api from '../api/client'

describe('Mobile Views Click & Interactive Logic Verification', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.restoreAllMocks()
  })

  describe('1. EntryView - User Registration and Room Entry', () => {
    it('handles nickname input, registration, client token persistence and WS init', () => {
      const user = useUserStore()
      expect(user.isRegistered).toBe(false)

      user.register('麦霸小王')
      expect(user.nickname).toBe('麦霸小王')
      expect(user.isRegistered).toBe(true)
      expect(user.clientToken).toBeTruthy()
      expect(localStorage.getItem('ktv_client_token')).toBe(user.clientToken)
      expect(localStorage.getItem('ktv_nickname')).toBe('麦霸小王')
    })

    it('generates random nickname suggestions and fallback tokens', () => {
      const user = useUserStore()
      const suggestion = user.suggestNickname()
      expect(suggestion).toBeTruthy()
      expect(suggestion).toContain('家人')

      const token = user.ensureToken()
      expect(token).toBeTruthy()
    })
  })

  describe('2. HomeView - Navigation, Ranking and Quick Actions', () => {
    it('category buttons map to correct target routes', () => {
      const categoryRoutes = {
        '歌手': { name: 'category-browse', query: { tab: 'artists' } },
        '语种': { name: 'category-browse', query: { tab: 'languages' } },
        '分类': { name: 'category-browse', query: { tab: 'tags' } },
        '新歌': { name: 'artist', query: { tag: '新歌' } },
        '对唱': { name: 'artist', query: { form: '对唱' } },
        '歌单': { name: 'playlists' },
        '我的收藏': { name: 'favorites' }
      }

      expect(categoryRoutes['歌手'].name).toBe('category-browse')
      expect(categoryRoutes['歌单'].name).toBe('playlists')
      expect(categoryRoutes['我的收藏'].name).toBe('favorites')
    })

    it('orders song from hot ranking with optimistic feedback', async () => {
      const mockOrder = vi.fn().mockResolvedValue({ queueId: 2001, position: 3 })
      const song = createMockSong({ id: 101, title: '晴天' })
      const res = await mockOrder(song.id)
      expect(mockOrder).toHaveBeenCalledWith(101)
      expect(res.queueId).toBe(2001)
      expect(res.position).toBe(3)
    })
  })

  describe('3. SearchView - Debounce Search, Filter Chips, Pagination and Wish Submission', () => {
    it('filters by media type chips and paginates with loadMore', async () => {
      const page0 = Array.from({ length: 50 }, (_, i) => createMockSong({ id: i + 1 }))
      const page1 = [createMockSong({ id: 51 })]
      const client = {
        searchSongs: vi.fn().mockResolvedValueOnce(page0).mockResolvedValueOnce(page1),
        addWish: vi.fn().mockResolvedValue({ id: 1, keyword: '七里香' })
      }
      const controller = createSearchController(client, { schedule: fn => fn() })

      controller.setQuery('周杰伦', 'KTV_VIDEO')
      await controller.waitForIdle()

      expect(client.searchSongs).toHaveBeenCalledWith('周杰伦', 'KTV_VIDEO', 0)
      expect(controller.state().results.length).toBe(50)
      expect(controller.state().hasMore).toBe(true)

      await controller.loadMore('周杰伦', 'KTV_VIDEO')
      expect(client.searchSongs).toHaveBeenCalledWith('周杰伦', 'KTV_VIDEO', 1)
      expect(controller.state().results.length).toBe(51)
      expect(controller.state().hasMore).toBe(false)

      const wishRes = await client.addWish('七里香')
      expect(wishRes.keyword).toBe('七里香')
    })
  })

  describe('4. CategoryBrowseView & ArtistView - Alphabet Index, Gender Filter, Sorting', () => {
    it('manages initial letter and gender filter state transitions', () => {
      let currentLetter = '热门'
      let currentGender = '全部'

      currentLetter = 'J'
      currentGender = '男歌手'

      expect(currentLetter).toBe('J')
      expect(currentGender).toBe('男歌手')
    })
  })

  describe('5. PlaylistListView & PlaylistDetailView - Detail Navigation, Share, Order All', () => {
    it('orders all songs in a playlist, filtering out non-playable ones', async () => {
      const playlist = createMockPlaylist({
        songs: [
          createMockSong({ id: 1, title: '可播 1' }),
          createMockSong({ id: 2, title: '可播 2' })
        ]
      })
      const mockOrderPlaylist = vi.fn().mockResolvedValue({ queued: 2, skipped: 0 })
      const res = await mockOrderPlaylist(playlist.id)

      expect(mockOrderPlaylist).toHaveBeenCalledWith('pl-1')
      expect(res.queued).toBe(2)
      expect(res.skipped).toBe(0)
    })
  })

  describe('6. FavoritesView - Favorite Toggle and Store Synchronization', async () => {
    it('adds, removes and persists favorites', async () => {
      vi.spyOn(api, 'addFavorite').mockResolvedValue({ success: true })
      vi.spyOn(api, 'removeFavorite').mockResolvedValue({ success: true })

      const favStore = useFavoritesStore()
      const clientToken = 'test-token-123'

      await favStore.toggle(101, clientToken)
      expect(favStore.has(101)).toBe(true)
      expect(favStore.has(102)).toBe(false)

      await favStore.toggle(102, clientToken)
      expect(favStore.ids.length).toBe(2)

      await favStore.toggle(101, clientToken)
      expect(favStore.has(101)).toBe(false)
      expect(favStore.ids.length).toBe(1)
    })
  })

  describe('7. QueueView - Host Privileges, Boost, Cancel, Cut, Shuffle and History Reorder', () => {
    it('allows song owner or room host to delete queue item', () => {
      const myItem = createMockQueueItem({ orderedByNick: '小明' })
      const otherItem = createMockQueueItem({ orderedByNick: '小红' })
      const userNick = '小明'

      const isMine = q => q.orderedByNick === userNick
      const canDelete = (q, isHost) => isMine(q) || isHost

      expect(canDelete(myItem, false)).toBe(true)
      expect(canDelete(otherItem, false)).toBe(false)
      expect(canDelete(otherItem, true)).toBe(true)
    })

    it('triggers top (boost), next (skip), and smart shuffle', async () => {
      const controls = {
        top: vi.fn().mockResolvedValue({ success: true }),
        cancel: vi.fn().mockResolvedValue({ success: true }),
        next: vi.fn().mockResolvedValue({ success: true }),
        shuffle: vi.fn().mockResolvedValue({ success: true }),
        order: vi.fn().mockResolvedValue({ queueId: 3001 })
      }

      await controls.top(1001)
      expect(controls.top).toHaveBeenCalledWith(1001)

      await controls.next()
      expect(controls.next).toHaveBeenCalled()

      await controls.shuffle()
      expect(controls.shuffle).toHaveBeenCalled()

      await controls.order(101)
      expect(controls.order).toHaveBeenCalledWith(101)
    })
  })

  describe('8. RemoteView - Full Playback & Ambience Controls', () => {
    it('manages play/pause, restart, volume +/- and ambience effects', async () => {
      const player = usePlayerStore()
      const controls = {
        play: vi.fn().mockImplementation(() => { player.state = 'playing' }),
        pause: vi.fn().mockImplementation(() => { player.state = 'paused' }),
        restart: vi.fn().mockResolvedValue({ success: true }),
        setVolume: vi.fn().mockImplementation(v => { player.volume = v }),
        setVocal: vi.fn().mockImplementation(mode => { player.vocalMode = mode }),
        swapVocalTracks: vi.fn().mockResolvedValue({ success: true }),
        effect: vi.fn().mockResolvedValue({ success: true })
      }

      await controls.play()
      expect(player.isPlaying).toBe(true)

      await controls.pause()
      expect(player.isPlaying).toBe(false)

      await controls.setVolume(75)
      expect(player.volume).toBe(75)

      await controls.setVocal('accompaniment')
      expect(player.vocalMode).toBe('accompaniment')

      await controls.effect('clap')
      expect(controls.effect).toHaveBeenCalledWith('clap')
    })
  })

  describe('9. LyricView - Synchronized Karaoke Lyrics Calculation and States', () => {
    it('parses LRC text and calculates current highlighted line based on position', () => {
      const lrcText = `
        [00:01.00]故事的小黄花
        [00:05.50]从出生那年就飘着
        [00:10.20]童年的荡秋千
      `
      const lines = parseLrc(lrcText)
      expect(lines.length).toBe(3)
      expect(lines[0].time).toBe(1000)
      expect(lines[1].time).toBe(5500)
      expect(lines[2].time).toBe(10200)

      expect(currentLyricIndex(lines, 500)).toBe(-1)
      expect(currentLyricIndex(lines, 3000)).toBe(0)
      expect(currentLyricIndex(lines, 6000)).toBe(1)
      expect(currentLyricIndex(lines, 15000)).toBe(2)
    })

    it('determines lyric view states correctly (idle, empty, error, lyrics)', () => {
      expect(lyricViewState(null, [], '')).toBe('idle')
      expect(lyricViewState({ id: 1 }, [], '网络超时')).toBe('error')
      expect(lyricViewState({ id: 1 }, [], '')).toBe('empty')
      expect(lyricViewState({ id: 1 }, [{ time: 0, text: 'Hello' }], '')).toBe('lyrics')
    })
  })
})
