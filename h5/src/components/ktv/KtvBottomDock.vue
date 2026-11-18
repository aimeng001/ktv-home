<template>
  <footer class="ktv-bottom-dock" aria-label="底部播控栏">
    <!-- 左侧 5 个圆形控制按钮 -->
    <div class="dock-group dock-left">
      <button type="button" class="dock-circle" :aria-label="player.isPlaying ? '暂停' : '播放'" @click="$emit('toggle-play')">
        <Pause v-if="player.isPlaying" :size="20" />
        <Play v-else :size="20" />
        <span>{{ player.isPlaying ? '暂停' : '播放' }}</span>
      </button>

      <button
        type="button"
        class="dock-circle"
        :class="{ active: player.vocalMode === 'accompaniment', disabled: !canVocal }"
        :aria-label="player.vocalMode === 'original' ? '当前原唱，点击切换伴唱' : '当前伴唱，点击切换原唱'"
        @click="$emit('toggle-vocal')"
      >
        <Mic2 :size="20" />
        <span>{{ player.vocalMode === 'original' ? '原唱' : '伴唱' }}</span>
      </button>

      <button type="button" class="dock-circle" aria-label="重唱" @click="$emit('restart')">
        <img :src="assets.iconReplay" alt="" class="dock-png" />
        <span>重唱</span>
      </button>

      <button type="button" class="dock-circle" aria-label="切歌" @click="$emit('next')">
        <img :src="assets.iconNext" alt="" class="dock-png" />
        <span>切歌</span>
      </button>

      <button type="button" class="dock-circle" aria-label="减小音量" @click="$emit('volume-delta', -8)">
        <img :src="assets.iconVolDown" alt="" class="dock-png" />
        <span>音量-</span>
      </button>
    </div>

    <!-- 中央 16:9 实时 MV 小窗与左右梯形羽化阴影 -->
    <div class="pip-stage-wrap">
      <img :src="assets.shadowLeft" alt="" class="pip-shadow left" />
      <div
        ref="pipSlotEl"
        class="video-container"
        role="button"
        tabindex="0"
        aria-label="展开全屏 MV 播放"
        @click="$emit('enter-fullscreen')"
        @keydown.enter.prevent="$emit('enter-fullscreen')"
      >
        <slot name="pip" />
        <div v-if="!player.nowPlaying" class="pip-idle">
          <img
            :src="effectiveStandbyLogo"
            alt="待机画面"
            class="pip-idle-img"
            @error="logoLoadFailed = true"
          />
          <span>空闲待点歌</span>
        </div>
        <div v-else class="pip-caption">
          <strong>{{ player.nowPlaying.song?.title }}</strong>
          <small>{{ player.isTvConsole ? 'TV 同步预览 · 点击全屏' : '点击全屏演唱' }}</small>
        </div>
      </div>
      <img :src="assets.shadowRight" alt="" class="pip-shadow right" />
    </div>

    <!-- 右侧 5 个圆形控制按钮 -->
    <div class="dock-group dock-right">
      <button type="button" class="dock-circle" aria-label="增加音量" @click="$emit('volume-delta', 8)">
        <img :src="assets.iconVolUp" alt="" class="dock-png" />
        <span>音量+</span>
      </button>

      <button type="button" class="dock-circle" aria-label="伴奏降调" @click="$emit('pitch-delta', -1)">
        <img :src="assets.iconToneDown" alt="" class="dock-png" />
        <span>降调</span>
      </button>

      <button type="button" class="dock-circle" aria-label="伴奏升调" @click="$emit('pitch-delta', 1)">
        <img :src="assets.iconToneUp" alt="" class="dock-png" />
        <span>升调</span>
      </button>

      <button type="button" class="dock-circle" aria-label="氛围音效与音轨说明" @click="$emit('open-mixer')">
        <img :src="assets.iconDownload" alt="" class="dock-png" />
        <span>音效/工具</span>
      </button>

      <button type="button" class="dock-circle queue-btn" aria-label="打开已点队列抽屉" @click="$emit('open-queue')">
        <ListMusic :size="20" />
        <span>已点</span>
        <em class="queue-badge">{{ player.queueCount }}</em>
      </button>
    </div>
  </footer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ListMusic, Mic2, Pause, Play } from 'lucide-vue-next'
import { KTV_ASSETS } from './ktvCatalogPresets'
import { usePlayerStore } from '../../stores/player'

const props = defineProps({
  standbyLogoUrl: { type: String, default: '' }
})

defineEmits([
  'toggle-play',
  'toggle-vocal',
  'restart',
  'next',
  'volume-delta',
  'pitch-delta',
  'open-mixer',
  'open-queue',
  'enter-fullscreen',
])

const player = usePlayerStore()
const assets = KTV_ASSETS
const pipSlotEl = ref(null)
const logoLoadFailed = ref(false)

watch(() => props.standbyLogoUrl, () => {
  logoLoadFailed.value = false
})

const effectiveStandbyLogo = computed(() => (
  !logoLoadFailed.value && props.standbyLogoUrl
    ? props.standbyLogoUrl
    : assets.standbyBanner
))

const canVocal = computed(() => (
  player.nowPlaying?.song?.hasVocalTrack === true
  || ['DUAL_TRACK', 'DUAL_CHANNEL'].includes(player.audioLayout?.layout)
))

defineExpose({ pipSlotEl })
</script>

<style scoped>
.ktv-bottom-dock {
  height: 118px;
  padding: 6px 14px 10px;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 8px;
  background: linear-gradient(0deg, rgba(8, 11, 20, 0.94), rgba(10, 14, 24, 0.58) 70%, transparent);
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  flex: none;
  z-index: 20;
}
.dock-group {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  height: 92px;
}
.dock-circle {
  position: relative;
  border-radius: 50%;
  max-width: 88px;
  width: 100%;
  height: 88px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  background: radial-gradient(circle at 50% 35%, rgba(255, 255, 255, 0.14), rgba(16, 22, 36, 0.85));
  border: 1.5px solid rgba(255, 255, 255, 0.22);
  color: var(--text);
  font-size: 12px;
  font-weight: 600;
  transition: var(--transition);
}
.dock-circle:hover,
.dock-circle:focus-visible {
  border-color: var(--gold);
  box-shadow: 0 0 0 2px rgba(255, 198, 75, 0.28);
  transform: translateY(-2px);
  outline: none;
}
.dock-circle.active {
  border-color: rgba(255, 198, 75, 0.72);
  color: var(--gold2);
}
.dock-circle.disabled {
  opacity: 0.55;
}
.dock-png {
  width: 24px;
  height: 24px;
  object-fit: contain;
}
.queue-badge {
  position: absolute;
  top: 8px;
  right: 10px;
  min-width: 20px;
  height: 20px;
  padding: 0 5px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  font-style: normal;
  font-weight: 800;
  display: grid;
  place-items: center;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.45);
}

.pip-stage-wrap {
  display: flex;
  align-items: center;
  height: 104px;
}
.pip-shadow {
  width: 26px;
  height: 100%;
  object-fit: fill;
  pointer-events: none;
}
.video-container {
  position: relative;
  width: 184px;
  height: 104px;
  border-radius: 10px;
  overflow: hidden;
  background: #080b12;
  border: 2px solid rgba(255, 198, 75, 0.45);
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.65);
  cursor: pointer;
}
.video-container:hover {
  border-color: var(--gold);
}
.pip-idle {
  position: relative;
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
}
.pip-idle-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.42;
}
.pip-idle span {
  position: relative;
  font-size: 12px;
  color: var(--dim);
  background: rgba(8, 11, 18, 0.72);
  padding: 3px 9px;
  border-radius: 999px;
}
.pip-caption {
  position: absolute;
  inset: auto 0 0 0;
  padding: 6px 8px 4px;
  background: linear-gradient(180deg, transparent, rgba(5, 8, 14, 0.92));
  display: flex;
  flex-direction: column;
  pointer-events: none;
  z-index: 3;
}
.pip-caption strong {
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pip-caption small {
  font-size: 9px;
  color: var(--gold2);
}

@media (max-width: 1080px) {
  .dock-circle {
    height: 76px;
    font-size: 11px;
  }
  .video-container {
    width: 152px;
    height: 86px;
  }
  .pip-shadow { display: none; }
}

@media (max-width: 759px) {
  .ktv-bottom-dock {
    height: auto;
    grid-template-columns: 1fr;
    gap: 6px;
    padding: 6px 8px calc(8px + var(--safe-bottom));
  }
  .pip-stage-wrap {
    order: -1;
    justify-content: center;
    height: 72px;
  }
  .video-container {
    width: 100%;
    max-width: 340px;
    height: 72px;
  }
  .dock-group {
    height: 54px;
    gap: 6px;
  }
  .dock-circle {
    height: 54px;
    border-radius: 12px;
    font-size: 10px;
    gap: 2px;
  }
  .dock-png { width: 18px; height: 18px; }
  .queue-badge { top: 2px; right: 4px; min-width: 17px; height: 17px; font-size: 10px; }
}
</style>
