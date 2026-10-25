// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    standbyContent: vi.fn(),
    registerUser: vi.fn()
  }
}))

vi.mock('./api/client', () => ({ default: apiMock }))

describe('main entry point', () => {
  beforeEach(() => {
    vi.resetModules()
    localStorage.clear()
    document.body.innerHTML = '<div id="app"></div>'
    apiMock.standbyContent.mockResolvedValue({
      welcomeText: '入口已挂载',
      subtitle: '本地模拟服务'
    })
    apiMock.registerUser.mockResolvedValue({ id: 7, nickname: '模拟用户' })
  })

  afterEach(() => {
    document.body.innerHTML = ''
    localStorage.clear()
  })

  it('mounts the root app on #app with the configured router and Pinia', async () => {
    await import('./main.js')
    const { default: router } = await import('./router')
    await router.isReady()
    await flushUi()

    expect(document.querySelector('#app .title')?.textContent).toBe('入口已挂载')
    expect(document.querySelector('#app .room')?.textContent).toContain('本地模拟服务')
    expect(apiMock.standbyContent).toHaveBeenCalledOnce()
    expect(apiMock.registerUser).not.toHaveBeenCalled()
  })

  async function flushUi() {
    await Promise.resolve()
    await Promise.resolve()
    await new Promise(resolve => setTimeout(resolve, 0))
    await Promise.resolve()
  }
})
