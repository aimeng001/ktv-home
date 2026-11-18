<template>
  <!-- 统一样式舞台：小窗时固定对齐底部 Dock 16:9 框位置；全屏时铺满全屏视口，始终复用同一个 <video> 实例 -->
  <div
    class="media-stage"
    :class="{ fullscreen, 'has-song': Boolean(song), 'audio-only': isAudioOnly }"
    :style="stageStyle"
    @mousemove="wakeOsd"
    @click="onStageClick"
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
      @ended="onVideoEnded"
      @error="onVideoError"
      @playing="onVideoPlaying"
    />

    <!-- 音频曲目 / 镜像流不可用降级封面展示 -->
    <div v-if="song && (!hasVideoStream || mirrorStreamFailed || isAudioOnly)" class="poster-fallback">
      <img v-if="song.coverUrl" :src="song.coverUrl" alt="" class="poster-img" />
      <div class="poster-badge">
        <strong>{{ song.title }}</strong>
        <span>{{ song.artist }}</span>
        <small v-if="mirrorStreamFailed">已切换为封面与实时歌词预览</small>
      </div>
    </div>

    <!-- 小窗悬浮歌名栏（避免被固定 video 层遮挡） -->
    <div v-if="!fullscreen && song" class="pip-overlay-caption">
      <strong>{{ song.title }}</strong>
      <small>{{ player.isTvConsole ? 'TV 同步预览 · 点击全屏' : '点击全屏演唱' }}</small>
    </div>

    <!-- 全屏覆盖层：顶部信息、实时歌词、底部控制面板（对标 view_ktv_fullscreen_playback_panel.xml） -->
    <template v-if="fullscreen">
      <div class="fs-topbar" :class="{ hidden: !osdVisible }">
        <button type="button" class="fs-back-btn" @click.stop="$emit('exit-fullscreen')">
          <Minimize2 :size="16" /> 返回选歌
        </button>
        <div class="fs-now">
          <strong>{{ song?.title || '暂无播放' }}</strong>
          <small v-if="song">{{ song.artist }} · {{ player.nowPlaying?.orderedByNick || '' }} 点</small>
          <small v-if="nextSong" class="next-hint">下一首：{{ nextSong.title }}</small>
        </div>
        <div class="fs-badges">
          <span class="fs-mode-tag">{{ player.isTvConsole ? 'TV 控制台镜像' : 'Web KTV 全屏' }}</span>
          <button type="button" class="fs-chip" @click.stop="showLyrics = !showLyrics">
            {{ showLyrics ? '隐藏歌词' : '显示歌词' }}
          </button>
        </div>
      </div>

      <!-- 实时滚动歌词 -->
      <div v-if="showLyrics && song" class="fs-lyrics" :class="{ 'audio-center': isAudioOnly || mirrorStreamFailed }">
        <div v-if="lyricError" class="lyric-empty">歌词加载失败 · <button type="button" @click.stop="retryLyric">重试</button></div>
        <div v-else-if="!lyricLines.length" class="lyric-empty">暂无歌词，享受现场伴奏</div>
        <div v-else class="lyric-window">
          <div class="lyric-track" :style="{ transform: `translateY(${lyricOffsetPx}px)` }">
            <div v-for="(line, idx) in lyricLines" :key="idx" class="lyric-line" :class="{ cur: idx === activeLyricIdx }">
              <template v-if="idx === activeLyricIdx && line.words?.length">
                <span v-for="(w, wi) in line.words" :key="wi" :class="{ sung: w.time <= displayPositionMs }">{{ w.text }}</span>
              </template>
              <template v-else>{{ line.text }}</template>
            </div>
          </div>
        </div>
      </div>

      <!-- 全屏底部控台 -->
      <div class="fs-bottom-panel" :class="{ hidden: !osdVisible }" @click.stop>
        <div class="fs-progress-row">
          <button type="button" class="fs-seek-btn" aria-label="快退 10 秒" @click="seekBy(-10000)">-10s</button>
          <span class="time-lbl">{{ formatDurationMs(displayPositionMs) }}</span>
          <input
            type="range"
            min="0"
            :max="durationMs || 1"
            :value="Math.min(displayPositionMs, durationMs || 1)"
            class="fs-slider"
            @input="onScrubInput"
            @change="onScrubCommit"
          />
          <span class="time-lbl">{{ formatDurationMs(durationMs) }}</span>
          <button type="button" class="fs-seek-btn" aria-label="快进 10 秒" @click="seekBy(10000)">+10s</button>
          <button type="button" class="fs-seek-btn" aria-label="降调" @click="$emit('pitch-delta', -1)">降调</button>
          <button type="button" class="fs-seek-btn" aria-label="升调" @click="$emit('pitch-delta', 1)">升调</button>
        </div>

        <div class="fs-action-row">
          <button type="button" class="fs-act" @click="$emit('restart')">重唱</button>
          <button type="button" class="fs-act primary" @click="$emit('toggle-play')">
            {{ player.isPlaying ? '暂停' : '播放' }}
          </button>
          <button type="button" class="fs-act" @click="$emit('next')">切歌</button>
          <button type="button" class="fs-act" :disabled="!canVocal" @click="$emit('toggle-vocal')">
            {{ player.vocalMode === 'original' ? '原唱' : '伴唱' }}
          </button>
          <button v-if="canVocal" type="button" class="fs-act" @click="$emit('swap-vocal')">纠正音轨</button>
          <button type="button" class="fs-act" @click="$emit('toggle-mute')">
            {{ player.muted ? '取消静音' : '静音' }}
          </button>
          <button type="button" class="fs-act" @click="$emit('volume-delta', -10)">音量-</button>
          <button type="button" class="fs-act" @click="$emit('volume-delta', 10)">音量+</button>
          <button type="button" class="fs-act" @click="$emit('open-mixer')">音效</button>
          <button type="button" class="fs-act" @click="$emit('open-queue')">已点 ({{ player.queueCount }})</button>
          <button type="button" class="fs-act" @click="$emit('open-browser')">点歌台</button>
          <button type="button" class="fs-act" @click="$emit('open-home')">首页</button>
        </div>
      </div>
    </template>
  </div>

  <!-- Web 播放器 Autoplay 声音锁定解锁提示（独立于 stage 透明度避免被隐藏） -->
  <button
    v-if="player.isWebHost && player.audioBlockedByAutoplay && song"
    type="button"
    class="autoplay-unlock"
    @click.stop="unlockAudio"
  >
    <Volume2 :size="16" /> 点击开启 KTV 伴奏声音
  </button>

  <!-- 现场氛围互动音效浮层（响应 WS effect_play） -->
  <div v-if="effectBanner" class="effect-stage-toast" role="status" aria-live="polite">
    {{ effectBanner }}
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Minimize2, Volume2 } from 'lucide-vue-next'
import { usePlayerStore } from '../../stores/player'
import { parseLrc } from '../../composables/lrc'
import { createLyricLoader } from '../../views/lyricLoader'
import { currentLyricIndex } from '../../views/lyricState'
import { buildWebStreamSpec } from '../../player/streamSelection'
import { createWebAudioChannelRouter } from '../../player/webAudioChannelRouter'
import { formatDurationMs } from './ktvCatalogPresets'

const props = defineProps({
  fullscreen: { type: Boolean, default: false },
  pipRect: { type: Object, default: null },
})
const emit = defineEmits([
  'enter-fullscreen',
  'exit-fullscreen',
  'toggle-play',
  'toggle-vocal',
  'swap-vocal',
  'toggle-mute',
  'restart',
  'next',
  'seek',
  'volume-delta',
  'pitch-delta',
  'open-mixer',
  'open-queue',
  'open-browser',
  'open-home',
])

const player = usePlayerStore()
const videoEl = ref(null)
const channelRouter = createWebAudioChannelRouter()

const forceTranscodeFallback = ref(false)
const mirrorStreamFailed = ref(false)
const liveStartSec = ref(0)
const streamReloadNonce = ref(0)
const detectedDurationMs = ref(0)
const scrubbingValue = ref(null)
const osdVisible = ref(true)
const showLyrics = ref(true)
const effectBanner = ref('')
let osdTimer = null
let effectTimer = null
let lastProgressSentAt = 0
let lastAppliedSeq = -1
let lastStreamKey = ''
let awaitingMediaReload = false

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
const canVocal = computed(() => (
  song.value?.hasVocalTrack === true
  || ['DUAL_TRACK', 'DUAL_CHANNEL'].includes(player.audioLayout?.layout)
))

const streamSpec = computed(() => buildWebStreamSpec({
  playback: player.playback,
  audioLayout: player.audioLayout,
  vocalMode: player.vocalMode,
  startSeconds: liveStartSec.value,
  forceTranscode: forceTranscodeFallback.value,
  song: song.value,
}))

const hasVideoStream = computed(() => Boolean(streamSpec.value.url))
const isAudioOnly = computed(() => streamSpec.value.isAudioOnly)
const effectiveMuted = computed(() => !player.isWebHost || player.muted || player.audioBlockedByAutoplay)
const durationMs = computed(() => song.value?.durationMs || detectedDurationMs.value || 0)
const displayPositionMs = computed(() => scrubbingValue.value != null ? scrubbingValue.value : player.positionMs)

const activeLyricIdx = computed(() => currentLyricIndex(lyricLines.value, displayPositionMs.value))
const lyricOffsetPx = computed(() => activeLyricIdx.value < 0 ? 0 : Math.max(0, activeLyricIdx.value - 2) * -36)

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
  osdVisible.value = true
  clearTimeout(osdTimer)
  osdTimer = setTimeout(() => { osdVisible.value = false }, 5000)
}

function onStageClick() {
  if (!props.fullscreen) {
    emit('enter-fullscreen')
    return
  }
  osdVisible.value = !osdVisible.value
  if (osdVisible.value) wakeOsd()
}

function syncAudioRouter() {
  const el = videoEl.value
  if (!el) return
  if (player.isWebHost) {
    channelRouter.attach(el)
    channelRouter.setRouting(player.audioLayout, player.vocalMode)
    channelRouter.setGain(player.volume, effectiveMuted.value)
    if (el.volume !== undefined) el.volume = Math.max(0, Math.min(1, (player.volume ?? 60) / 100))
    el.muted = effectiveMuted.value
  } else {
    channelRouter.setGain(0, true)
    el.muted = true
  }
}

async function applyMediaSource() {
  const el = videoEl.value
  if (!el) return
  const nextUrl = streamSpec.value.url
  const queueId = player.nowPlaying?.queueId || 0
  const key = `${queueId}|${nextUrl}|${streamReloadNonce.value}`
  if (key === lastStreamKey) return
  lastStreamKey = key

  if (!nextUrl) {
    awaitingMediaReload = false
    if (el.getAttribute('src')) {
      el.pause()
      el.removeAttribute('src')
      el.load()
    }
    return
  }

  awaitingMediaReload = true
  mirrorStreamFailed.value = false
  if (el.getAttribute('src') === nextUrl) {
    if (typeof el.load === 'function') el.load()
  } else {
    el.src = nextUrl
  }
  syncAudioRouter()
  if (player.isPlaying) {
    await attemptPlay()
  }
}

async function attemptPlay() {
  const el = videoEl.value
  if (!el || !streamSpec.value.url) return
  try {
    syncAudioRouter()
    if (player.isWebHost) await channelRouter.resume()
    await el.play()
    if (player.isWebHost && !player.muted && channelRouter.isSuspended()) {
      player.audioBlockedByAutoplay = true
    } else if (player.isWebHost) {
      player.audioBlockedByAutoplay = false
    }
  } catch (err) {
    if (player.isWebHost && err?.name === 'NotAllowedError') {
      player.audioBlockedByAutoplay = true
      el.muted = true
      try { await el.play() } catch { /* wait for user tap */ }
    }
  }
}

async function unlockAudio() {
  player.audioBlockedByAutoplay = false
  const el = videoEl.value
  if (!el) return
  el.muted = player.muted
  syncAudioRouter()
  await channelRouter.resume()
  if (player.isPlaying) {
    el.play().catch(() => {})
  }
}

function onLoadedMetadata() {
  awaitingMediaReload = false
  const el = videoEl.value
  if (!el) return
  if (Number.isFinite(el.duration) && el.duration > 0 && el.duration < 86400) {
    detectedDurationMs.value = Math.round(el.duration * 1000)
  }
  const targetSec = (player.positionMs || 0) / 1000
  if (!streamSpec.value.liveTranscode && targetSec > 1 && Number.isFinite(el.duration) && targetSec < el.duration) {
    try { el.currentTime = targetSec } catch { /* ignore */ }
  }
  if (player.isPlaying) attemptPlay()
}

function onVideoPlaying() {
  awaitingMediaReload = false
  if (player.audioBlockedByAutoplay && player.isWebHost && !player.muted) {
    // Wait for explicit user gesture if blocked
  }
}

function onTimeUpdate() {
  const el = videoEl.value
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
  if (!player.isWebHost || !player.nowPlaying?.queueId) return
  player.reportFinished(player.nowPlaying.queueId)
}

function onVideoError() {
  if (!streamSpec.value.url) return
  if (!player.isWebHost) {
    mirrorStreamFailed.value = true
    return
  }
  if (!forceTranscodeFallback.value && !isAudioOnly.value) {
    const startSec = Math.max(0, (player.positionMs || 0) / 1000)
    const fallbackSpec = buildWebStreamSpec({
      playback: player.playback,
      audioLayout: player.audioLayout,
      vocalMode: player.vocalMode,
      startSeconds: startSec,
      forceTranscode: true,
      song: song.value,
    })
    if (fallbackSpec.url && fallbackSpec.url !== streamSpec.value.url) {
      liveStartSec.value = startSec
      forceTranscodeFallback.value = true
      return
    }
  }
  const fileId = player.playback?.sourceFileId ?? null
  const queueId = player.nowPlaying?.queueId
  if (queueId) {
    player.reportPlayError(fileId, queueId, '浏览器无法解码当前视频流')
  }
}

function onScrubInput(e) {
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
  })
  return baseSpec.liveTranscode ? posSec : 0
}

watch(() => player.nowPlaying?.queueId, (newId, oldId) => {
  if (newId !== oldId) {
    awaitingMediaReload = Boolean(newId)
    forceTranscodeFallback.value = false
    mirrorStreamFailed.value = false
    liveStartSec.value = newId ? resolveInitialLiveStartSec() : 0
    detectedDurationMs.value = 0
    lastAppliedSeq = player.seekSequence
    if (song.value?.id) lyricLoader.load(song.value.id)
    else lyricLoader.dispose()
  }
}, { immediate: true })

watch(() => player.vocalMode, () => {
  syncAudioRouter()
  if (player.audioLayout?.layout === 'DUAL_TRACK' && player.nowPlaying) {
    awaitingMediaReload = true
    liveStartSec.value = Math.max(0, (player.positionMs || 0) / 1000)
  }
})

watch([() => streamSpec.value.url, () => player.nowPlaying?.queueId, streamReloadNonce], () => {
  applyMediaSource()
}, { flush: 'post' })

watch(() => player.state, (state) => {
  const el = videoEl.value
  if (!el || !streamSpec.value.url) return
  if (state === 'playing') attemptPlay()
  else el.pause()
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
    if (player.isPlaying) attemptPlay()
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
  const el = videoEl.value
  if (!el) return
  const targetSec = Math.max(0, (player.positionMs || 0) / 1000)
  if (streamSpec.value.liveTranscode) {
    awaitingMediaReload = true
    if (Math.abs(targetSec - liveStartSec.value) < 0.5) {
      liveStartSec.value = targetSec
      streamReloadNonce.value += 1
    } else {
      liveStartSec.value = targetSec
    }
  } else {
    try { el.currentTime = targetSec } catch { /* ignore */ }
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

onMounted(() => {
  applyMediaSource()
})

onBeforeUnmount(() => {
  clearTimeout(osdTimer)
  clearTimeout(effectTimer)
  lyricLoader.dispose()
  channelRouter.dispose()
})
</script>

<style scoped>
.media-stage {
  position: fixed;
  z-index: 30;
  border-radius: 10px;
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
  object-fit: contain;
  display: block;
}
.pip-overlay-caption {
  position: absolute;
  inset: auto 0 0 0;
  padding: 8px 8px 4px;
  background: linear-gradient(180deg, transparent, rgba(5, 8, 14, 0.92));
  display: flex;
  flex-direction: column;
  pointer-events: none;
  z-index: 2;
}
.pip-overlay-caption strong {
  font-size: 11px;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pip-overlay-caption small {
  font-size: 9px;
  color: var(--gold2);
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

/* 全屏顶部和底部控制面板 */
.fs-topbar {
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 76px;
  padding: 12px 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  background: linear-gradient(180deg, rgba(6, 9, 16, 0.9), transparent);
  transition: opacity 0.25s ease;
  z-index: 4;
}
.fs-topbar.hidden,
.fs-bottom-panel.hidden {
  opacity: 0;
  pointer-events: none;
}
.fs-back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.2);
  font-size: 13px;
  font-weight: 700;
}
.fs-now {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.fs-now strong { font-size: 18px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fs-now small { color: var(--dim); font-size: 12px; }
.fs-now .next-hint { color: var(--gold2); }
.fs-badges { display: flex; align-items: center; gap: 8px; }
.fs-mode-tag {
  padding: 5px 10px; border-radius: 999px; font-size: 11px;
  background: rgba(255, 198, 75, 0.18); border: 1px solid rgba(255, 198, 75, 0.45); color: var(--gold2);
}
.fs-chip {
  padding: 5px 10px; border-radius: 999px; font-size: 12px;
  background: rgba(255, 255, 255, 0.12);
}

.fs-lyrics {
  position: absolute;
  left: 10%;
  right: 10%;
  bottom: 150px;
  height: 150px;
  overflow: hidden;
  pointer-events: none;
  text-align: center;
  z-index: 3;
}
.fs-lyrics.audio-center {
  bottom: 210px;
  height: 220px;
}
.lyric-window { height: 100%; overflow: hidden; }
.lyric-track { transition: transform 0.25s ease; padding-top: 42px; }
.lyric-line {
  height: 36px;
  line-height: 36px;
  font-size: 16px;
  color: rgba(255, 255, 255, 0.62);
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.9);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.lyric-line.cur {
  color: var(--gold);
  font-size: 20px;
  font-weight: 800;
}
.lyric-line .sung { color: var(--gold2); }
.lyric-empty { margin-top: 50px; font-size: 13px; color: var(--dim); pointer-events: auto; }
.lyric-empty button { color: var(--gold); text-decoration: underline; }

.fs-bottom-panel {
  position: absolute;
  bottom: 0; left: 0; right: 0;
  padding: 16px 24px calc(16px + var(--safe-bottom));
  background: linear-gradient(0deg, rgba(6, 9, 16, 0.94) 65%, transparent);
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: opacity 0.25s ease;
  z-index: 4;
}
.fs-progress-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.fs-seek-btn {
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.12);
  font-size: 12px;
  font-weight: 700;
}
.time-lbl { width: 46px; text-align: center; font-size: 12px; color: var(--dim); font-variant-numeric: tabular-nums; }
.fs-slider { flex: 1; accent-color: var(--gold); }
.fs-action-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}
.fs-act {
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.16);
  font-size: 13px;
  font-weight: 600;
}
.fs-act.primary {
  background: var(--gold);
  color: #191304;
  border-color: var(--gold);
  font-weight: 800;
}
.fs-act:disabled { opacity: 0.45; }
</style>
