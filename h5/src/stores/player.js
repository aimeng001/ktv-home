import { defineStore } from 'pinia'
import { KtvSocket } from '../api/ws'
import { useUserStore } from './user'

/**
 * 播放器/队列全局状态（详设§4.1：服务端为唯一事实源，此处镜像广播状态）。
 *
 * Player/queue global state (Design Spec §4.1: the server is the single source
 * of truth; this store mirrors the broadcasted state).
 */
export const usePlayerStore = defineStore('player', {
  state: () => ({
    connected: false,
    socket: null,
    nowPlaying: null,      // 当前播放 | {queueId, song, orderedByNick}
    state: 'idle',         // 播放状态 | idle / playing / paused
    positionMs: 0,
    volume: 60,
    muted: false,
    vocalMode: 'accompaniment', // 伴唱模式 | original / accompaniment
    audioLayout: {
      layout: 'NORMAL_STEREO',
      originalTrackIndex: null,
      accompanimentTrackIndex: null,
      originalChannel: 'LEFT',
      accompanimentChannel: 'RIGHT'
    },
    queue: [],             // 点歌队列 | [{queueId, song, orderedBy, orderedByNick, status}]
    tvOnline: null,        // TV 状态在首次服务端快照前未知 | Unknown until the first server snapshot
    connectedPhones: 0,
    lastEffect: null,
    historyRevision: 0
  }),
  getters: {
    queueCount: (s) => s.queue.length,
    isPlaying: (s) => s.state === 'playing'
  },
  actions: {
    /**
     * 建立 WebSocket 连接（进入 App 后调用一次）。
     *
     * Establish WebSocket connection (called once after entering the App).
     */
    connect() {
      if (this.socket) return
      this.socket = new KtvSocket({
        onEvent: (type, payload) => this.handleEvent(type, payload),
        onStatus: (ok) => { this.connected = ok }
      })
      this.socket.connect()
    },

    /**
     * 断开 WebSocket 连接。
     *
     * Disconnect the WebSocket connection.
     */
    disconnect() {
      if (this.socket) { this.socket.close(); this.socket = null }
    },

    /**
     * 处理 WebSocket 事件。TV 端才上报 progress；H5 仅接收。此处保留发送能力供调试。
     *
     * Handle WebSocket events. Only the TV side reports progress; H5 only receives.
     * The sending capability is retained here for debugging.
     *
     * @param {string} type - 事件类型 / event type
     * @param {object} payload - 事件负载 / event payload
     */
    handleEvent(type, payload) {
      switch (type) {
        case 'sync_full':
          this.applySnapshot(payload)
          break
        case 'queue_updated':
        case 'now_playing':
        case 'player_state':
        case 'playback_restarted':
        case 'playback_seeked':
        case 'volume_changed':
        case 'vocal_changed':
          this.applySnapshot(payload)
          break
        case 'progress':
          if (payload && typeof payload.position_ms === 'number') {
            this.positionMs = payload.position_ms
          }
          break
        case 'effect_play':
          this.lastEffect = payload?.effect_id ?? null
          break
        case 'history_updated':
          this.historyRevision += 1
          break
        default:
          break
      }
    },

    /**
     * 应用服务端快照，同步所有播放状态（QueueSnapshot 结构）。
     *
     * Apply server snapshot to sync all playback state (QueueSnapshot structure).
     *
     * @param {object} snap - 服务端快照对象 / server snapshot object
     */
    applySnapshot(snap) {
      if (!snap) return
      // now_playing / player_state 等事件 payload 也是完整 snapshot
      // Events like now_playing / player_state also carry a full snapshot payload
      const playing = snap.playing ?? null
      this.nowPlaying = playing
        ? { queueId: playing.queueId, song: playing.song, orderedByNick: playing.orderedByNick }
        : null
      this.state = snap.state ?? 'idle'
      if (typeof snap.positionMs === 'number') this.positionMs = Math.max(0, snap.positionMs)
      this.volume = snap.volume ?? this.volume
      this.muted = snap.muted ?? false
      this.vocalMode = snap.vocalMode ?? this.vocalMode
      this.audioLayout = snap.audioLayout ?? this.audioLayout
      this.queue = snap.list ?? []
      if (typeof snap.tvOnline === 'boolean') this.tvOnline = snap.tvOnline
      this.connectedPhones = snap.connectedPhones ?? 0
    }
  }
})
