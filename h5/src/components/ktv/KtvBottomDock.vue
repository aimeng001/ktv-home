<template>
  <footer class="ktv-bottom-dock" aria-label="底部播控栏">
    <!-- 左侧 5 个 IKTV 原版白线图标按钮 -->
    <div class="dock-group dock-left">
      <button type="button" class="dock-circle dock-play-btn" :aria-label="player.isPlaying ? '暂停' : '播放'" @click="$emit('toggle-play')">
        <span class="dock-icon-ring">
          <img :src="player.isPlaying ? assets.playerPause : assets.playerPlay" alt="" class="dock-png" />
        </span>
        <span>{{ player.isPlaying ? '暂停' : '播放' }}</span>
      </button>

      <button
        type="button"
        class="dock-circle"
        :class="{ active: player.vocalMode === 'accompaniment' }"
        :disabled="!canVocal"
        :aria-disabled="!canVocal"
        :title="canVocal ? '' : '当前曲目无伴唱音轨'"
        :aria-label="canVocal
          ? (player.vocalMode === 'original' ? '当前原唱，点击切换伴唱' : '当前伴唱，点击切换原唱')
          : '当前曲目无伴唱音轨'"
        @click="$emit('toggle-vocal')"
      >
        <span class="dock-icon-ring">
          <img :src="assets.playerVocal" alt="" class="dock-png" />
        </span>
        <span>{{ player.vocalMode === 'original' ? '原唱' : '伴唱' }}</span>
      </button>

      <button type="button" class="dock-circle" aria-label="重唱" @click="$emit('restart')">
        <span class="dock-icon-ring">
          <img :src="assets.iconReplay" alt="" class="dock-png" />
        </span>
        <span>重唱</span>
      </button>

      <button type="button" class="dock-circle" aria-label="切歌" @click="$emit('next')">
        <span class="dock-icon-ring">
          <img :src="assets.iconNext" alt="" class="dock-png" />
        </span>
        <span>切歌</span>
      </button>

      <button type="button" class="dock-circle" aria-label="减小音量" @click="$emit('volume-delta', -VOLUME_STEP)">
        <span class="dock-icon-ring">
          <img :src="assets.iconVolDown" alt="" class="dock-png" />
        </span>
        <span>音量</span>
      </button>
    </div>

    <!-- 中央贴底 16:9 实时 MV 小窗与左右梯形羽化阴影 -->
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
        @keydown.space.prevent="$emit('enter-fullscreen')"
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
        <div v-else class="pip-hover-tip">点击全屏</div>
        <span class="pip-inner-shadow left" aria-hidden="true" />
        <span class="pip-inner-shadow right" aria-hidden="true" />
      </div>
      <img :src="assets.shadowRight" alt="" class="pip-shadow right" />
    </div>

    <!-- 右侧 5 个 IKTV 原版白线图标按钮 -->
    <div class="dock-group dock-right">
      <button type="button" class="dock-circle" aria-label="增加音量" @click="$emit('volume-delta', VOLUME_STEP)">
        <span class="dock-icon-ring">
          <img :src="assets.iconVolUp" alt="" class="dock-png" />
        </span>
        <span>音量</span>
      </button>

      <button type="button" class="dock-circle" aria-label="伴奏降调" @click="$emit('pitch-delta', -1)">
        <span class="dock-icon-ring">
          <img :src="assets.iconToneDown" alt="" class="dock-png" />
        </span>
        <span>降调</span>
      </button>

      <button type="button" class="dock-circle" aria-label="伴奏升调" @click="$emit('pitch-delta', 1)">
        <span class="dock-icon-ring">
          <img :src="assets.iconToneUp" alt="" class="dock-png" />
        </span>
        <span>升调</span>
      </button>

      <button type="button" class="dock-circle" aria-label="氛围音效与音轨说明" @click="$emit('open-mixer')">
        <span class="dock-icon-ring">
          <img :src="assets.iconDownload" alt="" class="dock-png" />
        </span>
        <span>音效</span>
      </button>

      <button type="button" class="dock-circle queue-btn" aria-label="打开已点队列抽屉" @click="$emit('open-queue')">
        <span class="dock-icon-ring">
          <img :src="assets.playerQueue" alt="" class="dock-png" />
          <em class="queue-badge">{{ player.queueCount }}</em>
        </span>
        <span>已点</span>
      </button>
    </div>
  </footer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { KTV_ASSETS } from './ktvCatalogPresets'
import { VOLUME_STEP } from './ktvControlSteps'
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
  height: 122px;
  padding: 0 16px;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 6px;
  background: linear-gradient(0deg, rgba(6, 9, 18, 0.88), rgba(6, 9, 18, 0.42) 72%, transparent);
  flex: none;
  z-index: 20;
}
.dock-group {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 4px;
  height: 100%;
  align-items: center;
}
.dock-circle {
  position: relative;
  border-radius: 14px;
  max-width: 96px;
  width: 100%;
  height: 98px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  background: transparent;
  border: none;
  color: rgba(246, 248, 252, 0.96);
  font-size: 13px;
  font-weight: 500;
  transition: transform 0.18s ease, opacity 0.18s ease;
}
.dock-icon-ring {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  border: 2px solid transparent;
  transition: background-color 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}
.dock-circle:hover .dock-icon-ring,
.dock-circle:focus-visible .dock-icon-ring {
  background: var(--iktv-blue);
  box-shadow: 0 0 18px var(--iktv-blue-glow);
  transform: scale(1.06);
}
.dock-circle:focus-visible {
  outline: none;
}
.dock-circle.active .dock-icon-ring {
  border-color: rgba(49, 130, 251, 0.72);
  background: rgba(49, 130, 251, 0.22);
}
.dock-circle:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.dock-circle:disabled:hover .dock-icon-ring {
  background: transparent;
  box-shadow: none;
  transform: none;
}
.dock-play-btn .dock-icon-ring {
  background: var(--iktv-blue);
  box-shadow: 0 0 18px var(--iktv-blue-glow);
}
.ktv-bottom-dock:has(.dock-circle:hover) .dock-play-btn:not(:hover) .dock-icon-ring,
.ktv-bottom-dock:has(.dock-circle:focus-visible) .dock-play-btn:not(:focus-visible):not(:hover) .dock-icon-ring {
  background: transparent;
  box-shadow: none;
}
.dock-png {
  width: 34px;
  height: 34px;
  object-fit: contain;
}
.queue-badge {
  position: absolute;
  top: 2px;
  right: 0;
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
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.5);
}

.pip-stage-wrap {
  display: flex;
  align-items: stretch;
  height: 100%;
}
.pip-shadow {
  width: 38px;
  height: 100%;
  object-fit: fill;
  pointer-events: none;
}
.video-container {
  position: relative;
  width: 216px;
  height: 122px;
  border-radius: 2px;
  overflow: hidden;
  background: #04060b;
  box-shadow: 0 0 24px rgba(0, 0, 0, 0.65);
  cursor: pointer;
  transition: box-shadow 0.18s ease;
}
.video-container:hover,
.video-container:focus-visible {
  box-shadow: 0 0 0 2px var(--iktv-blue), 0 0 24px var(--iktv-blue-glow);
  outline: none;
}
.pip-inner-shadow {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 18px;
  pointer-events: none;
  z-index: 2;
}
.pip-inner-shadow.left {
  left: 0;
  background: linear-gradient(90deg, rgba(0, 0, 0, 0.72), transparent);
}
.pip-inner-shadow.right {
  right: 0;
  background: linear-gradient(270deg, rgba(0, 0, 0, 0.72), transparent);
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
  opacity: 0.72;
}
.pip-idle span {
  position: relative;
  z-index: 3;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.9);
  background: rgba(6, 9, 16, 0.72);
  padding: 3px 10px;
  border-radius: 999px;
}
.pip-hover-tip {
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
  z-index: 3;
}
.video-container:hover .pip-hover-tip {
  opacity: 1;
}

@media (max-width: 1080px) {
  .dock-circle {
    height: 88px;
    font-size: 12px;
  }
  .dock-icon-ring {
    width: 48px;
    height: 48px;
  }
  .dock-png {
    width: 28px;
    height: 28px;
  }
  .video-container {
    width: 176px;
    height: 100px;
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
    height: 76px;
  }
  .video-container {
    width: 100%;
    max-width: 340px;
    height: 76px;
    border-radius: 8px;
  }
  .dock-group {
    height: 58px;
    gap: 4px;
  }
  .dock-circle {
    height: 58px;
    border-radius: 10px;
    font-size: 11px;
    gap: 2px;
  }
  .dock-icon-ring {
    width: 34px;
    height: 34px;
  }
  .dock-png { width: 22px; height: 22px; }
  .queue-badge { top: -2px; right: -4px; min-width: 17px; height: 17px; font-size: 10px; }
}
</style>
