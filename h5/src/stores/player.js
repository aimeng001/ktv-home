import { markRaw } from 'vue'
import { defineStore } from 'pinia'
import { KtvSocket } from '../api/ws'
import { useUserStore } from './user'

let nextToastSeq = 0

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
    pitchSemitones: 0,     // 伴奏升降调半音数 | -6 到 +6，0 为原调
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
    wasWebHostBeforeDisconnect: false,
    modeOverride: 'auto', // 'auto' | 'console' | 'host'
    audioBlockedByAutoplay: false,
    audioUnlockFailed: false
  }),
  getters: {
    queueCount: (s) => s.queue.length,
    isPlaying: (s) => s.state === 'playing',
    isTvConsole: (s) => s.tvOnline === true && s.modeOverride !== 'host',
    isWebHost: (s) => s.tvOnline !== true && s.webPlayerRole === 'ACTIVE' && s.modeOverride !== 'console',
    isSilentConsole: (s) => {
      if (s.tvOnline === true && s.modeOverride !== 'host') return true
      if (s.modeOverride === 'console') return true
      if (s.webPlayerActive && s.webPlayerRole !== 'ACTIVE') return true
      return false
    },
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
            this.wasWebHostBeforeDisconnect = Boolean(
              this.wasWebHostBeforeDisconnect ||
              (this.tvOnline !== true && this.webPlayerRole === 'ACTIVE' && this.modeOverride !== 'console')
            )
            this.socket?.requeueUnackedReports?.()
            this.stateRevision = 0
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
      this.wasWebHostBeforeDisconnect = false
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
            if (typeof payload.seek_sequence === 'number' && payload.seek_sequence < this.seekSequence) {
              break
            }
            if (typeof payload.seek_sequence === 'number') {
              this.seekSequence = payload.seek_sequence
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
            this.wasWebHostBeforeDisconnect = false
            this.webPlayerRole = role
            this.webPlayerGeneration = role === 'ACTIVE' ? (Number(payload.generation) || 0) : 0
            if (role === 'STANDBY' && this.modeOverride === 'host') {
              this.modeOverride = 'auto'
            }
            // 拿到新 generation 后才能重放：载荷必须用当前 generation 重建（见 replayPendingReports）
            if (role === 'ACTIVE' && this.webPlayerGeneration > 0) this.replayPendingReports()
            else this.socket?.clearPendingReports?.()
          }
          break
        case 'web_player_state':
          if (payload && typeof payload === 'object') {
            this.webPlayerActive = Boolean(payload.active)
            this.webPlayerHolderToken = payload.holderToken || null
          }
          break
        case 'playback_report_ack':
          if (payload && typeof payload === 'object') {
            this.socket?.acknowledgeReport?.(payload.report_type, payload.queue_id)
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
      const prevQueueId = this.nowPlaying?.queueId
      const playing = snap.playing ?? null
      this.nowPlaying = playing
        ? {
            queueId: playing.queueId,
            song: playing.song && typeof playing.song === 'object' ? markRaw(playing.song) : playing.song,
            orderedByNick: playing.orderedByNick
          }
        : null
      if (playing?.queueId !== prevQueueId) {
        this.resetPitch()
      }
      this.state = snap.state ?? 'idle'
      if (typeof snap.positionMs === 'number') this.positionMs = Math.max(0, snap.positionMs)
      if (typeof snap.seekSequence === 'number') this.seekSequence = snap.seekSequence
      this.volume = snap.volume ?? this.volume
      this.muted = snap.muted ?? false
      this.vocalMode = snap.vocalMode ?? this.vocalMode
      this.audioLayout = snap.audioLayout ?? this.audioLayout
      const rawList = snap.list ?? []
      this.queue = rawList.map(item =>
        item && item.song && typeof item.song === 'object'
          ? { ...item, song: markRaw(item.song) }
          : item
      )
      if (typeof snap.tvOnline === 'boolean') this.tvOnline = snap.tvOnline
      this.connectedPhones = snap.connectedPhones ?? 0
      this.playback = snap.playback ?? { kind: 'NONE', status: 'IDLE' }

      if (this.tvOnline === true) {
        this.webPlayerRole = 'STANDBY'
        this.webPlayerGeneration = 0
        this.webPlayerActive = false
        this.webPlayerHolderToken = null
        this.wasWebHostBeforeDisconnect = false
        this.socket?.clearPendingReports?.()
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

    setPitch(semitones) {
      const val = Number.isFinite(Number(semitones)) ? Math.round(Number(semitones)) : 0
      this.pitchSemitones = Math.max(-6, Math.min(6, val))
    },

    adjustPitch(delta) {
      this.setPitch(this.pitchSemitones + (Number(delta) || 0))
    },

    resetPitch() {
      this.pitchSemitones = 0
    },

    claimWebPlayer(force = false) {
      if (this.tvOnline === true && !force) return
      this.socket?.send({ type: 'web_player_claim', payload: { force: Boolean(force) } })
    },

    releaseWebPlayer() {
      this.webPlayerRole = 'STANDBY'
      this.webPlayerGeneration = 0
      this.wasWebHostBeforeDisconnect = false
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
        payload: {
          queue_id: queueId,
          position_ms: pos,
          generation: this.webPlayerGeneration,
          seek_sequence: this.seekSequence ?? 0,
        }
      })
    },

    reportFinished(queueId) {
      if (!Number.isInteger(queueId) || queueId <= 0) return false
      if (this.isWebHost && this.webPlayerGeneration) {
        const sent = this.socket?.send({
          type: 'finished',
          payload: { queue_id: queueId, generation: this.webPlayerGeneration }
        }) === true
        if (sent) this.socket?.trackInFlightReport?.({ kind: 'finished', queueId })
        else this.socket?.queueReport?.({ kind: 'finished', queueId })
        return sent
      }
      if (!this.connected && this.wasWebHostBeforeDisconnect && this.tvOnline !== true && this.modeOverride !== 'console') {
        this.socket?.queueReport?.({ kind: 'finished', queueId })
      }
      return false
    },

    reportPlayError(fileId, queueId, message) {
      if (!Number.isInteger(queueId) || queueId <= 0) return false
      const text = String(message || '媒体播放失败')
      if (this.isWebHost && this.webPlayerGeneration) {
        const sent = this.socket?.send({
          type: 'play_error',
          payload: {
            ...(fileId != null ? { file_id: fileId } : {}),
            queue_id: queueId,
            message: text,
            generation: this.webPlayerGeneration
          }
        }) === true
        if (sent) this.socket?.trackInFlightReport?.({ kind: 'play_error', queueId, fileId, message: text })
        else this.socket?.queueReport?.({ kind: 'play_error', queueId, fileId, message: text })
        return sent
      }
      if (!this.connected && this.wasWebHostBeforeDisconnect && this.tvOnline !== true && this.modeOverride !== 'console') {
        this.socket?.queueReport?.({ kind: 'play_error', queueId, fileId, message: text })
      }
      return false
    },

    /**
     * 重放断线期间积压的播放结果上报。
     *
     * 必须用**当前** generation 重建载荷：重连后 sessionId 与 generation 都变了，
     * 把旧消息体原样重发会被服务端判为 STALE 静默丢弃。
     */
    replayPendingReports() {
      if (!this.isWebHost || !this.webPlayerGeneration) return 0
      return this.socket?.replayPending?.((item) => {
        if (item.kind === 'finished') this.reportFinished(item.queueId)
        else if (item.kind === 'play_error') this.reportPlayError(item.fileId, item.queueId, item.message)
      }) ?? 0
    }
  }
})
