// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    searchSongs: vi.fn(async () => []),
    browseSongPage: vi.fn(async () => ({ items: [{ id: 1, title: '热门歌', artist: '歌手A', mediaType: 'KTV_VIDEO' }], total: 1 })),
    ranking: vi.fn(async () => []),
    newSongs: vi.fn(async () => []),
    artists: vi.fn(async () => []),
    artistsPage: vi.fn(async () => ({ items: [], total: 0 })),
    browseArtistPage: vi.fn(async () => ({ items: [], total: 0 })),
    browseArtistInitials: vi.fn(async () => []),
    tags: vi.fn(async () => []),
    languages: vi.fn(async () => []),
    favoriteIds: vi.fn(async () => []),
    favorites: vi.fn(async () => [])
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
import { useUserStore } from '../../stores/user'

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
  await nextTick()
}

describe('KtvThreeColumnBrowser 搜索韧性、非阻塞反馈与缓存', () => {
  let app
  let root
  let pinia

  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    const user = useUserStore()
    user.token = 'test-token'
    user.serverUserId = 1

    root = document.createElement('div')
    document.body.appendChild(root)
  })

  afterEach(() => {
    vi.useRealTimers()
    if (app) app.unmount()
    if (root?.parentNode) root.parentNode.removeChild(root)
    vi.clearAllMocks()
  })

  it('在已有列表时直接输入 YQXS 显示搜索中提示，失败时展示可点击重试条，再次命中走缓存', async () => {
    let rejectSearch
    apiMock.searchSongs.mockImplementationOnce(() => new Promise((_, reject) => {
      rejectSearch = reject
    }))

    app = createApp(KtvThreeColumnBrowser, { initialTab: 'song_name' })
    app.use(pinia)
    app.mount(root)
    await flushPromises()

    expect(root.querySelectorAll('.song-card').length).toBe(1)

    const input = root.querySelector('.kb-input-row input')
    input.value = 'YQXS'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    vi.advanceTimersByTime(150)
    await nextTick()

    // Non-blocking searching status is visible even when previous songs remain rendered
    expect(root.querySelector('.stage-inline-status')?.textContent).toContain('YQXS')
    expect(apiMock.searchSongs).toHaveBeenCalledWith('YQXS', '', 0, expect.objectContaining({ signal: expect.any(AbortSignal) }))

    // Simulate timeout / failure -> inline error banner must appear instead of silent freeze
    const timeoutErr = new Error('请求超时，请检查网络后重试')
    timeoutErr.code = 'TIMEOUT'
    rejectSearch(timeoutErr)
    await flushPromises()

    const errorBanner = root.querySelector('.stage-inline-error')
    expect(errorBanner).not.toBeNull()
    expect(errorBanner.textContent).toContain('请求超时')

    // Click Retry -> bypassCache and succeed
    apiMock.searchSongs.mockResolvedValueOnce([{ id: 99, title: '一曲相思', artist: '半阳', mediaType: 'KTV_VIDEO' }])
    errorBanner.querySelector('button').click()
    await flushPromises()

    expect(root.querySelector('.stage-inline-error')).toBeNull()
    expect(root.textContent).toContain('一曲相思')

    // Change keyword to YQ, then switch back to YQXS -> YQXS hits frontend cache (no 3rd searchSongs call for YQXS)
    apiMock.searchSongs.mockResolvedValueOnce([{ id: 100, title: '一起', artist: '歌手B', mediaType: 'KTV_VIDEO' }])
    input.value = 'YQ'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    vi.advanceTimersByTime(150)
    await flushPromises()
    expect(apiMock.searchSongs).toHaveBeenCalledTimes(3)

    input.value = 'YQXS'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    vi.advanceTimersByTime(150)
    await flushPromises()

    expect(apiMock.searchSongs).toHaveBeenCalledTimes(3)
    expect(root.textContent).toContain('一曲相思')
  })

  it('热歌新歌榜单模式下输入关键字仅做本地过滤，不重复拉取 ranking 接口', async () => {
    apiMock.ranking.mockResolvedValue([
      { id: 10, title: '一曲相思', artist: '半阳', titleInitials: 'yqxs', mediaType: 'KTV_VIDEO' },
      { id: 11, title: '晴天', artist: '周杰伦', titleInitials: 'qt', mediaType: 'KTV_VIDEO' }
    ])

    app = createApp(KtvThreeColumnBrowser, { initialTab: 'hot_new' })
    app.use(pinia)
    app.mount(root)
    await flushPromises()

    // Switch to 30天飙升榜
    const rankingBtn = [...root.querySelectorAll('.left-rail button')].find(b => b.textContent.includes('30天飙升榜'))
    rankingBtn.click()
    await flushPromises()
    expect(apiMock.ranking).toHaveBeenCalledTimes(1)

    const input = root.querySelector('.kb-input-row input')
    input.value = 'YQXS'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    vi.advanceTimersByTime(150)
    await flushPromises()

    expect(apiMock.ranking).toHaveBeenCalledTimes(1)
    expect(root.textContent).toContain('一曲相思')
    expect(root.textContent).not.toContain('晴天')
  })
})
