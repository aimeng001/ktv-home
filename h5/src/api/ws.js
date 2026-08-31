/**
 * WebSocket 客户端模块（详设§4.1）。
 *
 * 功能：连接 /ws 端点，接收服务端广播事件，断线时指数退避自动重连。
 * 心跳：H5 端每 25 秒发送 ping 保活（微信后台挂起后依赖重连恢复）。
 *
 * WebSocket client module (spec §4.1).
 *
 * Features: connects to /ws endpoint, receives server-broadcast events,
 * auto-reconnects with exponential backoff on disconnection.
 * Heartbeat: H5 sends ping every 25s to keep alive (relies on reconnect
 * after WeChat background suspension).
 *
 * @module api/ws
 */

// 指数退避间隔，10 秒封顶
// Exponential backoff intervals, capped at 10 seconds
const BACKOFF = [1000, 2000, 5000, 10000]

/**
 * KTV 点歌台 WebSocket 客户端。
 *
 * 管理一条 /ws 长连接的生命周期：建立连接、收发消息、心跳保活、断线重连。
 * 通过回调函数向外部通知连接状态变化和业务事件。
 *
 * KTV song-request WebSocket client.
 *
 * Manages the lifecycle of a single /ws persistent connection:
 * connect, send/receive messages, heartbeat keep-alive, and reconnection.
 * Notifies external code of connection state changes and business events
 * via callback functions.
 */
export class KtvSocket {
  /**
   * 创建 WebSocket 客户端实例。
   *
   * Creates a WebSocket client instance.
   *
   * @param {Object}   [options]         - 配置选项 / Configuration options
   * @param {Function} [options.onEvent]  - 收到业务事件时的回调（type, payload）/ Callback on business event (type, payload)
   * @param {Function} [options.onStatus] - 连接状态变化时的回调（boolean）/ Callback on connection status change (boolean)
   */
  constructor({ onEvent, onStatus } = {}) {
    this.onEvent = onEvent || (() => {})
    this.onStatus = onStatus || (() => {})
    this.ws = null
    this.retry = 0
    this.pingTimer = null
    this.reconnectTimer = null
    this.closed = false
    this.generation = 0
  }

  /**
   * 建立 WebSocket 连接。
   *
   * 根据当前页面协议自动选择 ws/wss，连接成功后重置重试计数并启动心跳。
   * 连接失败或断开时自动触发指数退避重连（除非主动调用 close()）。
   *
   * Establishes a WebSocket connection.
   *
   * Auto-selects ws/wss based on the current page protocol. Resets retry
   * counter and starts heartbeat on successful connection. Automatically
   * triggers exponential-backoff reconnection on failure/disconnection
   * (unless close() was called explicitly).
   */
  connect() {
    this.closed = false
    this._clearReconnect()
    const generation = ++this.generation
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    // 开发环境由 Vite 代理 /ws 请求；生产环境同源直连
    // Dev: Vite proxies /ws requests; production: same-origin direct connection
    const url = `${proto}://${location.host}/ws?client_type=h5&protocol_version=2&platform=H5`
    const socket = new WebSocket(url)
    this.ws = socket

    socket.onopen = () => {
      if (generation !== this.generation) return
      this.retry = 0
      this.onStatus(true)
      this._startPing()
    }
    socket.onmessage = (e) => {
      if (generation !== this.generation) return
      let msg
      try { msg = JSON.parse(e.data) } catch { return }
      if (msg.type === 'pong') return
      this.onEvent(msg.type, msg.payload)
    }
    socket.onclose = () => {
      if (generation !== this.generation) return
      this._stopPing()
      this.onStatus(false)
      if (!this.closed) this._scheduleReconnect()
    }
    socket.onerror = () => { if (generation === this.generation) socket.close() }
  }

  /**
   * 向服务端发送 JSON 消息。
   *
   * 仅在连接处于 OPEN 状态时发送，避免无效调用。
   *
   * Sends a JSON message to the server.
   *
   * Only sends when the connection is in OPEN state to avoid invalid calls.
   *
   * @param {Object} obj - 要发送的消息对象 / Message object to send
   */
  send(obj) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj))
    }
  }

  /**
   * 主动关闭连接，停止心跳和重连。
   *
   * 标记为主动关闭（closed = true），阻止后续自动重连。
   *
   * Explicitly closes the connection and stops heartbeat/reconnection.
   *
   * Marks the connection as intentionally closed (closed = true) to prevent
   * any subsequent automatic reconnection attempts.
   */
  close() {
    this.closed = true
    this.generation++
    this._clearReconnect()
    this._stopPing()
    const socket = this.ws
    this.ws = null
    socket && socket.close()
  }

  /**
   * 按指数退避策略调度重连。
   *
   * 根据当前重试次数从 BACKOFF 数组中取延迟值，最多 10 秒封顶。
   *
   * Schedules a reconnection attempt using exponential backoff.
   *
   * Picks a delay from the BACKOFF array based on the current retry count,
   * capped at 10 seconds.
   *
   * @private
   */
  _scheduleReconnect() {
    if (this.closed || this.reconnectTimer) return
    const delay = BACKOFF[Math.min(this.retry, BACKOFF.length - 1)]
    this.retry++
    const generation = this.generation
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      if (!this.closed && generation === this.generation) this.connect()
    }, delay)
  }

  _clearReconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  /**
   * 启动心跳定时器，每 25 秒发送一次 ping。
   *
   * 先清除已有定时器防止重复启动。
   *
   * Starts a heartbeat timer that sends a ping every 25 seconds.
   *
   * Clears any existing timer first to prevent duplicate intervals.
   *
   * @private
   */
  _startPing() {
    this._stopPing()
    this.pingTimer = setInterval(() => this.send({ type: 'ping' }), 25000)
  }

  /**
   * 停止心跳定时器。
   *
   * Stops the heartbeat timer.
   *
   * @private
   */
  _stopPing() {
    if (this.pingTimer) { clearInterval(this.pingTimer); this.pingTimer = null }
  }
}
