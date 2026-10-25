// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import TabBar from './TabBar.vue'
import { usePlayerStore } from '../stores/player'

let app
let root
let player

describe('TabBar', () => {
  beforeEach(async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    player = usePlayerStore(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/queue', name: 'queue', component: { template: '<div />' } },
        { path: '/remote', name: 'remote', component: { template: '<div />' } }
      ]
    })
    await router.push('/')
    await router.isReady()
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(TabBar, { active: 'queue' })
    app.use(pinia)
    app.use(router)
    app.mount(root)
    await nextTick()
  })

  afterEach(() => {
    app?.unmount()
    root?.remove()
  })

  it('renders all three routes and marks only the selected tab active', () => {
    const tabs = [...root.querySelectorAll('a.tab')]

    expect(tabs).toHaveLength(3)
    expect(tabs.map(tab => tab.textContent.trim())).toEqual(['点歌', '已点', '遥控'])
    expect(tabs.map(tab => tab.classList.contains('on'))).toEqual([false, true, false])
    expect(root.querySelector('.badge')).toBeNull()
  })

  it('shows the queue count only when there are queued songs', async () => {
    player.queue = [{ song: { id: 1 } }, { song: { id: 2 } }]
    await nextTick()

    expect(root.querySelector('.badge').textContent).toBe('2')
  })
})
