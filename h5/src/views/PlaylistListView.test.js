// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

const { apiMock } = vi.hoisted(() => ({ apiMock: { playlists: vi.fn() } }))

vi.mock('../api/client', () => ({ default: apiMock }))
vi.mock('../components/TabBar.vue', () => ({ default: { template: '<nav />' } }))

import PlaylistListView from './PlaylistListView.vue'

describe('PlaylistListView', () => {
  let app
  let root
  let router

  beforeEach(async () => {
    vi.clearAllMocks()
    apiMock.playlists.mockResolvedValue([])
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/playlists', name: 'playlists', component: PlaylistListView },
        { path: '/playlists/:id', name: 'playlist-detail', component: { template: '<div />' } }
      ]
    })
    await router.push('/playlists')
    await router.isReady()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(router)
    app.mount(root)
    await flushUi()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('shows loading until the public playlist request settles', async () => {
    let finishLoad
    apiMock.playlists.mockReturnValueOnce(new Promise(resolve => { finishLoad = resolve }))
    await remount()
    expect(root.textContent).toContain('加载中')

    finishLoad([{ id: 31, name: '测试歌单', theme: '家庭' }])
    await flushUi()
    expect(root.textContent).toContain('测试歌单')
  })

  it('distinguishes a successful empty list from a load error and retries failures', async () => {
    expect(root.textContent).toContain('暂无公开歌单')

    apiMock.playlists.mockRejectedValueOnce({})
    await remount()
    expect(root.textContent).toContain('歌单加载失败：歌单加载失败，请稍后重试')

    apiMock.playlists.mockResolvedValueOnce([{ id: 32, name: '重试成功' }])
    root.querySelector('.retry').click()
    await flushUi()
    expect(root.textContent).toContain('重试成功')
  })

  it('renders theme covers, preview fallbacks, AI labels, descriptions, and detail links', async () => {
    apiMock.playlists.mockResolvedValueOnce([
      { id: 1, name: '儿童歌单', theme: '儿童经典', preview: [{ title: '小星星' }], songCount: 1 },
      { id: 2, name: '摇滚歌单', theme: '热血摇滚', preview: [{ title: '追梦赤子心' }], songCount: 1 },
      { id: 3, name: '浪漫歌单', theme: '浪漫情歌', preview: [{ title: '后来' }], songCount: 1 },
      { id: 4, name: '怀旧歌单', theme: '经典年代', preview: [{ title: '朋友' }], songCount: 1 },
      { id: 5, name: '合唱歌单', theme: '合唱', preview: [], songCount: 0, aiGenerated: true },
      { id: 6, name: '自定义封面', theme: '其他', coverUrl: '/covers/custom.jpg', description: '', preview: [null], songCount: 0 },
      { id: 7, name: '默认歌单', description: '', preview: undefined, songCount: 0 }
    ])
    await remount()

    expect(root.querySelectorAll('.card')).toHaveLength(7)
    expect([...root.querySelectorAll('.cover > span')].map(span => span.textContent))
      .toEqual(['🧸', '🎸', '💞', '📻', '👥', '🎶'])
    expect(root.querySelectorAll('.cover em')).toHaveLength(1)
    expect(root.textContent).toContain('AI')
    expect(root.textContent).toContain('小星星')
    expect(root.textContent).toContain('等待添加歌曲')
    expect(root.textContent).toContain('家庭欢唱精选')
    expect(root.querySelector('.card:nth-of-type(6) .cover').style.backgroundImage).toContain('custom.jpg')
    expect(root.querySelector('.card').getAttribute('href')).toContain('/playlists/1')
  })

  async function remount() {
    app?.unmount()
    root?.remove()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(RouterView) })
    app.use(router)
    app.mount(root)
    await flushUi()
  }
})

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
