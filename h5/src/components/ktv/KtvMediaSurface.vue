<template>
  <!-- 统一样式舞台：小窗时固定对齐底部 Dock 16:9 框位置；全屏时铺满全屏视口，始终复用同一个 <video> 实例 -->
  <div
    class="media-stage"
    :class="{ fullscreen, 'has-song': Boolean(song), 'audio-only': isAudioOnly }"
    :style="stageStyle"
    :role="!fullscreen && song ? 'button' : undefined"
    :tabindex="!fullscreen && song ? 0 : undefined"
    :aria-label="!fullscreen && song ? '展开全屏 MV 播放' : undefined"
    @mousemove="wakeOsd"
    @click="onStageClick"
    @keydown.enter.prevent="!fullscreen && song && onStageClick()"
    @keydown.space.prevent="!fullscreen && song && onStageClick()"
  >
    <video
      v-show="hasVideoStream && !mirrorStreamFailed"
      ref="videoEl"
      class="ktv-video"
      playsinline
      preload="auto"
      :muted="effectiveMuted"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onLoadedMetadata"
      @progress="onVideoProgress"
      @canplay="onVideoCanPlay"
      @canplaythrough="onVideoCanPlayThrough"
      @ended="onVideoEnded"
      @error="onVideoError"
      @playing="onVideoPlaying"
      @waiting="onVideoWaiting"
      @stalled="onVideoStalled"
    />

    <!-- 转码流 Seek 平滑加载过渡层：保持当前画面并展示轻量加载指示，杜绝纯黑屏 -->
    <transition name="fade-fast">
      <div v-if="isSeekingTranscode" class="seek-buffer-overlay">
        <div class="seek-spinner" />
        <span>正在定位画面…</span>
      </div>
    </transition>

    <!-- 纯音频伴奏全屏动态频谱跳动与歌手写真舞台 (1:1 电视端全屏纯音频效果) -->
    <div
      v-if="song && fullscreen && (!hasVideoStream || mirrorStreamFailed || isAudioOnly)"
      class="audio-spectrum-stage"
    >
      <div
        class="spectrum-bg-kenburns"
        :style="audioStageBackdropUrl ? { backgroundImage: `url(${audioStageBackdropUrl})` } : {}"
      />
      <div class="spectrum-scrim" />
      <canvas ref="spectrumCanvas" class="spectrum-canvas" />
    </div>

    <!-- 音频曲目 / 镜像流不可用降级封面展示 (非全屏小窗时使用) -->
    <div v-if="song && !fullscreen && (!hasVideoStream || mirrorStreamFailed || isAudioOnly)" class="poster-fallback">
      <img
        v-if="audioPosterUrl"
        :src="audioPosterUrl"
        alt=""
        class="poster-img"
        @error="markMediaUrlFailed(audioPosterUrl)"
      />
      <div class="poster-badge">
        <strong>{{ song.title }}</strong>
        <span>{{ song.artist }}</span>
        <small v-if="mirrorStreamFailed || (isSilentConsole && streamSpec.liveTranscode)">已切换为封面与实时歌词预览</small>
      </div>
    </div>

    <!-- 小窗悬浮轻提示（仅悬停显露，绝不常驻遮挡画面） -->
    <div v-if="!fullscreen && song" class="pip-overlay-caption">
      <span>点击全屏</span>
    </div>

    <!-- 全屏覆盖层：极简顶部在播跑马灯、双行歌词、IKTV 底部两行大控台（对标 view_ktv_fullscreen_playback_panel.xml） -->
    <template v-if="fullscreen">
      <div class="fs-topbar" :class="{ hidden: !osdVisible }">
        <button type="button" class="fs-back-btn" @click.stop="$emit('open-browser')">
          <ArrowLeft :size="18" aria-hidden="true" />
          <span>返回点歌台</span>
        </button>
        <div v-if="marqueeText" class="fs-now-marquee" role="status" aria-live="polite">
          <span
            :key="marqueeRunKey"
            class="fs-now-text"
            :style="{ '--marquee-duration': `${marqueeDurationSec}s` }"
          >{{ marqueeText }}</span>
        </div>
      </div>

      <!-- 1:1 电视同款双行卡拉OK歌词 -->
      <div v-if="props.showLyrics && song" class="fs-lyrics" :class="{ 'audio-center': isAudioOnly || mirrorStreamFailed }">
        <div v-if="lyricError" class="lyric-empty">歌词加载失败 · <button type="button" @click.stop="retryLyric">重试</button></div>
        <KaraokeLyricSurface
          v-else
          :lyric-lines="lyricLines"
          :position-ms="displayPositionMs"
          :is-playing="player.isPlaying"
        />
      </div>

      <!-- 全屏底部控台：第一行翡翠绿进度条 + 第二行 10 个大号白线图标 -->
      <div
        class="fs-bottom-panel"
        :class="{ hidden: !osdVisible }"
        @click.stop="wakeOsd"
        @mousemove="wakeOsd"
        @pointerdown="wakeOsd"
      >
        <div class="fs-progress-row">
          <button type="button" class="fs-seek-btn" aria-label="快退 10 秒" @click="seekBy(-10000)">
            <span class="fs-seek-ring">
              <img :src="assets.playerRewind" alt="" class="fs-seek-png" />
            </span>
          </button>
          <span class="time-lbl">{{ formatDurationMs(displayPositionMs) }}</span>
          <input
            type="range"
            aria-label="播放进度"
            min="0"
            :max="durationMs || 1"
            :value="Math.min(displayPositionMs, durationMs || 1)"
            class="fs-slider"
            :style="{ '--seek-pct': `${seekProgressPct}%` }"
            @input="onScrubInput"
            @change="onScrubCommit"
          />
          <span class="time-lbl">{{ formatDurationMs(durationMs) }}</span>
          <button type="button" class="fs-seek-btn" aria-label="快进 10 秒" @click="seekBy(10000)">
            <span class="fs-seek-ring">
              <img :src="assets.playerForward" alt="" class="fs-seek-png" />
            </span>
          </button>
        </div>

        <div class="fs-action-row">
          <button type="button" class="fs-act fs-play-act" @click="$emit('toggle-play')">
            <span class="fs-act-icon">
              <img :src="player.isPlaying ? assets.playerPause : assets.playerPlay" alt="" class="fs-act-png" />
            </span>
            <span>{{ player.isPlaying ? '暂停' : '播放' }}</span>
          </button>
          <button
            type="button"
            class="fs-act"
            :class="{ active: player.vocalMode === 'accompaniment' }"
            :disabled="!canVocal"
            @click="$emit('toggle-vocal')"
          >
            <span class="fs-act-icon">
              <img :src="assets.playerVocal" alt="" class="fs-act-png" />
            </span>
            <span>{{ player.vocalMode === 'original' ? '原唱' : '伴唱' }}</span>
          </button>
          <button type="button" class="fs-act" @click="$emit('restart')">
            <span class="fs-act-icon">
              <img :src="assets.playerRestart" alt="" class="fs-act-png" />
            </span>
            <span>重唱</span>
          </button>
          <button type="button" class="fs-act" @click="$emit('next')">
            <span class="fs-act-icon">
              <img :src="assets.playerNext" alt="" class="fs-act-png" />
            </span>
            <span>切歌</span>
          </button>
          <button type="button" class="fs-act" @click="$emit('volume-delta', -VOLUME_STEP)">
            <span class="fs-act-icon">
              <img :src="assets.playerVolumeDown" alt="" class="fs-act-png" />
            </span>
            <span>音量减</span>
          </button>
          <button type="button" class="fs-act" @click="$emit('volume-delta', VOLUME_STEP)">
            <span class="fs-act-icon">
              <img :src="assets.playerVolumeUp" alt="" class="fs-act-png" />
            </span>
            <span>音量加</span>
          </button>
          <button type="button" class="fs-act" :class="{ active: player.muted }" @click="$emit('toggle-mute')">
            <span class="fs-act-icon">
              <img :src="player.muted ? assets.playerUnmute : assets.playerMute" alt="" class="fs-act-png" />
            </span>
            <span>{{ player.muted ? '取消静音' : '静音' }}</span>
          </button>
          <button type="button" class="fs-act" @click="$emit('open-browser')">
            <span class="fs-act-icon">
              <img :src="assets.playerKiosk" alt="" class="fs-act-png" />
            </span>
            <span>点歌台</span>
          </button>
          <button type="button" class="fs-act fs-queue-act" aria-label="已点歌单" @click="$emit('open-queue')">
            <span class="fs-act-icon">
              <img :src="assets.playerQueue" alt="" class="fs-act-png" />
              <em v-if="player.queueCount > 0" class="fs-queue-badge">{{ player.queueCount }}</em>
            </span>
            <span>已点</span>
          </button>
          <button type="button" class="fs-act" @click="$emit('open-home')">
            <span class="fs-act-icon">
              <img :src="assets.playerHome" alt="" class="fs-act-png" />
            </span>
            <span>首页</span>
          </button>
        </div>
      </div>

      <!-- 全屏模式下醒目的自动播放伴奏声音锁定解锁横幅 -->
      <transition name="fade-fast">
        <div
          v-if="fullscreen && player.isWebHost && !isSilentConsole && player.audioBlockedByAutoplay && song"
          class="fs-autoplay-banner"
          role="alert"
          @click.stop="unlockAudio"
        >
          <div class="banner-box">
            <Volume2 :size="24" class="banner-icon pulse" />
            <div class="banner-texts">
              <strong>浏览器已自动静音伴奏</strong>
              <small>点击画面任意位置或点击此处立即开启声音</small>
            </div>
          </div>
        </div>
      </transition>
    </template>
  </div>

  <!-- Web 播放器 Autoplay 声音锁定解锁提示（独立于 stage 透明度避免被隐藏） -->
  <button
    v-if="player.isWebHost && player.audioBlockedByAutoplay && song"
    type="button"
    class="autoplay-unlock"
    @click.stop="unlockAudio"
  >
    <Volume2 :size="16" /> {{ player.audioUnlockFailed ? '声音开启失败，点击重试' : '点击开启 KTV 伴奏声音' }}
  </button>

  <!-- 现场氛围互动音效浮层（响应 WS effect_play） -->
  <div v-if="effectBanner" class="effect-stage-toast" role="status" aria-live="polite">
    {{ effectBanner }}
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Volume2 } from 'lucide-vue-next'
import KaraokeLyricSurface from './KaraokeLyricSurface.vue'
import { usePlayerStore } from '../../stores/player'
import { parseLrc } from '../../composables/lrc'
import { createLyricLoader } from '../../views/lyricLoader'
import { buildWebStreamSpec } from '../../player/streamSelection'
import { VOLUME_STEP } from './ktvControlSteps'
import { createWebAudioChannelRouter } from '../../player/webAudioChannelRouter'
import { useToast } from '../../composables/useToast'
import { formatDurationMs, KTV_ASSETS } from './ktvCatalogPresets'
import {
  rememberFailedThumbUrl,
  resolveSongThumbUrl,
} from '../../utils/songThumbnail'

const props = defineProps({
  fullscreen: { type: Boolean, default: false },
  pipRect: { type: Object, default: null },
  showLyrics: { type: Boolean, default: true },
})
const assets = KTV_ASSETS
const emit = defineEmits([
  'enter-fullscreen',
  'toggle-play',
  'toggle-vocal',
  'toggle-mute',
  'restart',
  'next',
  'seek',
  'volume-delta',
  'open-queue',
  'open-browser',
  'open-home',
])

const player = usePlayerStore()
const { toast } = useToast()
const videoEl = ref(null)
const channelRouter = createWebAudioChannelRouter()

const forceTranscodeFallback = ref(false)
const forceSoftVideoFallback = ref(false)
const mirrorStreamFailed = ref(false)
const isSeekingTranscode = ref(false)
const liveStartSec = ref(0)
const streamReloadNonce = ref(0)
const detectedDurationMs = ref(0)
const scrubbingValue = ref(null)
const osdVisible = ref(true)
const effectBanner = ref('')
const marqueeRunKey = ref(0)
const streamSessionId = `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
let osdTimer = null
let lastOsdAutoWokeAt = 0
let effectTimer = null
let liveStallTimer = null
let liveStallBaselineTime = -1
let liveRecoveryAttempts = 0
let livePrebufferTimer = null
let awaitingLivePrebuffer = false
let lastProgressSentAt = 0
let lastAppliedSeq = -1
let lastStreamKey = ''
let awaitingMediaReload = false

const MAX_LIVE_RECOVERY_ATTEMPTS = 2
const LIVE_STALL_TIMEOUT_MS = 10000
const LIVE_PREBUFFER_MIN_SEC = 1.2
const LIVE_PREBUFFER_TIMEOUT_MS = 1800

const lyricLines = ref([])
const lyricError = ref('')
const lyricLoader = createLyricLoader({
  fetchImpl: fetch,
  parse: parseLrc,
  getCurrentSong: () => song.value,
  onReset: () => { lyricLines.value = []; lyricError.value = '' },
  onSuccess: parsed => { lyricLines.value = parsed },
  onError: err => { lyricError.value = err?.message || '加载失败' }
})

const song = computed(() => player.nowPlaying?.song || null)
const nextSong = computed(() => player.queue?.[0]?.song || null)
const marqueeText = computed(() => {
  if (!song.value?.title) return ''
  const prefix = player.nowPlaying?.autoPlay ? '随机公播' : '正在播放'
  const base = `${prefix}：${song.value.title}`
  if (!nextSong.value?.title) return base
  const nextPart = nextSong.value.artist
    ? `${nextSong.value.artist} - ${nextSong.value.title}`
    : nextSong.value.title
  return `${base}\u3000\u3000|\u3000\u3000下一首：${nextPart}`
})
const marqueeDurationSec = computed(() => {
  const len = marqueeText.value.length
  return Math.min(26, Math.max(12, Math.round(10 + len * 0.28)))
})
const failedMediaUrls = ref(new Set())
const audioStageBackdropUrl = computed(() => resolveSongThumbUrl(song.value, failedMediaUrls.value))
const audioPosterUrl = computed(() => resolveSongThumbUrl(song.value, failedMediaUrls.value))

function markMediaUrlFailed(url) {
  if (!url) return
  failedMediaUrls.value = rememberFailedThumbUrl(failedMediaUrls.value, url)
}
const isSilentConsole = computed(() => {
  return player.isSilentConsole ?? (
    player.isTvConsole ||
    player.modeOverride === 'console' ||
    (player.webPlayerActive && player.webPlayerRole !== 'ACTIVE')
  )
})

const canVocal = computed(() => (
  song.value?.hasVocalTrack === true
  || ['DUAL_TRACK', 'DUAL_CHANNEL'].includes(player.audioLayout?.layout)
))

const needsWebAudioRouting = computed(() => {
  if (isSilentConsole.value) return false
  if (['DUAL_TRACK', 'DUAL_CHANNEL'].includes(player.audioLayout?.layout)) return true
  if ((player.pitchSemitones || 0) !== 0) return true
  return false
})

const streamSpec = computed(() => buildWebStreamSpec({
  playback: player.playback,
  audioLayout: player.audioLayout,
  vocalMode: player.vocalMode,
  startSeconds: liveStartSec.value,
  forceTranscode: forceTranscodeFallback.value,
  softVideo: forceSoftVideoFallback.value,
  song: song.value,
  sid: streamSessionId,
}))

const isReconnectingWebHost = computed(() => Boolean(
  player.wasWebHostBeforeDisconnect
  && player.tvOnline !== true
  && player.modeOverride !== 'console'
))

const shouldLoadVideoMedia = computed(() => {
  // 扫码遥控端（WEB_REMOTE）或处于手动控制台模式时：
  // 1) 实时转码流（liveTranscode）一律不拉取，100% 保护服务端 4 并发 FFmpeg 槽位；
  // 2) 非全屏小窗默认走省流封面+实时歌词预览，避免多个手机大厅连接占用带宽与媒体槽位。
  // WS 瞬断重连过渡期内若本端断线前就是 WEB_HOST，则继续保活视频流避免无谓中断。
  if (isReconnectingWebHost.value) {
    return Boolean(streamSpec.value.url)
  }
  const isWebRemoteOnly = player.effectiveMode === 'WEB_REMOTE' || player.modeOverride === 'console'
  if (isWebRemoteOnly && (streamSpec.value.liveTranscode || !props.fullscreen)) {
    return false
  }
  return Boolean(streamSpec.value.url)
})

const hasVideoStream = computed(() => Boolean(shouldLoadVideoMedia.value && streamSpec.value.url))
const isAudioOnly = computed(() => streamSpec.value.isAudioOnly)
const effectiveMuted = computed(() => {
  if (isSilentConsole.value) return true
  if (player.muted) return true
  if (player.audioBlockedByAutoplay) return true
  return false
})
const durationMs = computed(() => song.value?.durationMs || detectedDurationMs.value || 0)
const displayPositionMs = computed(() => scrubbingValue.value != null ? scrubbingValue.value : player.positionMs)
const seekProgressPct = computed(() => (
  durationMs.value > 0
    ? Math.min(100, Math.max(0, (displayPositionMs.value / durationMs.value) * 100))
    : 0
))

const stageStyle = computed(() => {
  if (props.fullscreen) return {}
  const r = props.pipRect
  if (!song.value || !r || r.width <= 0 || r.height <= 0) {
    return { opacity: 0, pointerEvents: 'none', width: '0px', height: '0px' }
  }
  return {
    left: `${r.left}px`,
    top: `${r.top}px`,
    width: `${r.width}px`,
    height: `${r.height}px`,
  }
})

function wakeOsd() {
  if (!props.fullscreen) return
  if (!osdVisible.value) {
    marqueeRunKey.value += 1
    lastOsdAutoWokeAt = Date.now()
  }
  osdVisible.value = true
  clearTimeout(osdTimer)
  osdTimer = setTimeout(() => { osdVisible.value = false }, 3000)
}

function triggerAudioWakeGesture() {
  if (isSilentConsole.value) return
  if (typeof channelRouter.resumeDirect === 'function') {
    channelRouter.resumeDirect()
  }
  const el = videoEl.value
  if (el) {
    el.muted = effectiveMuted.value
  }
  if (player.audioBlockedByAutoplay) {
    unlockAudio()
  }
}

function onStageClick() {
  triggerAudioWakeGesture()
  if (!props.fullscreen) {
    emit('enter-fullscreen')
    return
  }
  if (osdVisible.value && Date.now() - lastOsdAutoWokeAt < 350) {
    wakeOsd()
    return
  }
  osdVisible.value = !osdVisible.value
  if (osdVisible.value) wakeOsd()
}

function syncAudioRouter() {
  const el = videoEl.value
  if (!el) return
  if (!isSilentConsole.value) {
    if (needsWebAudioRouting.value || Boolean(channelRouter?.isActive?.())) {
      const routed = channelRouter.attach(el)
      channelRouter.setRouting(player.audioLayout, player.vocalMode)
      channelRouter?.setPitch?.(player.pitchSemitones)
      channelRouter.setGain(player.volume, effectiveMuted.value)
      if (el.volume !== undefined) {
        el.volume = routed ? 1 : Math.max(0, Math.min(1, (player.volume ?? 60) / 100))
      }
      el.muted = effectiveMuted.value
    } else {
      if (el.volume !== undefined) {
        el.volume = Math.max(0, Math.min(1, (player.volume ?? 60) / 100))
      }
      el.muted = effectiveMuted.value
    }
  } else {
    channelRouter.setGain(0, true)
    channelRouter?.suspend?.()
    el.muted = true
  }
}

async function applyMediaSource() {
  const el = videoEl.value
  if (!el) return
  const nextUrl = shouldLoadVideoMedia.value ? streamSpec.value.url : ''
  const queueId = player.nowPlaying?.queueId || 0
  const key = `${queueId}|${nextUrl}|${streamReloadNonce.value}`
  if (key === lastStreamKey) return
  lastStreamKey = key

  if (!nextUrl) {
    clearLivePrebuffer()
    awaitingMediaReload = false
    isSeekingTranscode.value = false
    if (el.getAttribute('src')) {
      el.pause()
      el.removeAttribute('src')
      el.load()
    }
    return
  }

  awaitingMediaReload = true
  mirrorStreamFailed.value = false
  clearLivePrebuffer()
  const needsPrebuffer = streamSpec.value.liveTranscode && !isAudioOnly.value
  awaitingLivePrebuffer = needsPrebuffer
  if (needsPrebuffer) {
    livePrebufferTimer = setTimeout(() => {
      releaseLivePrebufferAndPlay()
    }, LIVE_PREBUFFER_TIMEOUT_MS)
  }
  if (el.getAttribute('src') === nextUrl) {
    if (typeof el.load === 'function') el.load()
  } else {
    el.src = nextUrl
  }
  syncAudioRouter()
  if (player.isPlaying && !awaitingLivePrebuffer) {
    await attemptPlay()
  }
}

async function attemptPlay() {
  const el = videoEl.value
  if (!el || !shouldLoadVideoMedia.value || !streamSpec.value.url) return
  const webAudioActive = needsWebAudioRouting.value || Boolean(channelRouter?.isActive?.())
  try {
    syncAudioRouter()
    if (webAudioActive && typeof channelRouter.resumeDirect === 'function') {
      channelRouter.resumeDirect()
      if (channelRouter.isSuspended?.() && typeof channelRouter.resume === 'function') {
        await channelRouter.resume(120)
      }
    }
    await el.play()
    if (!isSilentConsole.value && !player.muted && webAudioActive && channelRouter.isSuspended()) {
      player.audioBlockedByAutoplay = true
    } else if (!isSilentConsole.value) {
      player.audioBlockedByAutoplay = false
      player.audioUnlockFailed = false
    }
  } catch (err) {
    if (!isSilentConsole.value && err?.name === 'NotAllowedError') {
      player.audioBlockedByAutoplay = true
      el.muted = true
      try { await el.play() } catch { /* wait for user tap */ }
    }
  }
}

async function unlockAudio() {
  const el = videoEl.value
  if (!el || isSilentConsole.value) return
  clearLivePrebuffer()
  const webAudioActive = needsWebAudioRouting.value || Boolean(channelRouter?.isActive?.())
  player.audioBlockedByAutoplay = false
  player.audioUnlockFailed = false
  el.muted = player.muted
  syncAudioRouter()
  try {
    if (webAudioActive) {
      if (typeof channelRouter.resumeDirect === 'function') {
        channelRouter.resumeDirect()
      }
      if (typeof channelRouter.resume === 'function') {
        await channelRouter.resume()
      }
    }
    if (player.isPlaying) await el.play()
    if (webAudioActive && channelRouter.isSuspended?.()) {
      throw new DOMException('AudioContext remains suspended', 'NotAllowedError')
    }
  } catch (e) {
    player.audioBlockedByAutoplay = true
    player.audioUnlockFailed = true
    el.muted = true
    toast(e?.name === 'NotAllowedError'
      ? '浏览器仍拦截自动播放，请再点一次开启声音'
      : '声音开启失败，请检查设备输出后重试')
  }
}

function clearLivePrebuffer() {
  if (livePrebufferTimer) {
    clearTimeout(livePrebufferTimer)
    livePrebufferTimer = null
  }
  awaitingLivePrebuffer = false
}

function getBufferedAheadSeconds(el) {
  if (!el || !el.buffered || el.buffered.length === 0) return 0
  const cur = Number.isFinite(el.currentTime) ? el.currentTime : 0
  let maxEnd = cur
  for (let i = 0; i < el.buffered.length; i += 1) {
    const start = el.buffered.start(i)
    const end = el.buffered.end(i)
    if (cur + 0.25 >= start && end > maxEnd) {
      maxEnd = end
    }
  }
  return Math.max(0, maxEnd - cur)
}

function releaseLivePrebufferAndPlay() {
  const wasWaiting = awaitingLivePrebuffer
  clearLivePrebuffer()
  if (!wasWaiting) return
  isSeekingTranscode.value = false
  if (player.isPlaying && shouldLoadVideoMedia.value) {
    attemptPlay()
  }
}

function maybeReleaseLivePrebuffer() {
  if (!awaitingLivePrebuffer) return
  const el = videoEl.value
  if (!el) return
  if (el.readyState >= 4 || getBufferedAheadSeconds(el) >= LIVE_PREBUFFER_MIN_SEC) {
    releaseLivePrebufferAndPlay()
  }
}

function onVideoProgress() {
  maybeReleaseLivePrebuffer()
}

function onVideoCanPlay() {
  maybeReleaseLivePrebuffer()
}

function onVideoCanPlayThrough() {
  if (awaitingLivePrebuffer) {
    releaseLivePrebufferAndPlay()
  }
}

function clearLiveStallWatchdog() {
  if (liveStallTimer) {
    clearTimeout(liveStallTimer)
    liveStallTimer = null
  }
  liveStallBaselineTime = -1
}

function recoverLiveTranscodeStream() {
  if (!streamSpec.value.liveTranscode || isAudioOnly.value || !shouldLoadVideoMedia.value) return false
  if (!player.isWebHost || !player.isPlaying) return false
  if (liveRecoveryAttempts >= MAX_LIVE_RECOVERY_ATTEMPTS) return false

  liveRecoveryAttempts += 1
  clearLiveStallWatchdog()
  const el = videoEl.value
  const elPosSec = Number.isFinite(el?.currentTime) ? el.currentTime : 0
  const resumeSec = Math.max(0, liveStartSec.value + elPosSec, (player.positionMs || 0) / 1000)
  awaitingMediaReload = true
  isSeekingTranscode.value = true
  liveStartSec.value = resumeSec
  streamReloadNonce.value += 1
  return true
}

function scheduleLiveStallWatchdog() {
  if (liveStallTimer) return
  if (!streamSpec.value.liveTranscode || isAudioOnly.value || !shouldLoadVideoMedia.value) return
  if (!player.isWebHost || !player.isPlaying || isSilentConsole.value) return
  if (liveRecoveryAttempts >= MAX_LIVE_RECOVERY_ATTEMPTS) return

  const el = videoEl.value
  liveStallBaselineTime = Number.isFinite(el?.currentTime) ? el.currentTime : 0
  liveStallTimer = setTimeout(() => {
    const baseline = liveStallBaselineTime
    liveStallTimer = null
    const currentEl = videoEl.value
    if (!currentEl || !player.isPlaying || !player.isWebHost || !streamSpec.value.liveTranscode) return
    const nowTime = Number.isFinite(currentEl.currentTime) ? currentEl.currentTime : 0
    if (Math.abs(nowTime - baseline) < 0.25) {
      recoverLiveTranscodeStream()
    }
  }, LIVE_STALL_TIMEOUT_MS)
}

function onLoadedMetadata() {
  awaitingMediaReload = false
  if (!awaitingLivePrebuffer) {
    isSeekingTranscode.value = false
  }
  const el = videoEl.value
  if (!el) return
  if (Number.isFinite(el.duration) && el.duration > 0 && el.duration < 86400) {
    const offsetMs = streamSpec.value.liveTranscode ? Math.round((liveStartSec.value || 0) * 1000) : 0
    detectedDurationMs.value = offsetMs + Math.round(el.duration * 1000)
  }
  const targetSec = (player.positionMs || 0) / 1000
  if (!streamSpec.value.liveTranscode && targetSec > 1 && Number.isFinite(el.duration) && targetSec < el.duration) {
    try { el.currentTime = targetSec } catch { /* ignore */ }
  }
  maybeReleaseLivePrebuffer()
  if (player.isPlaying && el.paused && !awaitingLivePrebuffer) attemptPlay()
}

function onVideoPlaying() {
  awaitingMediaReload = false
  clearLivePrebuffer()
  isSeekingTranscode.value = false
  clearLiveStallWatchdog()
  if (player.audioBlockedByAutoplay && player.isWebHost && !player.muted) {
    // Wait for explicit user gesture if blocked
  }
}

function onVideoWaiting() {
  scheduleLiveStallWatchdog()
}

function onVideoStalled() {
  scheduleLiveStallWatchdog()
}

function onTimeUpdate() {
  const el = videoEl.value
  if (el && liveStallTimer && Number.isFinite(el.currentTime) && Math.abs(el.currentTime - liveStallBaselineTime) >= 0.25) {
    clearLiveStallWatchdog()
  }
  if (awaitingMediaReload || !el || !player.isWebHost || !player.nowPlaying?.queueId) return
  const baseMs = streamSpec.value.liveTranscode ? Math.round(liveStartSec.value * 1000) : 0
  const curMs = baseMs + Math.round((el.currentTime || 0) * 1000)
  const now = Date.now()
  if (now - lastProgressSentAt >= 900) {
    lastProgressSentAt = now
    player.reportProgress(player.nowPlaying.queueId, curMs)
  }
}

function onVideoEnded() {
  clearLiveStallWatchdog()
  const el = videoEl.value
  const baseMs = streamSpec.value.liveTranscode ? Math.round(liveStartSec.value * 1000) : 0
  const curMs = baseMs + Math.round(((Number.isFinite(el?.currentTime) ? el.currentTime : 0)) * 1000)
  const totalMs = durationMs.value || 0
  if (
    streamSpec.value.liveTranscode
    && !isAudioOnly.value
    && player.isWebHost
    && player.isPlaying
    && totalMs > 15000
    && curMs < totalMs - 8000
    && recoverLiveTranscodeStream()
  ) {
    return
  }
  if (!player.isWebHost || !player.nowPlaying?.queueId) return
  player.reportFinished(player.nowPlaying.queueId)
}

function onVideoError() {
  if (!streamSpec.value.url) return
  if ((!forceTranscodeFallback.value || !forceSoftVideoFallback.value) && !isAudioOnly.value) {
    const startSec = Math.max(0, (player.positionMs || 0) / 1000)
    const fallbackSpec = buildWebStreamSpec({
      playback: player.playback,
      audioLayout: player.audioLayout,
      vocalMode: player.vocalMode,
      startSeconds: startSec,
      forceTranscode: true,
      softVideo: true,
      song: song.value,
      sid: streamSessionId,
    })
    if (fallbackSpec.url && fallbackSpec.url !== streamSpec.value.url) {
      liveStartSec.value = startSec
      forceTranscodeFallback.value = true
      forceSoftVideoFallback.value = true
      return
    }
  }
  if (!player.isWebHost) {
    mirrorStreamFailed.value = true
    return
  }
  const fileId = player.playback?.sourceFileId ?? null
  const queueId = player.nowPlaying?.queueId
  if (queueId) {
    const reported = player.reportPlayError(fileId, queueId, '浏览器无法解码当前视频流')
    // 上报没发出去（WS 未连或非本端授权）时，服务端并不知道这一端已经播不动，
    // 本地必须自己退到失败态，否则画面会一直停在黑屏/转圈。
    if (!reported) mirrorStreamFailed.value = true
  }
}

function onScrubInput(e) {
  wakeOsd()
  scrubbingValue.value = Number(e.target.value)
}

function onScrubCommit(e) {
  const targetMs = Number(e.target.value)
  scrubbingValue.value = null
  emit('seek', targetMs)
}

function seekBy(deltaMs) {
  const nextMs = Math.max(0, Math.min(durationMs.value || 0, player.positionMs + deltaMs))
  emit('seek', nextMs)
}

function retryLyric() {
  if (song.value?.id) lyricLoader.load(song.value.id)
}

function resolveInitialLiveStartSec() {
  const posSec = Math.max(0, (player.positionMs || 0) / 1000)
  if (posSec <= 1) return 0
  const baseSpec = buildWebStreamSpec({
    playback: player.playback,
    audioLayout: player.audioLayout,
    vocalMode: player.vocalMode,
    startSeconds: 0,
    forceTranscode: forceTranscodeFallback.value,
    song: song.value,
    sid: streamSessionId,
  })
  return baseSpec.liveTranscode ? posSec : 0
}

watch(() => player.nowPlaying?.queueId, (newId, oldId) => {
  if (newId !== oldId) {
    awaitingMediaReload = Boolean(newId)
    forceTranscodeFallback.value = false
    forceSoftVideoFallback.value = false
    mirrorStreamFailed.value = false
    liveRecoveryAttempts = 0
    clearLivePrebuffer()
    clearLiveStallWatchdog()
    liveStartSec.value = newId ? resolveInitialLiveStartSec() : 0
    detectedDurationMs.value = 0
    isSeekingTranscode.value = false
    lastAppliedSeq = player.seekSequence
    channelRouter?.resetPitch?.()
    if (song.value?.id) lyricLoader.load(song.value.id)
    else lyricLoader.dispose()

    if (newId && song.value) {
      marqueeRunKey.value += 1
      wakeOsd()
    }
  }
}, { immediate: true })

watch(() => nextSong.value?.id, (newNextId, oldNextId) => {
  if (newNextId && newNextId !== oldNextId && song.value) {
    marqueeRunKey.value += 1
    wakeOsd()
  }
})

watch(() => props.fullscreen, (isFull) => {
  if (isFull) {
    marqueeRunKey.value += 1
  }
})

watch(() => player.vocalMode, () => {
  syncAudioRouter()
})

watch(
  () => [
    player.audioLayout?.layout,
    player.audioLayout?.originalTrackIndex,
    player.audioLayout?.accompanimentTrackIndex,
    player.audioLayout?.originalChannel,
    player.audioLayout?.accompanimentChannel,
  ],
  () => {
    syncAudioRouter()
  }
)

watch([() => streamSpec.value.url, () => player.nowPlaying?.queueId, streamReloadNonce, shouldLoadVideoMedia], () => {
  applyMediaSource()
}, { flush: 'post' })

watch(() => player.state, (state) => {
  const el = videoEl.value
  if (!el || !streamSpec.value.url) return
  if (state === 'playing') {
    if (!awaitingLivePrebuffer) attemptPlay()
  } else {
    clearLivePrebuffer()
    clearLiveStallWatchdog()
    isSeekingTranscode.value = false
    el.pause()
    channelRouter?.suspend?.()
  }
})

watch(() => player.isWebHost, (isHost) => {
  if (isHost) {
    mirrorStreamFailed.value = false
    const el = videoEl.value
    const posSec = Math.max(0, (player.positionMs || 0) / 1000)
    if (streamSpec.value.liveTranscode) {
      const currentTotalSec = liveStartSec.value + (el?.currentTime || 0)
      if (posSec > 1 && Math.abs(currentTotalSec - posSec) > 3) {
        awaitingMediaReload = true
        liveStartSec.value = posSec
      }
    } else if (el && Number.isFinite(el.duration) && posSec < el.duration && Math.abs((el.currentTime || 0) - posSec) > 2.5) {
      try { el.currentTime = posSec } catch { /* ignore */ }
    }
    syncAudioRouter()
    if (player.isPlaying && !awaitingLivePrebuffer) attemptPlay()
  } else {
    syncAudioRouter()
  }
})

watch([() => player.volume, () => player.muted], () => {
  syncAudioRouter()
})

watch(() => player.seekSequence, (seq) => {
  if (seq === lastAppliedSeq) return
  lastAppliedSeq = seq
  const targetSec = Math.max(0, (player.positionMs || 0) / 1000)
  if (streamSpec.value.liveTranscode) {
    if (shouldLoadVideoMedia.value) {
      awaitingMediaReload = true
      isSeekingTranscode.value = true
      if (Math.abs(targetSec - liveStartSec.value) < 0.5) {
        liveStartSec.value = targetSec
        streamReloadNonce.value += 1
      } else {
        liveStartSec.value = targetSec
      }
    } else {
      isSeekingTranscode.value = false
      liveStartSec.value = targetSec
    }
  } else if (shouldLoadVideoMedia.value) {
    const el = videoEl.value
    if (el) {
      try { el.currentTime = targetSec } catch { /* ignore */ }
    }
  }
})

const EFFECT_LABELS = {
  clap: '👏 现场鼓掌',
  cheer: '🎉 欢呼喝彩',
  boo: '📣 起哄倒彩',
  toast: '🍻 举杯欢聚',
}

watch(() => player.lastEffectSeq, (seq) => {
  if (!seq || !player.lastEffect) return
  effectBanner.value = EFFECT_LABELS[player.lastEffect] || `🎵 现场音效：${player.lastEffect}`
  clearTimeout(effectTimer)
  effectTimer = setTimeout(() => { effectBanner.value = '' }, 2400)
})

watch(() => player.positionMs, (posMs) => {
  const el = videoEl.value
  if (!el || !streamSpec.value.url || player.isWebHost) return
  const targetSec = Math.max(0, (posMs || 0) / 1000)
  if (!streamSpec.value.liveTranscode && Math.abs((el.currentTime || 0) - targetSec) > 2.5) {
    try { el.currentTime = targetSec } catch { /* ignore */ }
  }
})

watch(() => props.fullscreen, (fs) => {
  if (fs) wakeOsd()
  else clearTimeout(osdTimer)
})

watch(() => player.pitchSemitones, () => {
  syncAudioRouter()
})

const spectrumCanvas = ref(null)
let spectrumAnimId = null
let cachedSpectrumCanvas = null
let cachedSpectrumCtx = null
let cachedSpectrumGradient = null
let cachedSpectrumWidth = 0
let cachedSpectrumHeight = 0
let lastSpectrumDrawTime = 0
const SPECTRUM_FRAME_INTERVAL_MS = 33

function resetSpectrumCache() {
  cachedSpectrumCanvas = null
  cachedSpectrumCtx = null
  cachedSpectrumGradient = null
  cachedSpectrumWidth = 0
  cachedSpectrumHeight = 0
}

function shouldRunSpectrum() {
  if (typeof document !== 'undefined' && document.hidden) return false
  return Boolean(
    props.fullscreen
    && (isAudioOnly.value || !hasVideoStream.value || mirrorStreamFailed.value)
    && player.isPlaying
  )
}

function renderSpectrumFrame(nowTs = 0) {
  if (!spectrumCanvas.value || !shouldRunSpectrum()) {
    spectrumAnimId = null
    return
  }

  const ts = typeof nowTs === 'number' && nowTs > 0 ? nowTs : Date.now()
  if (lastSpectrumDrawTime > 0 && (ts - lastSpectrumDrawTime) < SPECTRUM_FRAME_INTERVAL_MS) {
    spectrumAnimId = requestAnimationFrame(renderSpectrumFrame)
    return
  }
  lastSpectrumDrawTime = ts

  const canvas = spectrumCanvas.value
  if (cachedSpectrumCanvas !== canvas || !cachedSpectrumCtx) {
    try {
      cachedSpectrumCtx = canvas.getContext ? canvas.getContext('2d') : null
      cachedSpectrumCanvas = canvas
      cachedSpectrumGradient = null
    } catch {
      cachedSpectrumCtx = null
    }
  }
  const ctx = cachedSpectrumCtx
  if (!ctx) return

  const width = canvas.width || 600
  const height = canvas.height || 180
  if (typeof ctx.clearRect === 'function') {
    ctx.clearRect(0, 0, width, height)
  }

  if (!cachedSpectrumGradient || cachedSpectrumWidth !== width || cachedSpectrumHeight !== height) {
    cachedSpectrumWidth = width
    cachedSpectrumHeight = height
    if (typeof ctx.createLinearGradient === 'function') {
      const gradient = ctx.createLinearGradient(0, height, 0, 0)
      gradient.addColorStop(0, 'rgba(0, 229, 255, 0.7)')
      gradient.addColorStop(0.5, 'rgba(168, 85, 247, 0.85)')
      gradient.addColorStop(1, 'rgba(255, 215, 0, 0.95)')
      cachedSpectrumGradient = gradient
    } else {
      cachedSpectrumGradient = '#00e5ff'
    }
  }
  ctx.fillStyle = cachedSpectrumGradient

  const isPlaying = Boolean(player.isPlaying)
  const freqData = channelRouter?.getFrequencyData?.()
  const barCount = 40
  const barWidth = Math.max(3, (width / barCount) - 4)
  const maxBarHeight = height * 0.75
  const startOffset = (width - (barCount * (barWidth + 4))) / 2
  const fallbackPhase = ts / 300

  if (typeof ctx.beginPath === 'function') ctx.beginPath()
  for (let i = 0; i < barCount; i++) {
    let value = 0
    if (isPlaying && freqData && freqData.length > 0) {
      const freqIndex = Math.floor((i / barCount) * Math.min(freqData.length, 48))
      value = (freqData[freqIndex] || 0) / 255
    } else if (isPlaying) {
      value = (Math.sin(fallbackPhase + i * 0.3) + 1) * 0.15 + 0.05
    }
    const barHeight = Math.max(4, value * maxBarHeight)
    const x = i * (barWidth + 4) + startOffset
    const y = height - barHeight

    if (typeof ctx.roundRect === 'function') {
      ctx.roundRect(x, y, barWidth, barHeight, [4, 4, 0, 0])
    } else if (typeof ctx.rect === 'function') {
      ctx.rect(x, y, barWidth, barHeight)
    }
  }
  if (typeof ctx.fill === 'function') ctx.fill()

  if (shouldRunSpectrum()) {
    spectrumAnimId = requestAnimationFrame(renderSpectrumFrame)
  } else {
    spectrumAnimId = null
  }
}

function startSpectrumAnimation() {
  stopSpectrumAnimation()
  if (!shouldRunSpectrum()) return

  if (spectrumCanvas.value) {
    const rect = spectrumCanvas.value.getBoundingClientRect ? spectrumCanvas.value.getBoundingClientRect() : null
    if (rect && rect.width > 0 && rect.height > 0) {
      spectrumCanvas.value.width = rect.width
      spectrumCanvas.value.height = rect.height
    } else {
      spectrumCanvas.value.width = 600
      spectrumCanvas.value.height = 180
    }
  }
  lastSpectrumDrawTime = 0
  spectrumAnimId = requestAnimationFrame(renderSpectrumFrame)
}

function stopSpectrumAnimation() {
  if (spectrumAnimId) {
    cancelAnimationFrame(spectrumAnimId)
    spectrumAnimId = null
  }
  lastSpectrumDrawTime = 0
  if (spectrumCanvas.value) {
    try {
      const ctx = cachedSpectrumCanvas === spectrumCanvas.value && cachedSpectrumCtx
        ? cachedSpectrumCtx
        : (spectrumCanvas.value.getContext ? spectrumCanvas.value.getContext('2d') : null)
      if (ctx && typeof ctx.clearRect === 'function') {
        ctx.clearRect(0, 0, spectrumCanvas.value.width || 0, spectrumCanvas.value.height || 0)
      }
    } catch {
      // ignore
    }
  }
}

function onDocumentVisibilityChange() {
  if (shouldRunSpectrum()) {
    if (!spectrumAnimId) startSpectrumAnimation()
  } else {
    stopSpectrumAnimation()
  }
}

watch([() => props.fullscreen, isAudioOnly, hasVideoStream, mirrorStreamFailed, () => player.isPlaying], () => {
  if (shouldRunSpectrum()) {
    startSpectrumAnimation()
  } else {
    stopSpectrumAnimation()
  }
}, { immediate: true })

watch(spectrumCanvas, (canvas) => {
  resetSpectrumCache()
  if (canvas && shouldRunSpectrum()) {
    startSpectrumAnimation()
  }
})

function onGlobalUserGesture() {
  triggerAudioWakeGesture()
}

onMounted(() => {
  applyMediaSource()
  if (typeof window !== 'undefined') {
    window.addEventListener('pointerdown', onGlobalUserGesture, { capture: true })
    window.addEventListener('click', onGlobalUserGesture, { capture: true })
    window.addEventListener('keydown', onGlobalUserGesture, { capture: true })
  }
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onDocumentVisibilityChange)
  }
})

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('pointerdown', onGlobalUserGesture, { capture: true })
    window.removeEventListener('click', onGlobalUserGesture, { capture: true })
    window.removeEventListener('keydown', onGlobalUserGesture, { capture: true })
  }
  if (typeof document !== 'undefined') {
    document.removeEventListener('visibilitychange', onDocumentVisibilityChange)
  }
  stopSpectrumAnimation()
  resetSpectrumCache()
  clearTimeout(osdTimer)
  clearTimeout(effectTimer)
  clearLivePrebuffer()
  clearLiveStallWatchdog()
  lyricLoader.dispose()
  channelRouter.dispose()
})
</script>

<style scoped>
.seek-buffer-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.58);
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
  gap: 10px;
  z-index: 10;
  pointer-events: none;
}
.seek-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid rgba(255, 255, 255, 0.2);
  border-top-color: #ffc64b;
  border-radius: 50%;
  animation: seek-spin 0.8s linear infinite;
}
@keyframes seek-spin {
  to { transform: rotate(360deg); }
}

.media-stage {
  position: fixed;
  z-index: 30;
  border-radius: 2px;
  overflow: hidden;
  background: #060910;
  transition: border-radius 0.2s ease;
  cursor: pointer;
}
.media-stage.fullscreen {
  inset: 0 !important;
  width: 100vw !important;
  height: 100vh !important;
  border-radius: 0;
  z-index: 50;
  cursor: default;
}
.ktv-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.media-stage.fullscreen .ktv-video {
  object-fit: contain;
}
.pip-overlay-caption {
  position: absolute;
  inset: auto 0 6px 0;
  margin: 0 auto;
  width: fit-content;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  font-size: 11px;
  opacity: 0;
  transition: opacity 0.18s ease;
  pointer-events: none;
  z-index: 2;
}
.media-stage:not(.fullscreen):hover .pip-overlay-caption {
  opacity: 1;
}
.effect-stage-toast {
  position: fixed;
  top: 72px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 68;
  padding: 8px 18px;
  border-radius: 999px;
  background: rgba(15, 21, 35, 0.9);
  border: 1px solid rgba(255, 198, 75, 0.65);
  color: var(--gold2);
  font-size: 15px;
  font-weight: 800;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.5);
  pointer-events: none;
}
.audio-spectrum-stage {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  z-index: 1;
}
.spectrum-bg-kenburns {
  position: absolute;
  inset: -10%;
  background-size: cover;
  background-position: center;
  filter: blur(20px) brightness(0.45);
  animation: kenburnsBreath 18s ease-in-out infinite alternate;
  will-change: transform;
}
@keyframes kenburnsBreath {
  0% { transform: scale(1); }
  50% { transform: scale(1.08) translate(-1%, 1%); }
  100% { transform: scale(1.15) translate(1%, -1%); }
}
.spectrum-scrim {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at center, rgba(15, 23, 42, 0.3) 0%, rgba(5, 8, 22, 0.88) 100%);
}
.spectrum-canvas {
  position: absolute;
  bottom: 186px;
  left: 5%;
  width: 90%;
  height: 180px;
  pointer-events: none;
  z-index: 2;
}

.poster-fallback {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: radial-gradient(circle at center, rgba(35, 45, 70, 0.9), #070a12);
}
.poster-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.32;
  filter: blur(8px);
}
.poster-badge {
  position: relative;
  text-align: center;
  padding: 8px 14px;
}
.poster-badge strong { display: block; font-size: 14px; color: #fff; }
.poster-badge span { display: block; font-size: 11px; color: var(--dim); margin-top: 2px; }
.poster-badge small { display: block; font-size: 10px; color: var(--gold2); margin-top: 4px; }
.media-stage.fullscreen .poster-badge strong { font-size: 24px; }
.media-stage.fullscreen .poster-badge span { font-size: 14px; }

.autoplay-unlock {
  position: fixed;
  top: 60px;
  right: 16px;
  z-index: 65;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 999px;
  background: var(--gold);
  color: #191304;
  font-size: 13px;
  font-weight: 800;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
}

/* 全屏顶部和底部控制面板（1:1 对标 view_ktv_fullscreen_playback_panel.xml） */
.fs-topbar {
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 88px;
  padding: 0 24px;
  gap: 20px;
  display: flex;
  align-items: center;
  overflow: hidden;
  background: var(--iktv-panel-bg);
  transition: opacity 0.25s ease;
  z-index: 4;
}
.fs-back-btn {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 16px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.22);
  background: rgba(255, 255, 255, 0.1);
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
  transition: background-color 0.18s ease, border-color 0.18s ease;
}
.fs-back-btn:hover,
.fs-back-btn:focus-visible {
  background: var(--iktv-blue);
  border-color: var(--iktv-blue);
  outline: none;
}
.fs-topbar.hidden,
.fs-bottom-panel.hidden {
  opacity: 0;
  pointer-events: none;
}
.fs-now-marquee {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}
.fs-now-text {
  display: inline-block;
  font-size: clamp(22px, 2.3vw, 34px);
  font-weight: 700;
  color: #ffffff;
  white-space: nowrap;
  letter-spacing: 0.5px;
  will-change: transform;
  animation: fs-marquee-scroll var(--marquee-duration, 14s) linear infinite;
}
.fs-topbar.hidden .fs-now-text {
  animation-play-state: paused;
}
@keyframes fs-marquee-scroll {
  0% {
    transform: translate3d(100vw, 0, 0);
  }
  100% {
    transform: translate3d(-100%, 0, 0);
  }
}
@media (prefers-reduced-motion: reduce) {
  .fs-now-marquee {
    padding: 0 24px;
    text-align: right;
  }
  .fs-now-text {
    animation: none;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.fs-lyrics {
  position: absolute;
  left: 8%;
  right: 8%;
  bottom: 190px;
  height: 150px;
  overflow: hidden;
  pointer-events: none;
  text-align: center;
  z-index: 3;
}
.fs-lyrics.audio-center {
  bottom: 230px;
  height: 220px;
}
.lyric-empty { margin-top: 50px; font-size: 13px; color: var(--dim); pointer-events: auto; }
.lyric-empty button { color: var(--iktv-blue); text-decoration: underline; }

.fs-bottom-panel {
  position: absolute;
  bottom: 0; left: 0; right: 0;
  padding: 12px 44px calc(12px + var(--safe-bottom));
  background: var(--iktv-panel-bg);
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: opacity 0.25s ease;
  z-index: 4;
}
.fs-progress-row {
  display: flex;
  align-items: center;
  gap: 14px;
  height: 46px;
}
.fs-seek-btn {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  flex: none;
}
.fs-seek-ring {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  transition: background-color 0.18s ease, box-shadow 0.18s ease;
}
.fs-seek-btn:hover .fs-seek-ring,
.fs-seek-btn:focus-visible .fs-seek-ring {
  background: var(--iktv-blue);
  box-shadow: 0 0 14px var(--iktv-blue-glow);
}
.fs-seek-btn:focus-visible { outline: none; }
.fs-seek-png {
  width: 28px;
  height: 28px;
  object-fit: contain;
}
.time-lbl {
  width: 58px;
  text-align: center;
  font-size: 14px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.92);
  font-variant-numeric: tabular-nums;
  flex: none;
}
.fs-slider {
  flex: 1;
  -webkit-appearance: none;
  appearance: none;
  height: 6px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    var(--iktv-green) 0%,
    var(--iktv-green) var(--seek-pct, 0%),
    var(--iktv-track) var(--seek-pct, 0%),
    var(--iktv-track) 100%
  );
  cursor: pointer;
}
.fs-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--iktv-green);
  border: 2px solid #86efac;
  box-shadow: 0 0 12px rgba(49, 194, 124, 0.72);
  cursor: pointer;
}
.fs-slider::-moz-range-thumb {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--iktv-green);
  border: 2px solid #86efac;
  box-shadow: 0 0 12px rgba(49, 194, 124, 0.72);
  cursor: pointer;
}
.fs-action-row {
  display: grid;
  grid-template-columns: repeat(10, minmax(0, 1fr));
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
  gap: 8px;
  align-items: start;
}
.fs-act {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 6px;
  padding: 4px 2px 6px;
  background: transparent;
  border: none;
  color: #ffffff;
  font-size: 13px;
  font-weight: 500;
}
.fs-act-icon {
  position: relative;
  width: 58px;
  height: 58px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  border: 2px solid transparent;
  transition: background-color 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}
.fs-act-png {
  width: 36px;
  height: 36px;
  object-fit: contain;
}
.fs-play-act .fs-act-icon {
  background: var(--iktv-blue);
  box-shadow: 0 0 18px var(--iktv-blue-glow);
}
.fs-action-row:has(.fs-act:hover) .fs-play-act:not(:hover) .fs-act-icon,
.fs-action-row:has(.fs-act:focus-visible) .fs-play-act:not(:focus-visible):not(:hover) .fs-act-icon {
  background: transparent;
  box-shadow: none;
}
.fs-act:hover .fs-act-icon,
.fs-act:focus-visible .fs-act-icon {
  background: var(--iktv-blue);
  box-shadow: 0 0 18px var(--iktv-blue-glow);
  transform: scale(1.06);
}
.fs-act:focus-visible { outline: none; }
.fs-act.active:not(.fs-play-act) .fs-act-icon {
  border-color: rgba(49, 130, 251, 0.72);
  background: rgba(49, 130, 251, 0.22);
}
.fs-act:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}
.fs-act:disabled:hover .fs-act-icon {
  background: transparent;
  box-shadow: none;
  transform: none;
}
.fs-queue-badge {
  position: absolute;
  top: 2px;
  right: 0;
  min-width: 19px;
  height: 19px;
  padding: 0 5px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  font-style: normal;
  font-weight: 800;
  display: grid;
  place-items: center;
}
@media (max-width: 759px) {
  .fs-topbar {
    height: 60px;
    padding: 0;
  }
  .fs-bottom-panel {
    padding: 10px 12px calc(12px + var(--safe-bottom));
    gap: 8px;
  }
  .fs-progress-row {
    gap: 6px;
    height: 38px;
  }
  .fs-seek-btn { width: 34px; height: 34px; }
  .fs-seek-ring { width: 32px; height: 32px; }
  .fs-seek-png { width: 22px; height: 22px; }
  .time-lbl { width: 44px; font-size: 12px; }
  .fs-action-row {
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 6px 4px;
  }
  .fs-act { font-size: 11px; gap: 3px; }
  .fs-act-icon { width: 42px; height: 42px; }
  .fs-act-png { width: 26px; height: 26px; }
}

.fs-autoplay-banner {
  position: absolute;
  top: 22%;
  left: 50%;
  transform: translateX(-50%);
  z-index: 45;
  cursor: pointer;
  pointer-events: auto;
}
.banner-box {
  display: flex;
  align-items: center;
  gap: 12px;
  background: rgba(18, 24, 38, 0.9);
  border: 1px solid rgba(255, 198, 75, 0.5);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.6), 0 0 16px rgba(255, 198, 75, 0.25);
  padding: 12px 22px;
  border-radius: 999px;
  color: #fff;
  transition: all 0.2s ease;
}
.banner-box:hover {
  background: rgba(28, 36, 56, 0.98);
  border-color: #ffc64b;
  transform: scale(1.03);
}
.banner-icon {
  color: #ffc64b;
  flex-shrink: 0;
}
.pulse {
  animation: banner-pulse 1.6s infinite;
}
@keyframes banner-pulse {
  0% { transform: scale(0.95); opacity: 0.8; }
  50% { transform: scale(1.1); opacity: 1; }
  100% { transform: scale(0.95); opacity: 0.8; }
}
.banner-texts {
  display: flex;
  flex-direction: column;
}
.banner-texts strong {
  font-size: 14px;
  color: #ffc64b;
}
.banner-texts small {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.8);
}
</style>
