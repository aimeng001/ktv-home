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
const MAX_MESSAGE_BYTES = 1_048_576
const MAX_QUEUE_ENTRIES = 1000
const MAX_SYNC_CHUNKS = 64
const SNAPSHOT_EVENTS = new Set([
  'sync_full',
  'queue_updated',
  'now_playing',
  'player_state',
  'playback_restarted',
  'playback_seeked',
  'volume_changed',
  'vocal_changed',
])

// 断线期间最多缓存多少条播放结果上报；同一 queueId 只保留一条。
// Max number of playback reports buffered while offline; one entry per queueId.
const MAX_PENDING_REPORTS = 20
const PONG_TIMEOUT_MS = 8_000

function utf8ByteCountAtMost(value, limit) {
  let bytes = 0
  for (let index = 0; index < value.length;) {
    const code = value.codePointAt(index)
    bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4
    if (bytes > limit) return null
    index += code > 0xffff ? 2 : 1
  }
  return bytes
}

export class SnapshotChunkAssembler {
  constructor({ maxChunks = MAX_SYNC_CHUNKS, maxEntries = MAX_QUEUE_ENTRIES } = {}) {
    this.maxChunks = maxChunks
    this.maxEntries = maxEntries
    this.reset()
  }

  accept(chunk, wireBytes = 0) {
    if (!chunk || typeof chunk !== 'object'
      || wireBytes > MAX_MESSAGE_BYTES
      || !SNAPSHOT_EVENTS.has(chunk.eventType)
      || typeof chunk.syncId !== 'string' || !chunk.syncId
      || !Number.isInteger(chunk.total) || chunk.total < 1 || chunk.total > this.maxChunks
      || !Number.isInteger(chunk.index) || chunk.index < 0 || chunk.index >= chunk.total
      || chunk.last !== (chunk.index === chunk.total - 1)
      || !Array.isArray(chunk.entries) || chunk.entries.length > this.maxEntries
      || !chunk.header || typeof chunk.header !== 'object') {
      this.reset()
      return null
    }

    if (this.syncId === null) {
      this.syncId = chunk.syncId
      this.eventType = chunk.eventType
      this.total = chunk.total
      this.header = chunk.header
    } else if (this.syncId !== chunk.syncId) {
      this.reset()
      this.syncId = chunk.syncId
      this.eventType = chunk.eventType
      this.total = chunk.total
      this.header = chunk.header
    } else if (this.eventType !== chunk.eventType
      || this.total !== chunk.total
      || JSON.stringify(this.header) !== JSON.stringify(chunk.header)) {
      this.reset()
      return null
    }

    if (this.chunks.has(chunk.index)) {
      this.reset()
      return null
    }
    this.entryCount += chunk.entries.length
    if (this.entryCount > this.maxEntries) {
      this.reset()
      return null
    }
    this.chunks.set(chunk.index, chunk.entries)
    if (this.chunks.size !== this.total) return null

    const entries = []
    for (let index = 0; index < this.total; index++) {
      if (!this.chunks.has(index)) {
        this.reset()
        return null
      }
      entries.push(...this.chunks.get(index))
    }
    const snapshot = { ...this.header, list: entries }
    const result = { eventType: this.eventType, snapshot }
    this.reset()
    return result
  }

  reset() {
    this.syncId = null
    this.eventType = null
    this.total = 0
    this.header = null
    this.chunks = new Map()
    this.entryCount = 0
  }
}

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
  constructor({ onEvent, onStatus, getPingPayload, getClientToken } = {}) {
    this.onEvent = onEvent || (() => {})
    this.onStatus = onStatus || (() => {})
    this.getPingPayload = getPingPayload || null
    this.getClientToken = getClientToken || null
    this.ws = null
    this.retry = 0
    this.pingTimer = null
    this.pongTimer = null
    this.reconnectTimer = null
    this.closed = false
    this.generation = 0
    this.pendingReports = []
    this.inFlightReports = []
    this.snapshotChunks = new SnapshotChunkAssembler()
    this._eventsBound = false
    this._onVisibilityChange = null
    this._onOnline = null
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
    this._bindWindowEvents()
    this.snapshotChunks.reset()
    this._clearReconnect()
    this._clearPongTimeout()
    const generation = ++this.generation
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    // 开发环境由 Vite 代理 /ws 请求；生产环境同源直连
    // Dev: Vite proxies /ws requests; production: same-origin direct connection
    const token = this.getClientToken?.()
    const tokenQuery = token ? `&client_token=${encodeURIComponent(token)}` : ''
    const url = `${proto}://${location.host}/ws?client_type=h5&protocol_version=2&platform=H5${tokenQuery}`
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
      this._clearPongTimeout()
      const wireBytes = utf8ByteCountAtMost(e.data, MAX_MESSAGE_BYTES)
      if (wireBytes === null) {
        socket.close()
        return
      }
      let msg
      try { msg = JSON.parse(e.data) } catch { return }
      if (msg.type === 'pong') return
      if (msg.type === 'snapshot_chunk') {
        const assembled = this.snapshotChunks.accept(msg.payload, wireBytes)
        if (assembled) this.onEvent(assembled.eventType, assembled.snapshot)
        return
      }
      if (SNAPSHOT_EVENTS.has(msg.type)) this.snapshotChunks.reset()
      this.onEvent(msg.type, msg.payload)
    }
    socket.onclose = () => {
      if (generation !== this.generation) return
      this._stopPing()
      this._clearPongTimeout()
      this.onStatus(false)
      if (!this.closed) this._scheduleReconnect()
    }
    socket.onerror = () => { if (generation === this.generation) socket.close() }
  }

  /**
   * 向服务端发送 JSON 消息。
   *
   * 仅在连接处于 OPEN 状态时发送，避免无效调用。
   * 返回是否真的发出去了 —— 调用方（如播放结果上报）据此决定要不要排队重放。
   *
   * Sends a JSON message to the server.
   *
   * Only sends when the connection is in OPEN state to avoid invalid calls.
   * Returns whether the frame actually went out, so callers such as playback
   * reports can decide whether to buffer it for replay.
   *
   * @param {Object} obj - 要发送的消息对象 / Message object to send
   * @returns {boolean} 已发送为 true / true when the frame was handed to the socket
   */
  send(obj) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj))
      return true
    }
    return false
  }

  /**
   * 缓存一条待重放的播放结果上报。
   *
   * 只存「要做什么」（kind/queueId），不存消息体：上报载荷里带会话级 generation，
   * 重连后旧 generation 一定会被服务端判为 STALE 丢弃。
   *
   * Buffers one playback report for replay.
   *
   * Stores intent only (kind/queueId), never the wire message: report payloads carry a
   * session-scoped generation that the server rejects as STALE after a reconnect.
   *
   * @param {{kind: string, queueId: number, fileId?: number, message?: string}} descriptor 上报描述 / report descriptor
   */
  queueReport(descriptor) {
    if (!descriptor || !descriptor.kind) return
    const key = `${descriptor.kind}:${descriptor.queueId}`
    if (this.pendingReports.some((item) => `${item.kind}:${item.queueId}` === key)) return
    if (this.pendingReports.length >= MAX_PENDING_REPORTS) this.pendingReports.shift()
    this.pendingReports.push({ ...descriptor })
  }

  /** Tracks an already-sent report until the server confirms via playback_report_ack. */
  trackInFlightReport(descriptor) {
    if (!descriptor || !descriptor.kind) return
    const key = `${descriptor.kind}:${descriptor.queueId}`
    if (this.inFlightReports.some((item) => `${item.kind}:${item.queueId}` === key)) return
    if (this.inFlightReports.length >= MAX_PENDING_REPORTS) this.inFlightReports.shift()
    this.inFlightReports.push({ ...descriptor })
  }

  /** Marks a playback report acknowledged by the server so reconnect will not resend it. */
  acknowledgeReport(kind, queueId) {
    if (queueId == null) return
    const match = (item) => item.queueId === queueId && (!kind || item.kind === kind)
    this.pendingReports = this.pendingReports.filter((item) => !match(item))
    this.inFlightReports = this.inFlightReports.filter((item) => !match(item))
  }

  /** Promotes unacknowledged sent reports back into pendingReports on socket drop. */
  requeueUnackedReports() {
    if (this.inFlightReports.length === 0) return
    for (const item of this.inFlightReports) {
      this.queueReport(item)
    }
    this.inFlightReports = []
  }

  /** 丢弃全部待重放条目（角色降级为 STANDBY 时调用）。 */
  clearPendingReports() {
    this.pendingReports = []
    this.inFlightReports = []
  }

  /** 待重放条目数量（测试与诊断用）。 */
  get pendingReportCount() {
    return this.pendingReports.length
  }

  /**
   * 重放断线期间积压的上报。
   *
   * 先取出并清空队列再逐条回调，这样回调内部再次 send 失败时只会重新入队一次，
   * 不会出现「重放 → 入队 → 再重放」的自我循环。
   *
   * Replays reports buffered while offline.
   *
   * Drains the queue before invoking the callback so a failing send inside the
   * callback only re-queues once instead of looping forever.
   *
   * @param {Function} replay - 单条重放回调，入参为描述对象 / callback receiving one descriptor
   * @returns {number} 实际重放条数 / how many descriptors were replayed
   */
  replayPending(replay) {
    if (typeof replay !== 'function' || this.pendingReports.length === 0) return 0
    const queued = this.pendingReports
    this.pendingReports = []
    let replayed = 0
    for (const item of queued) {
      try {
        replay(item)
        replayed++
      } catch {
        // 单条失败不影响其余条目；发送侧会自行重新入队
        // One failure must not block the rest; the sender re-queues by itself.
      }
    }
    return replayed
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
    this._clearPongTimeout()
    this._unbindWindowEvents()
    this.snapshotChunks.reset()
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

  _bindWindowEvents() {
    if (typeof window === 'undefined' || typeof document === 'undefined' || this._eventsBound) return
    this._onVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        this.resumeIfNeeded()
      }
    }
    this._onOnline = () => {
      this.resumeIfNeeded()
    }
    document.addEventListener('visibilitychange', this._onVisibilityChange)
    window.addEventListener('online', this._onOnline)
    this._eventsBound = true
  }

  _unbindWindowEvents() {
    if (typeof window === 'undefined' || !this._eventsBound) return
    if (this._onVisibilityChange) {
      document.removeEventListener('visibilitychange', this._onVisibilityChange)
      this._onVisibilityChange = null
    }
    if (this._onOnline) {
      window.removeEventListener('online', this._onOnline)
      this._onOnline = null
    }
    this._eventsBound = false
  }

  /**
   * 页面切回前台或网络恢复时主动唤醒/探测连接。
   */
  resumeIfNeeded() {
    if (this.closed) return
    if (!this.ws || this.ws.readyState === WebSocket.CLOSED || this.ws.readyState === WebSocket.CLOSING) {
      this.retry = 0
      this._clearReconnect()
      this.connect()
    } else if (this.ws.readyState === WebSocket.OPEN) {
      const payload = this.getPingPayload?.()
      if (this.send(payload ? { type: 'ping', payload } : { type: 'ping' })) {
        this._armPongTimeout(this.ws, this.generation)
      }
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
    this.pingTimer = setInterval(() => {
      const payload = this.getPingPayload?.()
      if (this.send(payload ? { type: 'ping', payload } : { type: 'ping' })) {
        this._armPongTimeout(this.ws, this.generation)
      }
    }, 25000)
  }

  _armPongTimeout(socket, generation) {
    this._clearPongTimeout()
    if (!socket) return
    this.pongTimer = setTimeout(() => {
      this.pongTimer = null
      if (!this.closed && generation === this.generation && this.ws === socket) {
        socket.close()
      }
    }, PONG_TIMEOUT_MS)
  }

  _clearPongTimeout() {
    if (this.pongTimer) {
      clearTimeout(this.pongTimer)
      this.pongTimer = null
    }
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
