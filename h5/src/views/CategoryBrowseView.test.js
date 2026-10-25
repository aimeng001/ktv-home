// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    browseArtistPage: vi.fn(),
    browseArtistInitials: vi.fn(),
    browseLanguages: vi.fn(),
    browseTags: vi.fn()
  }
}))

vi.mock('../api/client', () => ({ default: apiMock }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav aria-label="main navigation" />' } }))

import CategoryBrowseView from './CategoryBrowseView.vue'

describe('CategoryBrowseView', () => {
  let app
  let root
  let router

  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.browseArtistPage.mockResolvedValue({ items: [], total: 0 })
    apiMock.browseArtistInitials.mockResolvedValue([])
    apiMock.browseLanguages.mockResolvedValue([])
    apiMock.browseTags.mockResolvedValue([])
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('renders artist entries, alphabet indexes, and falls back to initials after avatar failure', async () => {
    apiMock.browseArtistPage.mockResolvedValueOnce({
      items: [{ name: '王菲', artistKey: 'WANG_FEI', songCount: 12, avatarUrl: '/avatars/wang-fei.jpg' }],
      total: 1
    })
    apiMock.browseArtistInitials.mockResolvedValueOnce(['A', 'W', '热门'])
    await mountBrowse()

    expect(root.textContent).toContain('王菲')
    expect(root.textContent).toContain('12 首')
    expect([...root.querySelectorAll('.letters button')].map(button => button.textContent))
      .toEqual(['热门', 'A', 'W'])
    expect(root.querySelector('.artist-list img').getAttribute('src')).toBe('/avatars/wang-fei.jpg')

    root.querySelector('.artist-list img').dispatchEvent(new Event('error'))
    await flushUi()
    expect(root.querySelector('.artist-list img')).toBeNull()
    expect(root.querySelector('.avatar').textContent).toBe('王')
  })

  it('shows the selected gender empty state and sends the gender to both artist requests', async () => {
    await mountBrowse()
    expect(root.textContent).toContain('暂无全部歌手')

    clickText('.gender-filter button', '女歌手')
    await flushUi()
    expect(root.textContent).toContain('暂无女歌手')
    expect(apiMock.browseArtistPage).toHaveBeenLastCalledWith({ gender: '女歌手', initial: '', page: 0, size: 30 })
    expect(apiMock.browseArtistInitials).toHaveBeenLastCalledWith('女歌手')
  })

  it('defaults to artist browsing when the tab query is absent', async () => {
    await mountBrowse(null)

    expect(root.querySelector('.gender-filter')).not.toBeNull()
    expect(apiMock.browseArtistPage).toHaveBeenCalledWith({ gender: '', initial: '', page: 0, size: 30 })
    expect(apiMock.browseArtistInitials).toHaveBeenCalledWith('')
  })

  it('filters by a selected initial and resets to the hot index when gender changes', async () => {
    apiMock.browseArtistInitials.mockResolvedValue(['A', 'B'])
    await mountBrowse()

    clickText('.letters button', 'B')
    await flushUi()
    expect(apiMock.browseArtistPage).toHaveBeenLastCalledWith({ gender: '', initial: 'B', page: 0, size: 30 })

    clickText('.gender-filter button', '男歌手')
    await flushUi()
    expect(root.querySelector('.letters button.on').textContent).toBe('热门')
    expect(apiMock.browseArtistPage).toHaveBeenLastCalledWith({ gender: '男歌手', initial: '', page: 0, size: 30 })
    expect(apiMock.browseArtistInitials).toHaveBeenLastCalledWith('男歌手')
  })

  it('keeps already loaded artists on later-page failure and offers retry', async () => {
    apiMock.browseArtistPage
      .mockResolvedValueOnce({ items: [{ name: '陈奕迅', songCount: 8 }], total: 31 })
      .mockRejectedValueOnce(new Error('分页暂不可用'))
      .mockResolvedValueOnce({ items: [{ name: '陈奕迅', songCount: 8 }, { name: '林忆莲', songCount: 6 }], total: 2 })
    await mountBrowse()

    const loadMore = root.querySelector('.load-more button')
    loadMore.click()
    await flushUi()
    expect(root.querySelector('.artist-list').textContent).toContain('陈奕迅')
    expect(root.querySelector('.artist-list').textContent).not.toContain('林忆莲')
    expect(root.textContent).toContain('后续歌手读取失败：分页暂不可用')

    root.querySelector('.retry').click()
    await flushUi()
    expect(root.querySelector('.artist-list').textContent).toContain('陈奕迅')
    expect(root.querySelector('.artist-list').textContent).toContain('林忆莲')
    expect(apiMock.browseArtistPage).toHaveBeenCalledTimes(3)
  })

  it('disables pagination while loading and appends only the requested page', async () => {
    let finishPage
    apiMock.browseArtistPage
      .mockResolvedValueOnce({ items: [{ name: '周杰伦', songCount: 10 }], total: 31 })
      .mockReturnValueOnce(new Promise(resolve => { finishPage = resolve }))
    await mountBrowse()

    root.querySelector('.load-more button').click()
    await flushUi()
    expect(root.querySelector('.load-more button').disabled).toBe(true)
    expect(root.querySelector('.load-more button').textContent).toContain('加载中')
    expect(apiMock.browseArtistPage).toHaveBeenLastCalledWith({ gender: '', initial: '', page: 1, size: 30 })

    finishPage({ items: [{ name: '孙燕姿', songCount: 9 }], total: Number.NaN })
    await flushUi()
    expect(root.querySelector('.artist-list').textContent).toContain('周杰伦')
    expect(root.querySelector('.artist-list').textContent).toContain('孙燕姿')
    expect(root.querySelector('.load-more')).toBeNull()
  })

  it('treats a malformed artist page payload as an empty list', async () => {
    apiMock.browseArtistPage.mockResolvedValueOnce({ items: null, total: Number.POSITIVE_INFINITY })
    await mountBrowse()

    expect(root.textContent).toContain('暂无全部歌手')
    expect(root.querySelector('.artist-list')).toBeNull()
    expect(root.querySelector('.load-more')).toBeNull()
  })

  it('treats a malformed initials payload as an empty index', async () => {
    apiMock.browseArtistInitials.mockResolvedValueOnce({ items: ['A', 'B'] })
    await mountBrowse()

    expect([...root.querySelectorAll('.letters button')].map(button => button.textContent)).toEqual(['热门'])
    expect(root.textContent).toContain('暂无全部歌手')
  })

  it('ignores a stale artist page response after a newer initial request completes', async () => {
    let finishOldPage
    apiMock.browseArtistPage
      .mockReturnValueOnce(new Promise(resolve => { finishOldPage = resolve }))
      .mockResolvedValueOnce({ items: [{ name: '新请求歌手', songCount: 1 }], total: 1 })
    apiMock.browseArtistInitials.mockResolvedValue(['A'])
    await mountBrowse()

    clickText('.letters button', 'A')
    await flushUi()
    finishOldPage({ items: [{ name: '过期歌手', songCount: 1 }], total: 1 })
    await flushUi()

    expect(root.textContent).toContain('新请求歌手')
    expect(root.textContent).not.toContain('过期歌手')
  })

  it('shows an artist error and retries both the page and initial requests', async () => {
    apiMock.browseArtistPage
      .mockRejectedValueOnce(new Error('歌手服务不可用'))
      .mockResolvedValueOnce({ items: [{ name: '重试歌手', songCount: 2 }], total: 1 })
    await mountBrowse()

    expect(root.textContent).toContain('歌手读取失败：歌手服务不可用')
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.textContent).toContain('重试歌手')
    expect(apiMock.browseArtistPage).toHaveBeenCalledTimes(2)
    expect(apiMock.browseArtistInitials).toHaveBeenCalledTimes(2)
  })

  it('uses fallback text for initial and later artist-page errors without messages', async () => {
    apiMock.browseArtistPage
      .mockRejectedValueOnce({ message: '' })
      .mockResolvedValueOnce({ items: [{ name: '王菲', songCount: 4 }], total: 31 })
      .mockRejectedValueOnce({ message: '' })
    await mountBrowse()

    expect(root.textContent).toContain('歌手读取失败：网络错误，请稍后重试')
    root.querySelector('.retry').click()
    await flushUi()
    root.querySelector('.load-more button').click()
    await flushUi()

    expect(root.textContent).toContain('后续歌手读取失败：网络错误，请稍后重试')
    expect(root.querySelector('.artist-list').textContent).toContain('王菲')
  })

  it('loads languages and tags on demand, caches successful lists, and renders category icons and links', async () => {
    apiMock.browseLanguages.mockResolvedValueOnce([
      { name: '国语', songCount: 12 },
      { name: '儿歌', songCount: 3 },
      { name: '摇滚', songCount: 4 },
      { name: '情歌', songCount: 5 },
      { name: '其他', songCount: 2 }
    ])
    apiMock.browseTags.mockResolvedValueOnce([{ name: '影视金曲', songCount: 7 }])
    await mountBrowse('languages')

    expect(root.querySelectorAll('.cards a')).toHaveLength(5)
    expect([...root.querySelectorAll('.cards a > span')].map(span => span.textContent))
      .toEqual(['🌏', '🧸', '🎸', '💞', '🎶'])
    expect(root.querySelector('.cards a').getAttribute('href')).toContain('mode=language')

    clickText('.tabs button', '分类')
    await flushUi()
    expect(root.querySelector('.cards').textContent).toContain('影视金曲')
    expect(root.querySelector('.cards a').getAttribute('href')).toContain('mode=tag')

    clickText('.tabs button', '语种')
    await flushUi()
    expect(apiMock.browseLanguages).toHaveBeenCalledTimes(1)
    expect(apiMock.browseTags).toHaveBeenCalledTimes(1)
  })

  it('distinguishes empty, loading, error, and retry states for a category list', async () => {
    apiMock.browseLanguages
      .mockRejectedValueOnce(new Error('语种暂不可用'))
      .mockResolvedValueOnce([{ name: '英语', songCount: 1 }])
    await mountBrowse('languages')

    expect(root.textContent).toContain('分类读取失败：语种暂不可用')
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.querySelector('.cards').textContent).toContain('英语')

    clickText('.tabs button', '分类')
    await flushUi()
    expect(root.textContent).toContain('暂无分类')
  })

  it('shows a fallback message when a category error has no message', async () => {
    apiMock.browseLanguages.mockRejectedValueOnce({ message: '' })
    await mountBrowse('languages')

    expect(root.textContent).toContain('分类读取失败：网络错误，请稍后重试')
    expect(root.querySelector('.retry')).not.toBeNull()
  })

  async function mountBrowse(tab = 'artists') {
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/browse', name: 'browse', component: CategoryBrowseView },
        { path: '/artist/:name', name: 'artist', component: { template: '<div />' } }
      ]
    })
    await router.push(tab == null ? '/browse' : `/browse?tab=${tab}`)
    await router.isReady()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(router)
    app.mount(root)
    await flushUi()
  }
})

function clickText(selector, text) {
  const button = [...document.querySelectorAll(selector)].find(element => element.textContent.trim() === text)
  if (!button) throw new Error(`Button not found: ${selector} / ${text}`)
  button.click()
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
