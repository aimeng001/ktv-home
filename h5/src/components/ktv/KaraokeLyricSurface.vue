<template>
  <div class="karaoke-surface" role="region" aria-label="双行卡拉OK歌词">
    <transition name="fade">
      <div v-if="showEmptyNotice" class="karaoke-empty">
        <span>暂无歌词，享受现场伴奏</span>
      </div>
    </transition>
    <div v-if="lyricLines && lyricLines.length" class="karaoke-duo-stage">
      <!-- 当前在唱行（左对齐，逐字平滑扫光） -->
      <div class="lyric-line active" :class="{ empty: !currentLine }">
        <span class="text-pending">{{ currentLine?.text || '…' }}</span>
        <span
          class="sweep-fill"
          :style="{ clipPath: `inset(0 ${100 - sweepPercent}% 0 0)` }"
        >
          {{ currentLine?.text || '' }}
        </span>
      </div>

      <!-- 下一句预备行（右对齐，待唱高对比白灰） -->
      <div class="lyric-line next" :class="{ empty: !nextLine }">
        <span class="text-pending">{{ nextLine?.text || '…' }}</span>
      </div>

      <!-- 前奏与间奏 4 颗节奏倒计时圆点（对标 IKTV 50.0.4） -->
      <div v-if="countdownProgress > 0" class="karaoke-countdown" aria-label="准备倒计时">
        <span
          v-for="dot in 4"
          :key="dot"
          class="countdown-dot"
          :class="{ active: dot <= countdownProgress }"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  lyricLines: { type: Array, default: () => [] },
  positionMs: { type: Number, default: 0 },
  isPlaying: { type: Boolean, default: false }
})

const showEmptyNotice = computed(() => {
  if (props.lyricLines && props.lyricLines.length > 0) return false
  return props.positionMs < 3500
})

function lineStartMs(line) {
  const val = Number(line?.startMs ?? line?.time ?? 0)
  return Number.isFinite(val) ? val : 0
}

function lineEndMs(lines, index) {
  const line = lines?.[index]
  if (!line) return 0
  const start = lineStartMs(line)
  const explicitEnd = Number(line.endMs)
  if (Number.isFinite(explicitEnd) && explicitEnd > start) return explicitEnd
  const nextLine = lines?.[index + 1]
  if (nextLine) {
    const nextStart = lineStartMs(nextLine)
    if (nextStart > start) return nextStart
  }
  return start + 4500
}

function findCurrentIndex(lines, pos) {
  if (!lines || !lines.length) return -1
  let low = 0
  let high = lines.length - 1
  let result = -1

  while (low <= high) {
    const mid = (low + high) >> 1
    if (lineStartMs(lines[mid]) <= pos) {
      result = mid
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  return result
}

const activeIndex = computed(() => findCurrentIndex(props.lyricLines, props.positionMs))

const currentLine = computed(() => {
  if (activeIndex.value < 0 || activeIndex.value >= props.lyricLines.length) {
    return props.lyricLines[0] || null
  }
  return props.lyricLines[activeIndex.value]
})

const nextLine = computed(() => {
  const nextIdx = activeIndex.value < 0 ? 1 : activeIndex.value + 1
  if (nextIdx >= 0 && nextIdx < props.lyricLines.length) {
    return props.lyricLines[nextIdx]
  }
  return null
})

const sweepPercent = computed(() => {
  const line = currentLine.value
  if (!line || activeIndex.value < 0) return 0
  const start = lineStartMs(line)
  const end = lineEndMs(props.lyricLines, activeIndex.value)
  const pos = props.positionMs

  if (pos <= start) return 0
  if (pos >= end) return 100

  // 1. 若有逐字锚点时间戳，按字宽比例精准扫光
  if (Array.isArray(line.words) && line.words.length > 0) {
    const totalChars = line.words.reduce((sum, w) => sum + Math.max(1, String(w?.text || '').length), 0)
    let sungChars = 0
    for (let i = 0; i < line.words.length; i++) {
      const w = line.words[i]
      const charCount = Math.max(1, String(w?.text || '').length)
      const wStart = Number(w?.startMs ?? w?.time ?? start)
      const nextWord = line.words[i + 1]
      const wEnd = nextWord ? Number(nextWord?.startMs ?? nextWord?.time ?? end) : end
      if (pos >= wEnd) {
        sungChars += charCount
      } else if (pos > wStart) {
        const frac = (pos - wStart) / Math.max(1, wEnd - wStart)
        sungChars += charCount * Math.max(0, Math.min(1, frac))
        break
      } else {
        break
      }
    }
    return Math.max(0, Math.min(100, Math.round((sungChars / Math.max(1, totalChars)) * 100)))
  }

  // 2. 传统行级歌词：按行起止毫秒平滑线性扫光（对齐 Android TV LyricSweepCalculator）
  const duration = Math.max(1, end - start)
  const ratio = (pos - start) / duration
  return Math.max(0, Math.min(100, Math.round(ratio * 100)))
})

/**
 * 4 颗准备倒计时圆点（对标 Android TV / IKTV 50.0.4 节拍倒计时）：
 * 1. 前奏阶段：当前播放位置在首句开唱前 4 秒内（且首句时间 >= 3 秒）；
 * 2. 间奏阶段：两句间隔大于 8 秒且距离下一句开唱还剩 4 秒内；
 * 返回当前亮起的点数 1~4，0 表示不处于倒计时状态。
 */
const countdownProgress = computed(() => {
  if (!props.isPlaying || !props.lyricLines || !props.lyricLines.length) return 0
  const pos = props.positionMs
  const first = props.lyricLines[0]
  const firstStart = lineStartMs(first)

  // 1. 前奏倒计时
  if (firstStart >= 3000 && pos < firstStart && pos >= firstStart - 4000) {
    const remainMs = firstStart - pos
    if (remainMs > 3000) return 1
    if (remainMs > 2000) return 2
    if (remainMs > 1000) return 3
    return 4
  }

  // 2. 间奏倒计时
  if (activeIndex.value >= 0 && activeIndex.value < props.lyricLines.length - 1) {
    const cur = props.lyricLines[activeIndex.value]
    const next = props.lyricLines[activeIndex.value + 1]
    const explicitCurEnd = Number(cur?.endMs)
    const curEnd = Number.isFinite(explicitCurEnd) && explicitCurEnd > lineStartMs(cur)
      ? explicitCurEnd
      : lineStartMs(cur) + 3000
    const nextStart = lineStartMs(next)
    if (nextStart - curEnd >= 8000 && pos >= curEnd && pos >= nextStart - 4000 && pos < nextStart) {
      const remainMs = nextStart - pos
      if (remainMs > 3000) return 1
      if (remainMs > 2000) return 2
      if (remainMs > 1000) return 3
      return 4
    }
  }

  return 0
})
</script>

<style scoped>
.karaoke-surface {
  width: 100%;
  pointer-events: none;
  user-select: none;
}

.karaoke-empty {
  text-align: center;
  font-size: 20px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.45);
  letter-spacing: 1px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.5s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.karaoke-duo-stage {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  padding: 0 40px;
}

.lyric-line {
  position: relative;
  font-size: 32px;
  line-height: 1.25;
  font-weight: 800;
  font-style: italic;
  letter-spacing: 1px;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.95), 0 0 4px rgba(0, 0, 0, 0.8);
}

.lyric-line.active {
  align-self: flex-start;
  text-align: left;
}

.lyric-line.next {
  align-self: flex-end;
  text-align: right;
  opacity: 0.72;
}

.text-pending {
  color: #ffffff;
}

.sweep-fill {
  position: absolute;
  top: 0;
  left: 0;
  color: #ffc64b; /* 金黄色卡拉OK经典扫光高亮 */
  white-space: nowrap;
  pointer-events: none;
  contain: layout style;
  transition: clip-path 0.08s linear;
}

.lyric-line.empty {
  visibility: hidden;
}

@media (max-width: 899px) {
  .lyric-line {
    font-size: 22px;
  }
  .karaoke-duo-stage {
    padding: 0 16px;
    gap: 8px;
  }
}

.karaoke-countdown {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  align-items: center;
  gap: 16px;
  z-index: 10;
  pointer-events: none;
}

.countdown-dot {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  border: 2px solid rgba(255, 255, 255, 0.45);
  box-shadow: 0 0 10px rgba(0, 0, 0, 0.5);
  transition: transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1), background-color 0.2s ease, border-color 0.2s ease;
}

.countdown-dot.active {
  background: #ffc64b;
  border-color: #ffd700;
  box-shadow: 0 0 18px rgba(255, 198, 75, 0.95), 0 0 32px rgba(255, 215, 0, 0.55);
  transform: scale(1.35);
}
</style>
