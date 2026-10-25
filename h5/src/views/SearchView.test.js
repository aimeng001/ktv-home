// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock, makeControlsMock, toastMock } = vi.hoisted(() => ({
  apiMock: { searchSongs: vi.fn(), addWish: vi.fn() },
  makeControlsMock: vi.fn(),
  toastMock: vi.fn()
}))

vi.mock('../api/client', () => ({ default: apiMock, makeControls: makeControlsMock }))
vi.mock('../composables/useToast', () => ({ useToast: () => ({ toast: toastMock }) }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav />' } }))
vi.mock('../components/SongRow.vue', () => ({
  default: {
    props: ['song', 'keyword', 'extra', 'ordered'],
    emits: ['order'],
    template: '<button class="song-row" :data-id="song.id" :data-extra="extra" :data-keyword="keyword" :data-ordered="ordered" @click="$emit(\'order\', song)">{{ song.title }}</button>'
  }
}))

import SearchView from './SearchView.vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'

describe('SearchView', () => {
  let app
  let root
  let router
  let pinia
  let user
  let player
  let orderMock

  beforeEach(async () => {
    vi.useFakeTimers()
    localStorage.clear()
    vi.clearAllMocks()
    apiMock.searchSongs.mockResolvedValue([])
    apiMock.addWish.mockResolvedValue({})
    orderMock = vi.fn().mockResolvedValue({ list: [{}] })
    makeControlsMock.mockReturnValue({ order: orderMock })

    pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore(pinia)
    user.clientToken = 'search-client'
    player = usePlayerStore(pinia)
    player.applyControlResponse = vi.fn()

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div>home</div>' } },
        { path: '/search', name: 'search', component: SearchView }
      ]
    })
    await router.push('/')
    await router.push('/search')
    await router.isReady()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await flushUi()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
    vi.useRealTimers()
  })

  it('starts idle, debounces a trimmed query, and formats song duration', async () => {
    apiMock.searchSongs.mockResolvedValueOnce([{ id: 1, title: '青花瓷', durationMs: 225000 }])
    expect(root.textContent).toContain('输入关键词开始搜索')

    await typeQuery('  青花瓷  ')
    expect(root.textContent).toContain('搜索中')
    await vi.advanceTimersByTimeAsync(299)
    expect(apiMock.searchSongs).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await flushUi()

    expect(apiMock.searchSongs).toHaveBeenCalledWith('青花瓷', '', 0, expect.objectContaining({ signal: expect.any(Object) }))
    expect(root.querySelector('.cnt').textContent).toContain('1 首歌曲')
    expect(root.querySelector('.song-row').getAttribute('data-extra')).toBe('3:45')
    expect(root.querySelector('.song-row').getAttribute('data-keyword')).toBe('  青花瓷  ')
  })

  it('runs an immediate search when the media filter changes and marks the selected filter', async () => {
    apiMock.searchSongs.mockResolvedValue([{ id: 2, title: '电影原声' }])
    await typeQuery('电影原声')
    await vi.advanceTimersByTimeAsync(300)
    await flushUi()
    expect(apiMock.searchSongs).toHaveBeenCalledTimes(1)

    clickText('.filter', 'MV')
    await flushUi()
    expect(apiMock.searchSongs).toHaveBeenLastCalledWith('电影原声', 'MV', 0, expect.any(Object))
    expect(root.querySelector('.filter[aria-pressed="true"]').textContent).toBe('MV')

    clickText('.filter', 'MV')
    await flushUi()
    expect(apiMock.searchSongs).toHaveBeenCalledTimes(2)
  })

  it('shows an empty result and submits the trimmed song wish with the client token', async () => {
    await typeQuery('  想唱的歌  ')
    await runSearch()
    expect(root.querySelector('.empty').textContent).toContain('曲库还没有这首歌')

    root.querySelector('.empty button').click()
    await flushUi()
    expect(apiMock.addWish).toHaveBeenCalledWith('想唱的歌', 'search-client')
    expect(toastMock).toHaveBeenCalledWith('已记下《想唱的歌》，稍后补充到曲库')
  })

  it('reports wish submission failures and keeps the empty search usable', async () => {
    apiMock.addWish.mockRejectedValueOnce(new Error('心愿服务故障'))
    await typeQuery('希望补歌')
    await runSearch()

    root.querySelector('.empty button').click()
    await flushUi()
    expect(toastMock).toHaveBeenCalledWith('心愿服务故障')
    expect(root.querySelector('.empty')).not.toBeNull()
  })

  it('uses a fallback message when wish submission fails without an error message', async () => {
    apiMock.addWish.mockRejectedValueOnce(new Error(''))
    await typeQuery('希望补充的歌')
    await runSearch()

    root.querySelector('.empty button').click()
    await flushUi()

    expect(toastMock).toHaveBeenCalledWith('提交失败')
  })

  it('shows a search error and retries immediately with the selected filter', async () => {
    apiMock.searchSongs
      .mockRejectedValueOnce(new Error('搜索服务故障'))
      .mockResolvedValueOnce([{ id: 3, title: '重试结果' }])
    clickText('.filter', '纯音频')
    await typeQuery('重试歌')
    await runSearch()
    expect(root.textContent).toContain('搜索失败：搜索服务故障')

    root.querySelector('.error-state .btn').click()
    await flushUi()
    expect(root.textContent).toContain('重试结果')
    expect(apiMock.searchSongs).toHaveBeenLastCalledWith('重试歌', 'AUDIO', 0, expect.any(Object))
  })

  it('loads more results, reports a page failure, and retries that same page', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => ({ id: index + 1, title: `搜索歌${index + 1}` }))
    apiMock.searchSongs
      .mockResolvedValueOnce({ content: firstPage })
      .mockRejectedValueOnce(new Error('第二页暂不可用'))
      .mockResolvedValueOnce([{ id: 51, title: '第二页恢复' }])
    await typeQuery('分页歌')
    await runSearch()
    expect(root.querySelectorAll('.song-row')).toHaveLength(50)

    root.querySelector('.load-more-btn').click()
    await flushUi()
    expect(apiMock.searchSongs).toHaveBeenLastCalledWith('分页歌', '', 1, expect.any(Object))
    expect(root.textContent).toContain('加载更多失败：第二页暂不可用')
    expect(root.querySelector('.load-more-wrap')).toBeNull()

    root.querySelector('.page-error button').click()
    await flushUi()
    expect(apiMock.searchSongs).toHaveBeenLastCalledWith('分页歌', '', 1, expect.any(Object))
    expect(root.querySelectorAll('.song-row')).toHaveLength(51)
    expect(root.textContent).toContain('第二页恢复')
    expect(root.querySelector('.load-more-wrap')).toBeNull()
  })

  it('ignores a duplicate load-more retry while the page request is pending', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => ({ id: index + 1, title: `防重歌${index + 1}` }))
    let finishPage
    apiMock.searchSongs
      .mockResolvedValueOnce({ content: firstPage })
      .mockRejectedValueOnce(new Error('下一页暂不可用'))
      .mockReturnValueOnce(new Promise(resolve => { finishPage = resolve }))
    await typeQuery('分页重试防重')
    await runSearch()

    root.querySelector('.load-more-btn').click()
    await flushUi()
    const retry = root.querySelector('.page-error button')
    retry.click()
    await flushUi()
    retry.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushUi()

    expect(apiMock.searchSongs).toHaveBeenCalledTimes(3)
    expect(apiMock.searchSongs).toHaveBeenLastCalledWith('分页重试防重', '', 1, expect.any(Object))
    finishPage({ content: [{ id: 51, title: '重试成功' }] })
    await flushUi()
    expect(root.querySelectorAll('.song-row')).toHaveLength(51)
    expect(root.textContent).toContain('重试成功')
  })

  it('does not issue duplicate page requests while load-more is still pending', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => ({ id: index + 1, title: `搜索歌${index + 1}` }))
    let finishPage
    apiMock.searchSongs.mockReset()
    apiMock.searchSongs
      .mockResolvedValueOnce({ content: firstPage })
      .mockReturnValueOnce(new Promise(resolve => { finishPage = resolve }))
    await typeQuery('分页防重')
    await runSearch()

    const loadMore = root.querySelector('.load-more-btn')
    loadMore.click()
    loadMore.click()
    await flushUi()
    expect(apiMock.searchSongs).toHaveBeenCalledTimes(2)

    finishPage({ content: [{ id: 51, title: '唯一追加结果' }] })
    await flushUi()
    expect(root.querySelectorAll('.song-row')).toHaveLength(51)
    expect(root.textContent).toContain('唯一追加结果')
  })

  it('does not submit another search while the current retry is pending', async () => {
    let finishRetry
    apiMock.searchSongs
      .mockRejectedValueOnce(new Error('首次失败'))
      .mockReturnValueOnce(new Promise(resolve => { finishRetry = resolve }))
    await typeQuery('防止重复重试')
    await runSearch()

    const retry = root.querySelector('.error-state .btn')
    retry.click()
    retry.click()
    await flushUi()

    expect(apiMock.searchSongs).toHaveBeenCalledTimes(2)
    expect(root.textContent).toContain('搜索中')
    finishRetry([])
    await flushUi()
    expect(root.textContent).toContain('曲库还没有这首歌')
  })

  it('queues a result and reports the server queue position', async () => {
    apiMock.searchSongs.mockResolvedValueOnce([{ id: 6, title: '排队歌曲' }])
    await typeQuery('排队歌曲')
    await runSearch()
    root.querySelector('.song-row').click()
    await flushUi()

    expect(orderMock).toHaveBeenCalledWith(6)
    expect(player.applyControlResponse).toHaveBeenCalledWith({ list: [{}] })
    expect(toastMock).toHaveBeenCalledWith('已加入待播（第 1 位）')
  })

  it('uses queue-specific and generic fallback messages for failed search-result orders', async () => {
    apiMock.searchSongs.mockResolvedValueOnce([{ id: 19, title: '无法点播' }])
    await typeQuery('无法点播')
    await runSearch()

    orderMock.mockRejectedValueOnce(Object.assign(new Error(''), { code: 'SONG_IN_QUEUE' }))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('已在队列中')

    orderMock.mockRejectedValueOnce(new Error(''))
    root.querySelector('.song-row').click()
    await flushUi()
    expect(toastMock).toHaveBeenLastCalledWith('点歌失败')
  })

  it('clears a pending debounced search and restores the idle prompt', async () => {
    await typeQuery('取消搜索')
    expect(root.textContent).toContain('搜索中')
    root.querySelector('.clear').click()
    await flushUi()
    await vi.advanceTimersByTimeAsync(300)
    await flushUi()

    expect(root.querySelector('input').value).toBe('')
    expect(root.textContent).toContain('输入关键词开始搜索')
    expect(apiMock.searchSongs).not.toHaveBeenCalled()
  })

  it('cancels the pending debounce when the page unmounts and supports back navigation', async () => {
    await typeQuery('离开页面')
    app.unmount()
    await vi.advanceTimersByTimeAsync(300)
    await flushUi()
    expect(apiMock.searchSongs).not.toHaveBeenCalled()

    await router.back()
    await vi.advanceTimersByTimeAsync(0)
    await flushUi()
    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  async function typeQuery(value) {
    const input = root.querySelector('input')
    input.value = value
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushUi()
  }

  async function runSearch() {
    await vi.advanceTimersByTimeAsync(300)
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
}
