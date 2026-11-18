// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const { apiMock, controlsMock } = vi.hoisted(() => ({
  apiMock: {
    browseSongPage: vi.fn(),
    browseArtistPage: vi.fn(),
    browseArtistInitials: vi.fn(),
    browseLanguages: vi.fn(),
    browseTags: vi.fn(),
    searchSongs: vi.fn(),
    ranking: vi.fn(),
    newSongs: vi.fn(),
    standbyContent: vi.fn(),
    playlists: vi.fn(),
    playlistDetail: vi.fn(),
    orderPlaylist: vi.fn(),
    recentHistory: vi.fn(),
    repeatHistory: vi.fn(),
    favorites: vi.fn(),
    favoriteIds: vi.fn(),
    roomHostStatus: vi.fn(),
    claimRoomHost: vi.fn(),
    releaseRoomHost: vi.fn(),
    health: vi.fn(),
    releaseInfo: vi.fn(),
    addWish: vi.fn(),
  },
  controlsMock: {
    order: vi.fn(),
    top: vi.fn(),
    cancel: vi.fn(),
    shuffle: vi.fn(),
    play: vi.fn(),
    pause: vi.fn(),
    next: vi.fn(),
    restart: vi.fn(),
    seek: vi.fn(),
    setVolume: vi.fn(),
    setMuted: vi.fn(),
    setVocal: vi.fn(),
    swapVocalTracks: vi.fn(),
    effect: vi.fn(),
  }
}))

vi.mock('../api/client', () => ({
  default: apiMock,
  makeControls: vi.fn(() => controlsMock)
}))

import KtvKioskView from './KtvKioskView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('KtvKioskView', () => {
  let app
  let root
  let player
  let router

  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    HTMLMediaElement.prototype.play = vi.fn().mockResolvedValue()
    HTMLMediaElement.prototype.pause = vi.fn()
    HTMLMediaElement.prototype.load = vi.fn()
    apiMock.browseSongPage.mockResolvedValue({
      items: [{ id: 101, title: '晴天', artist: '周杰伦', mediaType: 'KTV_VIDEO', hasVocalTrack: true, durationMs: 269000, playable: true }],
      total: 1, page: 0, size: 18
    })
    apiMock.browseArtistPage.mockResolvedValue({ items: [{ name: '周杰伦', artistKey: 'jay', songCount: 12 }], total: 1 })
    apiMock.browseArtistInitials.mockResolvedValue(['Z'])
    apiMock.browseLanguages.mockResolvedValue([{ name: '国语', songCount: 50 }])
    apiMock.browseTags.mockResolvedValue([{ name: '流行', songCount: 80 }])
    apiMock.searchSongs.mockResolvedValue([
      { id: 202, title: '稻香', artist: '周杰伦', mediaType: 'KTV_VIDEO', hasVocalTrack: true, durationMs: 223000, playable: true }
    ])
    apiMock.ranking.mockResolvedValue([
      { id: 301, title: '七里香', artist: '周杰伦', mediaType: 'KTV_VIDEO', hasVocalTrack: true, durationMs: 299000, playable: true }
    ])
    apiMock.newSongs.mockResolvedValue([
      { id: 302, title: 'Mojito', artist: '周杰伦', mediaType: 'MV', hasVocalTrack: false, durationMs: 185000, playable: true }
    ])
    apiMock.standbyContent.mockResolvedValue({ logoUrl: '/api/standby/logo' })
    apiMock.playlists.mockResolvedValue([])
    apiMock.recentHistory.mockResolvedValue([])
    apiMock.roomHostStatus.mockResolvedValue({ claimed: false, isHost: false, revision: 1 })
    apiMock.health.mockResolvedValue({ status: 'UP' })
    apiMock.releaseInfo.mockResolvedValue({ version: '1.0.40' })
    controlsMock.order.mockResolvedValue({
      list: [{ queueId: 55, song: { id: 101, title: '晴天' } }],
      stateRevision: 1
    })
    controlsMock.top.mockResolvedValue({
      list: [{ queueId: 55, song: { id: 101, title: '晴天' } }],
      stateRevision: 2
    })
    controlsMock.setMuted.mockResolvedValue({ muted: true, stateRevision: 3 })

    const pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore(pinia)
    user.register('测试房客')
    user.markRegistrationSuccess({ id: 9, nickname: '测试房客' })
    player = usePlayerStore(pinia)
    player.socket = { send: vi.fn(), close: vi.fn() }
    player.connected = true

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'entry', component: KtvKioskView },
        { path: '/home', name: 'home', component: KtvKioskView },
        { path: '/search', name: 'search', component: KtvKioskView },
        { path: '/browse', name: 'browse', component: KtvKioskView },
        { path: '/artist/:name', name: 'artist', component: KtvKioskView },
        { path: '/admin', name: 'admin-dashboard', component: { template: '<div>admin</div>' } },
      ]
    })
    await router.push('/')
    await router.isReady()

    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(KtvKioskView)
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await nextTick()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('renders the 10-tile Android-TV Bento dashboard and 11-control bottom dock', () => {
    expect(root.querySelectorAll('.bento-tile')).toHaveLength(10)
    expect(root.querySelector('.ktv-bottom-dock')).not.toBeNull()
    expect(root.querySelectorAll('.dock-circle')).toHaveLength(10)
    expect(root.querySelector('.video-container')).not.toBeNull()
  })

  it('switches automatically between TV Console mode and Web KTV Host mode', async () => {
    player.handleEvent('sync_full', {
      state: 'playing', volume: 65, muted: false, vocalMode: 'accompaniment',
      tvOnline: true, stateRevision: 10,
      playing: { queueId: 5, orderedByNick: '小明', song: { id: 101, title: '晴天', artist: '周杰伦', hasVocalTrack: true } },
      list: [{ queueId: 6, song: { id: 102, title: '七里香' }, orderedByNick: '小红' }],
      playback: { kind: 'NATIVE', status: 'READY', sourceFileId: 55 }
    })
    await nextTick()
    expect(root.querySelector('.mode-pill')?.textContent).toContain('TV控制台')
    expect(root.querySelector('.queue-badge')?.textContent?.trim()).toBe('1')

    player.handleEvent('player_state', {
      state: 'playing', volume: 65, muted: false, vocalMode: 'accompaniment',
      tvOnline: false, stateRevision: 11,
      playing: { queueId: 5, orderedByNick: '小明', song: { id: 101, title: '晴天', artist: '周杰伦', hasVocalTrack: true } },
      list: [],
      playback: { kind: 'NATIVE', status: 'READY', sourceFileId: 55 }
    })
    player.handleEvent('player_role', { role: 'ACTIVE', generation: 2, lease_ms: 45000 })
    await nextTick()
    expect(root.querySelector('.mode-pill')?.textContent).toContain('本机KTV主屏')
  })

  it('opens the 3-column browser from Bento tiles and loads songs and orders', async () => {
    const songNameTile = Array.from(root.querySelectorAll('.bento-tile'))
      .find(el => el.textContent.includes('歌名点歌'))
    songNameTile.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 10))
    await nextTick()

    expect(root.querySelector('.three-col-body')).not.toBeNull()
    expect(root.querySelector('.soft-keyboard')).not.toBeNull()
    expect(apiMock.browseSongPage).toHaveBeenCalled()

    const orderBtn = root.querySelector('.order-btn')
    expect(orderBtn?.textContent).toContain('点歌')
    orderBtn.click()
    await Promise.resolve()
    expect(controlsMock.order).toHaveBeenCalledWith(101)
  })

  it('opens header modals for QR, Mixer, and Settings cleanly', async () => {
    root.querySelector('button[aria-label="手机扫码点歌"]').click()
    await nextTick()
    expect(root.querySelector('.qr-img')).not.toBeNull()

    root.querySelector('.modal-card .close-btn').click()
    await nextTick()
    root.querySelector('button[aria-label="调音与现场音效"]').click()
    await nextTick()
    expect(root.querySelector('.effects-grid')).not.toBeNull()
  })

  it('opens queue drawer and fullscreen MV overlay cleanly', async () => {
    root.querySelector('.queue-btn').click()
    await nextTick()
    expect(root.querySelector('.queue-drawer')).not.toBeNull()

    root.querySelector('.queue-drawer .close-btn').click()
    await nextTick()
    expect(root.querySelector('.queue-drawer')).toBeNull()

    root.querySelector('.video-container').click()
    await nextTick()
    expect(root.querySelector('.media-stage.fullscreen')).not.toBeNull()
    expect(root.querySelector('.fs-topbar')).not.toBeNull()
  })

  it('loads standby logo, displays effect banner, triggers mute control, and reports pitch support honestly', async () => {
    await Promise.resolve()
    await nextTick()
    const standbyImg = root.querySelector('.pip-idle-img')
    expect(standbyImg?.getAttribute('src')).toBe('/api/standby/logo')

    player.handleEvent('effect_play', { effect_id: 'clap' })
    await nextTick()
    expect(root.querySelector('.effect-stage-toast')?.textContent).toContain('现场鼓掌')

    root.querySelector('button[aria-label="调音与现场音效"]').click()
    await nextTick()
    const muteBtn = root.querySelector('.mini-mute-btn')
    expect(muteBtn?.textContent).toContain('静音')
    muteBtn.click()
    await Promise.resolve()
    expect(controlsMock.setMuted).toHaveBeenCalledWith(true)

    root.querySelector('button[aria-label="伴奏升调"]').click()
    await nextTick()
    expect(root.textContent).not.toContain('+1 半音')
  })

  it('supports T9 multi-tap search, real array /api/songs responses, exact category queries, and top-priority ordering', async () => {
    const songNameTile = Array.from(root.querySelectorAll('.bento-tile'))
      .find(el => el.textContent.includes('歌名点歌'))
    songNameTile.click()
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 15))
    await nextTick()

    // Test 置顶 button orders and tops queueId 55
    const topBtn = root.querySelector('.top-order-btn')
    topBtn.click()
    await Promise.resolve()
    await nextTick()
    expect(controlsMock.order).toHaveBeenCalledWith(101)
    expect(controlsMock.top).toHaveBeenCalledWith(55)

    // Switch to T9 mode and test multi-tap cycling: 2 -> A -> B, then 0 -> B0
    const t9Tab = Array.from(root.querySelectorAll('.kb-mode-tabs button')).find(el => el.textContent.includes('T9'))
    t9Tab.click()
    await nextTick()

    const kbInput = root.querySelector('.kb-input-row input')
    const key2 = Array.from(root.querySelectorAll('.t9-key')).find(el => el.textContent.includes('ABC'))
    const key0 = Array.from(root.querySelectorAll('.t9-key')).find(el => el.querySelector('strong')?.textContent === '0')
    key2.click()
    await nextTick()
    expect(kbInput?.value).toBe('A')
    key2.click()
    await nextTick()
    expect(kbInput?.value).toBe('B')
    key0.click()
    await nextTick()
    expect(kbInput?.value).toBe('B0')

    kbInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await new Promise(resolve => setTimeout(resolve, 15))
    await nextTick()
    expect(apiMock.searchSongs).toHaveBeenCalledWith('B0', '', 0, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(root.textContent).toContain('稻香')

    // Clear keyword so category tabs query browseSongPage cleanly
    const clearBtn = Array.from(root.querySelectorAll('.t9-key')).find(el => el.textContent.includes('清空'))
    clearBtn.click()
    await nextTick()

    const switchCapsule = (label) => Array.from(root.querySelectorAll('.capsule-tab')).find(el => el.textContent.trim() === label)

    switchCapsule('高清专区').click()
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ tag: '高清HD歌曲' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    switchCapsule('抖音神曲').click()
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ tag: '抖音神曲' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    switchCapsule('综艺点歌').click()
    await new Promise(resolve => setTimeout(resolve, 15))
    const wwgkBtn = Array.from(root.querySelectorAll('.variety-btn')).find(el => el.textContent.includes('我为歌狂'))
    expect(wwgkBtn).not.toBeUndefined()
    wwgkBtn.click()
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ tag: '我为歌狂' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    switchCapsule('热歌新歌').click()
    await new Promise(resolve => setTimeout(resolve, 15))
    Array.from(root.querySelectorAll('.rail-btn')).find(el => el.textContent.includes('30天飙升榜')).click()
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.ranking).toHaveBeenCalled()
    expect(root.textContent).toContain('七里香')

    Array.from(root.querySelectorAll('.rail-btn')).find(el => el.textContent.includes('最新入库')).click()
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.newSongs).toHaveBeenCalled()
    expect(root.textContent).toContain('Mojito')
  })

  it('passes singer region filter to artist APIs and mediaType filter to browseSongPage', async () => {
    const singerTile = Array.from(root.querySelectorAll('.bento-tile')).find(el => el.textContent.includes('歌手点歌'))
    singerTile.click()
    await new Promise(resolve => setTimeout(resolve, 15))
    await nextTick()

    const inlandBtn = Array.from(root.querySelectorAll('.rail-btn')).find(el => el.textContent.trim() === '内地')
    inlandBtn.click()
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.browseArtistPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ region: '内地' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(apiMock.browseArtistInitials).toHaveBeenLastCalledWith(
      '',
      '内地',
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    Array.from(root.querySelectorAll('.capsule-tab')).find(el => el.textContent.trim() === '本地媒体').click()
    await new Promise(resolve => setTimeout(resolve, 15))
    Array.from(root.querySelectorAll('.rail-btn')).find(el => el.textContent.includes('KTV 双音轨')).click()
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ mediaType: 'KTV_VIDEO' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
  })

  it('hides media stage when idle, renders autoplay unlock independently, and resolves deep-link routes', async () => {
    const idleStage = root.querySelector('.media-stage')
    expect(idleStage?.style.width).toBe('0px')
    expect(idleStage?.style.opacity).toBe('0')

    player.handleEvent('sync_full', {
      state: 'playing', volume: 65, muted: false, vocalMode: 'accompaniment',
      tvOnline: false, stateRevision: 20,
      playing: { queueId: 7, orderedByNick: '房客', song: { id: 101, title: '晴天', artist: '周杰伦' } },
      list: [],
      playback: { kind: 'NATIVE', status: 'READY', sourceFileId: 55 }
    })
    player.handleEvent('player_role', { role: 'ACTIVE', generation: 1, lease_ms: 45000 })
    player.audioBlockedByAutoplay = true
    await nextTick()
    const unlockBtn = root.querySelector('.autoplay-unlock')
    expect(unlockBtn).not.toBeNull()
    expect(idleStage.contains(unlockBtn)).toBe(false)

    await router.push('/artist/all?mode=vocalForm&value=%E5%AF%B9%E5%94%B1')
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.browseSongPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ vocalForm: '对唱' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    await router.push('/search?q=%E6%99%B4%E5%A4%A9')
    await new Promise(resolve => setTimeout(resolve, 15))
    expect(apiMock.searchSongs).toHaveBeenLastCalledWith(
      '晴天', '', 0, expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
  })

  it('displays WS status indicator, settings auto-fullscreen & media feedback, and fullscreen hall/home navigation', async () => {
    expect(root.querySelector('.ws-pill')?.textContent).toContain('在线')
    player.connected = false
    await nextTick()
    expect(root.querySelector('.ws-pill')?.textContent).toContain('重连中')
    player.connected = true

    // Settings modal: autoFullscreen persistence & media feedback wish form
    apiMock.addWish.mockResolvedValue({ ok: true })
    root.querySelector('button[aria-label="系统设置"]').click()
    await nextTick()
    const autoFsToggle = root.querySelector('.toggle-pill')
    expect(autoFsToggle?.textContent).toContain('已关闭')
    autoFsToggle.click()
    await nextTick()
    expect(autoFsToggle.textContent).toContain('已开启')
    expect(localStorage.getItem('ktv_auto_fullscreen')).toBe('true')

    const wishInput = root.querySelector('input[placeholder="输入曲库缺少的歌名或歌手"]')
    wishInput.value = '夜曲'
    wishInput.dispatchEvent(new Event('input'))
    await nextTick()
    const wishBtn = Array.from(root.querySelectorAll('.modal-body button')).find(el => el.textContent.includes('提交心愿'))
    wishBtn.click()
    await Promise.resolve()
    expect(apiMock.addWish).toHaveBeenCalledWith('夜曲', expect.any(String))

    root.querySelector('.modal-card .close-btn').click()
    await nextTick()

    // Fullscreen 点歌台 and 首页 navigation buttons
    player.handleEvent('sync_full', {
      state: 'playing', volume: 65, muted: false, vocalMode: 'accompaniment',
      tvOnline: false, stateRevision: 30, positionMs: 65000,
      playing: { queueId: 12, orderedByNick: '房客', song: { id: 101, title: '晴天', artist: '周杰伦', format: 'rmvb' } },
      list: [],
      playback: { kind: 'LIVE_TRANSCODE', status: 'READY', sourceFileId: 99 }
    })
    player.handleEvent('player_role', { role: 'ACTIVE', generation: 2, lease_ms: 45000 })
    await nextTick()

    // Verify mid-song liveTranscode initial start offset uses positionMs (65s)
    const videoEl = root.querySelector('video.ktv-video')
    expect(videoEl?.src).toContain('/api/stream/99')
    expect(videoEl?.src).toContain('start=65.000')

    // Seek triggers reload and suppresses stale timeupdate before loadedmetadata
    player.handleEvent('playback_seeked', {
      state: 'playing', volume: 65, muted: false, vocalMode: 'accompaniment',
      tvOnline: false, stateRevision: 31, positionMs: 120000, seekSequence: 1,
      playing: { queueId: 12, orderedByNick: '房客', song: { id: 101, title: '晴天', artist: '周杰伦', format: 'rmvb' } },
      list: [],
      playback: { kind: 'LIVE_TRANSCODE', status: 'READY', sourceFileId: 99 }
    })
    await nextTick()
    Object.defineProperty(videoEl, 'currentTime', { value: 42, configurable: true })
    videoEl.dispatchEvent(new Event('timeupdate'))
    expect(player.socket.send).not.toHaveBeenCalledWith(expect.objectContaining({ type: 'progress' }))

    videoEl.dispatchEvent(new Event('loadedmetadata'))
    await nextTick()

    root.querySelector('.video-container').click()
    await nextTick()
    const fsBrowserBtn = Array.from(root.querySelectorAll('.fs-act')).find(el => el.textContent.trim() === '点歌台')
    expect(fsBrowserBtn).not.toBeUndefined()
    fsBrowserBtn.click()
    await nextTick()
    expect(root.querySelector('.media-stage.fullscreen')).toBeNull()
    expect(root.querySelector('.three-col-body')).not.toBeNull()

    root.querySelector('.video-container').click()
    await nextTick()
    const fsHomeBtn = Array.from(root.querySelectorAll('.fs-act')).find(el => el.textContent.trim() === '首页')
    expect(fsHomeBtn).not.toBeUndefined()
    fsHomeBtn.click()
    await nextTick()
    expect(root.querySelector('.bento-dashboard')).not.toBeNull()
  })
})
