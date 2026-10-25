// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import NowPlayingBar from './NowPlayingBar.vue'
import { usePlayerStore } from '../stores/player'

let app
let root
let router
let player

describe('NowPlayingBar', () => {
  beforeEach(async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/lyric', name: 'lyric', component: { template: '<div />' } }
      ]
    })
    await router.push('/')
    await router.isReady()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(NowPlayingBar)
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await nextTick()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('shows the empty state before a song is playing', () => {
    expect(root.textContent).toContain('还没人点歌，来点第一首吧')
    expect(root.querySelector('a.now')).toBeNull()
  })

  it('shows song details, a clamped progress bar, cover, and playback state', async () => {
    player.nowPlaying = { song: { title: '晴天', artist: '周杰伦', durationMs: 1_000, coverUrl: '/cover.jpg' } }
    player.positionMs = 1_500
    player.state = 'paused'
    await nextTick()

    expect(root.querySelector('a.now').getAttribute('aria-label')).toBe('打开歌词：晴天')
    expect(root.textContent).toContain('晴天')
    expect(root.querySelector('.cover').getAttribute('style')).toContain('cover.jpg')
    expect(root.querySelector('.bar i').getAttribute('style')).toContain('100%')
    expect(root.querySelector('.chip').textContent).toBe('已暂停')
  })

  it('uses the icon and zero progress when cover and duration are absent', async () => {
    player.nowPlaying = { song: { artist: '歌手', durationMs: 0 } }
    player.positionMs = 500
    player.state = 'playing'
    player.playback = { status: 'IDLE' }
    await nextTick()

    expect(root.querySelector('.cover svg')).not.toBeNull()
    expect(root.querySelector('.bar i').getAttribute('style')).toContain('0%')
    expect(root.querySelector('.chip').textContent).toBe('演唱中')
  })

  it('defaults a missing playback status to the idle status label', async () => {
    player.nowPlaying = { song: { title: '晴天', durationMs: 1_000 } }
    player.state = 'playing'
    player.playback = {}
    await nextTick()

    expect(root.querySelector('.chip').textContent).toBe('演唱中')
  })

  it.each([
    ['PREPARING', 'paused', '准备 MV 中'],
    ['FAILED', 'playing', 'MV 准备失败']
  ])('displays the %s playback status', async (status, state, expected) => {
    player.nowPlaying = { song: { title: 'MV' } }
    player.playback = { status }
    player.state = state
    await nextTick()

    expect(root.querySelector('.chip').textContent).toBe(expected)
  })

  it('does not invent a chip label for an unknown state', async () => {
    player.nowPlaying = { song: { title: 'MV' } }
    player.playback = { status: 'UNKNOWN' }
    player.state = 'stopped'
    await nextTick()

    expect(root.querySelector('.chip').textContent).toBe('')
  })
})
