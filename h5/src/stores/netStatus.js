import { defineStore } from 'pinia'

/**
 * 浏览器在线状态。
 *
 * 只表达「本机到局域网是否可达」（navigator.onLine + online/offline 事件），
 * 不代表服务端可用 —— 服务端可用性由 WebSocket 连接状态（player.connected）表达。
 *
 * Browser network reachability only. Server availability is expressed by the
 * WebSocket connection state (`player.connected`).
 */
export const useNetStatusStore = defineStore('netStatus', {
  state: () => ({
    online: true,
    listening: false
  }),
  actions: {
    /** 开始监听（幂等）。组件挂载时调用。 */
    start() {
      if (this.listening || typeof window === 'undefined') return
      this.listening = true
      this._sync()
      window.addEventListener('online', this._sync)
      window.addEventListener('offline', this._sync)
    },

    /** 停止监听（测试与热更新用）。 */
    stop() {
      if (typeof window === 'undefined') return
      window.removeEventListener('online', this._sync)
      window.removeEventListener('offline', this._sync)
      this.listening = false
    },

    /**
     * 同步状态。
     *
     * 事件优先：jsdom 与部分浏览器在收到 offline 事件后 navigator.onLine 仍是 true，
     * 因此不能只读 navigator.onLine。
     */
    _sync(event) {
      if (event && event.type === 'offline') {
        this.online = false
        return
      }
      if (event && event.type === 'online') {
        this.online = true
        return
      }
      this.online = typeof navigator === 'undefined' ? true : navigator.onLine !== false
    }
  }
})
