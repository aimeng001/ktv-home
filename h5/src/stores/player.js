import { defineStore } from 'pinia'
import { KtvSocket } from '../api/ws'
import { useUserStore } from './user'

let nextToastSeq = 0

function clampPitch(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return 0
  return Math.max(-6, Math.min(6, Math.round(num)))
}

/**
 * 播放器/队列全局状态（详设§4.1：服务端为唯一事实源，此处镜像广播状态）。
 * 扩展支持双模式自动切换：
 * - 检测到物理 TV 在线（tvOnline === true）：自动作 TV 控制台（同步控制 + 静音小窗预览）
 * - 未检测到物理 TV 在线（tvOnline === false）：自动协商唯一 Web 主播放端（WEB_HOST），
 *   其余同时接入的网页客户端作该主屏的遥控台（WEB_REMOTE），支持顶栏手动切换接管。
 */
export const usePlayerStore = defineStore('player', {
  state: () => ({
    connected: false,
    socket: null,
    nowPlaying: null,      // 当前播放 | {queueId, song, orderedByNick}
    state: 'idle',         // 播放状态 | idle / playing / paused
    positionMs: 0,
    seekSequence: 0,
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
    stateRevision: 0,
    playback: { kind: 'NONE', status: 'IDLE' },
    roomHost: { claimed: false, hostUserId: null, hostNickname: null, revision: 0, isHost: false },
    lastEffect: null,
    lastEffectSeq: 0,
    lastToast: null,
    historyRevision: 0,
    webPlayerRole: 'STANDBY',
    webPlayerGeneration: 0,
    webPlayerActive: false,
    webPlayerHolderToken: null,
    modeOverride: 'auto', // 'auto' | 'console' | 'host'
    pitchSemitones: 0,
    audioBlockedByAutoplay: false
  }),
  getters: {
    queueCount: (s) => s.queue.length,
    isPlaying: (s) => s.state === 'playing',
    orderedSongIds: (s) => new Set([
      ...(s.nowPlaying?.song?.id != null ? [Number(s.nowPlaying.song.id)] : []),
      ...(s.queue || []).map(item => Number(item?.song?.id)).filter(Number.isFinite)
    ]),
    isTvConsole: (s) => s.tvOnline === true && s.modeOverride !== 'host',
    isWebHost: (s) => s.tvOnline !== true && s.webPlayerRole === 'ACTIVE' && s.modeOverride !== 'console',
    effectiveMode: (s) => {
      if (s.tvOnline === true && s.modeOverride !== 'host') return 'TV_CONSOLE'
      if (s.tvOnline !== true && s.webPlayerRole === 'ACTIVE' && s.modeOverride !== 'console') return 'WEB_HOST'
      return 'WEB_REMOTE'
    }
  },
  actions: {
    /**
     * 建立 WebSocket 连接（进入 App 后调用一次）。
     */
    connect() {
      if (this.socket) return
      this.socket = new KtvSocket({
        getClientToken: () => {
          try { return useUserStore().clientToken || '' } catch { return '' }
        },
        getPingPayload: () => (
          this.isWebHost && this.webPlayerGeneration > 0
            ? { generation: this.webPlayerGeneration }
            : null
        ),
        onEvent: (type, payload) => this.handleEvent(type, payload),
        onStatus: (ok) => {
          this.connected = ok
          if (!ok) {
            this.webPlayerRole = 'STANDBY'
            this.webPlayerGeneration = 0
          } else {
            this.syncWebPlayerRegistration()
          }
        }
      })
      this.socket.connect()
    },

    /**
     * 断开 WebSocket 连接。
     */
    disconnect() {
      this.webPlayerRole = 'STANDBY'
      this.webPlayerGeneration = 0
      this.webPlayerActive = false
      this.webPlayerHolderToken = null
      if (this.socket) { this.socket.close(); this.socket = null }
    },

    /**
     * 处理 WebSocket 事件。
     */
    handleEvent(type, payload) {
      switch (type) {
        case 'sync_full':
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
            const hasQueueId = Object.prototype.hasOwnProperty.call(payload, 'queue_id')
            if (hasQueueId) {
              if (!Number.isSafeInteger(payload.queue_id) || payload.queue_id <= 0
                || payload.queue_id !== this.nowPlaying?.queueId) break
            } else if (!this.nowPlaying) {
              // Older servers omitted queue_id; keep their position mirror only while a song is active.
              break
            }
            this.positionMs = Math.max(0, payload.position_ms)
          }
          break
        case 'effect_play':
          this.lastEffect = payload?.effect_id ?? null
          this.lastEffectSeq += 1
          break
        case 'toast':
          if (typeof payload?.text === 'string' && payload.text.trim()) {
            this.lastToast = { id: ++nextToastSeq, text: payload.text.trim() }
          }
          break
        case 'history_updated':
          this.historyRevision += 1
          break
        case 'player_role':
          if (payload && typeof payload === 'object') {
            const role = payload.role === 'ACTIVE' && this.tvOnline !== true ? 'ACTIVE' : 'STANDBY'
            this.webPlayerRole = role
            this.webPlayerGeneration = role === 'ACTIVE' ? (Number(payload.generation) || 0) : 0
            if (role === 'STANDBY' && this.modeOverride === 'host') {
              this.modeOverride = 'auto'
            }
          }
          break
        case 'web_player_state':
          if (payload && typeof payload === 'object') {
            this.webPlayerActive = Boolean(payload.active)
            this.webPlayerHolderToken = payload.holderToken || null
          }
          break
        case 'room_host_changed':
          if (payload && typeof payload === 'object') {
            const incomingRevision = Number(payload.revision)
            const currentRevision = Number(this.roomHost.revision) || 0
            if (Number.isFinite(incomingRevision) ? incomingRevision >= currentRevision : currentRevision === 0) {
              const user = useUserStore()
              const isHost = Boolean(payload?.claimed && Number(payload?.hostUserId) > 0 && Number(payload.hostUserId) === Number(user?.serverUserId))
              this.roomHost = {
                ...this.roomHost,
                ...payload,
                isHost,
                revision: Number.isFinite(incomingRevision) ? incomingRevision : currentRevision
              }
            }
          }
          break
        default:
          break
      }
    },

    /**
     * 应用服务端快照，同步所有播放状态（QueueSnapshot 结构）。
     */
    applySnapshot(snap) {
      if (!snap) return
      const incomingRevision = Number(snap.stateRevision)
      const currentRevision = Number(this.stateRevision) || 0
      const hasRevision = Number.isSafeInteger(incomingRevision) && incomingRevision > 0
      if (hasRevision) {
        if (currentRevision > 0 && incomingRevision < currentRevision) return false
        this.stateRevision = incomingRevision
      } else if (currentRevision > 0) {
        return false
      }
      const prevTvOnline = this.tvOnline
      const prevSongId = this.nowPlaying?.song?.id ?? null
      const playing = snap.playing ?? null
      this.nowPlaying = playing
        ? { queueId: playing.queueId, song: playing.song, orderedByNick: playing.orderedByNick }
        : null
      if ((playing?.song?.id ?? null) !== prevSongId) {
        this.pitchSemitones = 0
      }
      this.state = snap.state ?? 'idle'
      if (typeof snap.positionMs === 'number') this.positionMs = Math.max(0, snap.positionMs)
      if (typeof snap.seekSequence === 'number') this.seekSequence = snap.seekSequence
      this.volume = snap.volume ?? this.volume
      this.muted = snap.muted ?? false
      this.vocalMode = snap.vocalMode ?? this.vocalMode
      this.audioLayout = snap.audioLayout ?? this.audioLayout
      this.queue = snap.list ?? []
      if (typeof snap.tvOnline === 'boolean') this.tvOnline = snap.tvOnline
      this.connectedPhones = snap.connectedPhones ?? 0
      this.playback = snap.playback ?? { kind: 'NONE', status: 'IDLE' }

      if (this.tvOnline === true) {
        this.webPlayerRole = 'STANDBY'
        this.webPlayerGeneration = 0
        this.webPlayerActive = false
        this.webPlayerHolderToken = null
        if (this.modeOverride === 'host') {
          this.modeOverride = 'auto'
        }
      } else if (this.tvOnline === false && prevTvOnline !== false && this.connected) {
        this.syncWebPlayerRegistration()
      }
      return true
    },

    /** Accept both the current direct QueueSnapshot response and legacy {snapshot} wrappers. */
    applyControlResponse(response) {
      const snapshot = response?.snapshot ?? response
      if (snapshot && Array.isArray(snapshot.list)) this.applySnapshot(snapshot)
    },

    syncWebPlayerRegistration() {
      if (!this.socket || !this.connected) return
      if (this.tvOnline === true) {
        if (this.webPlayerRole === 'ACTIVE') {
          this.releaseWebPlayer()
        }
        return
      }
      if (this.tvOnline !== false) return
      if (this.modeOverride === 'console') {
        this.releaseWebPlayer()
        return
      }
      if (this.modeOverride === 'host') {
        this.claimWebPlayer(true)
        return
      }
      if (this.webPlayerRole !== 'ACTIVE') {
        this.claimWebPlayer(false)
      }
    },

    claimWebPlayer(force = false) {
      if (this.tvOnline === true && !force) return
      this.socket?.send({ type: 'web_player_claim', payload: { force: Boolean(force) } })
    },

    releaseWebPlayer() {
      this.webPlayerRole = 'STANDBY'
      this.webPlayerGeneration = 0
      this.socket?.send({ type: 'web_player_release' })
    },

    setModeOverride(mode) {
      if (!['auto', 'console', 'host'].includes(mode)) return
      if (mode === 'host' && this.tvOnline === true) {
        this.modeOverride = 'auto'
        return
      }
      this.modeOverride = mode
      this.syncWebPlayerRegistration()
    },

    reportProgress(queueId, positionMs) {
      if (!this.isWebHost || !this.webPlayerGeneration) return
      if (!Number.isInteger(queueId) || queueId <= 0) return
      const pos = Math.max(0, Math.round(Number(positionMs) || 0))
      this.positionMs = pos
      this.socket?.send({
        type: 'progress',
        payload: { queue_id: queueId, position_ms: pos, generation: this.webPlayerGeneration }
      })
    },

    reportFinished(queueId) {
      if (!this.isWebHost || !this.webPlayerGeneration) return
      if (!Number.isInteger(queueId) || queueId <= 0) return
      this.socket?.send({
        type: 'finished',
        payload: { queue_id: queueId, generation: this.webPlayerGeneration }
      })
    },

    reportPlayError(fileId, queueId, message) {
      if (!this.isWebHost || !this.webPlayerGeneration) return
      if (!Number.isInteger(queueId) || queueId <= 0) return
      this.socket?.send({
        type: 'play_error',
        payload: {
          ...(fileId != null ? { file_id: fileId } : {}),
          queue_id: queueId,
          message: String(message || '媒体播放失败'),
          generation: this.webPlayerGeneration
        }
      })
    },

    adjustPitch(delta) {
      this.pitchSemitones = clampPitch(this.pitchSemitones + (Number(delta) || 0))
      return this.pitchSemitones
    },

    setPitch(value) {
      this.pitchSemitones = clampPitch(value)
      return this.pitchSemitones
    },

    resetPitch() {
      this.pitchSemitones = 0
    }
  }
})
