// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { apiMock, makeControlsMock } = vi.hoisted(() => ({
  apiMock: {
    registerUser: vi.fn(),
    roomHostStatus: vi.fn(),
    recentHistory: vi.fn(),
    favoriteIds: vi.fn(),
    favorites: vi.fn()
  },
  makeControlsMock: vi.fn(() => ({
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
    setVocal: vi.fn(),
    effect: vi.fn()
  }))
}))

vi.mock('./api/client', () => ({
  default: apiMock,
  makeControls: makeControlsMock
}))

describe('main entry point', () => {
  beforeEach(() => {
    vi.resetModules()
    localStorage.clear()
    document.body.innerHTML = '<div id="app"></div>'
    apiMock.registerUser.mockResolvedValue({ id: 7, nickname: '模拟用户' })
    apiMock.roomHostStatus.mockResolvedValue({ claimed: false, isHost: false, revision: 1 })
    apiMock.recentHistory.mockResolvedValue([])
    apiMock.favoriteIds.mockResolvedValue([])
    apiMock.favorites.mockResolvedValue([])
  })

  afterEach(() => {
    document.body.innerHTML = ''
    localStorage.clear()
  })

  it('mounts the root app on #app straight into the KTV kiosk dashboard', async () => {
    await import('./main.js')
    const { default: router } = await import('./router')
    await router.isReady()
    await flushUi()

    expect(document.querySelector('#app .ktv-kiosk-shell')).not.toBeNull()
    expect(document.querySelectorAll('#app .bento-tile')).toHaveLength(10)
    expect(document.querySelector('#app .ktv-bottom-dock')).not.toBeNull()
    expect(apiMock.registerUser).toHaveBeenCalledOnce()
  })

  async function flushUi() {
    await Promise.resolve()
    await Promise.resolve()
    await new Promise(resolve => setTimeout(resolve, 0))
    await Promise.resolve()
  }
})
