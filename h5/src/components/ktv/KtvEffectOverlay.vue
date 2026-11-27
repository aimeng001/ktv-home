<template>
  <div v-if="activeParticles.length || currentBanner" class="ktv-effect-overlay" aria-live="polite">
    <!-- 气氛特效横幅 -->
    <transition name="banner-pop">
      <div v-if="currentBanner" class="effect-banner">
        <span class="banner-icon">{{ currentBanner.icon }}</span>
        <span class="banner-text">{{ currentBanner.text }}</span>
      </div>
    </transition>

    <!-- 粒子群层 -->
    <div class="particles-container">
      <span
        v-for="p in activeParticles"
        :key="p.id"
        class="effect-particle"
        :style="{
          left: `${p.x}%`,
          top: `${p.y}%`,
          transform: `scale(${p.scale}) rotate(${p.rotation}deg)`,
          animationDuration: `${p.duration}s`,
          animationDelay: `${p.delay}s`,
        }"
      >
        {{ p.char }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount, onMounted } from 'vue'
import { usePlayerStore } from '../../stores/player'

const player = usePlayerStore()

const activeParticles = ref([])
const currentBanner = ref(null)

let bannerTimer = null
let particlesTimer = null
let audioCtx = null
let lastSoundAt = 0

const EFFECT_CONFIGS = {
  applause: { icon: '👏', text: '现场掌声响起来！', chars: ['👏', '✨', '⭐', '🎊'] },
  clap: { icon: '👏', text: '现场掌声响起来！', chars: ['👏', '✨', '⭐', '🎊'] },
  cheer: { icon: '🎉', text: '现场欢呼喝彩！', chars: ['🎉', '🥳', '🙌', '🌟', '💥'] },
  boo: { icon: '👎', text: '现场倒彩一片！', chars: ['👎', '💨', '⚡', '🍂'] },
  flower: { icon: '💐', text: '献上一大束鲜花！', chars: ['💐', '🌸', '🌹', '🌺', '🌷'] },
  toast: { icon: '🍻', text: '举杯欢聚，干杯！', chars: ['🍻', '🥂', '🍾', '✨', '🎉'] },
}

function getAudioContext() {
  if (typeof window === 'undefined') return null
  const AudioContext = window.AudioContext || window.webkitAudioContext
  if (!AudioContext) return null
  if (!audioCtx) {
    audioCtx = new AudioContext()
  }
  if (audioCtx.state === 'suspended' && typeof audioCtx.resume === 'function') {
    Promise.resolve(audioCtx.resume()).catch(() => {})
  }
  return audioCtx
}

function wakeEffectAudioOnGesture() {
  if (!audioCtx || audioCtx.state !== 'suspended' || typeof audioCtx.resume !== 'function') return
  Promise.resolve(audioCtx.resume()).catch(() => {})
}

function playSynthesizedSound(type) {
  const nowMs = Date.now()
  if (nowMs - lastSoundAt < 150) return
  lastSoundAt = nowMs
  const volumeScale = Math.max(0.05, Math.min(1, Number(player?.volume ?? 60) / 100))
  try {
    const ctx = getAudioContext()
    if (!ctx) return
    const now = ctx.currentTime

    if (type === 'applause' || type === 'clap' || type === 'cheer') {
      // 噪音碎拍模拟掌声/喝彩
      const bufferSize = ctx.sampleRate * 0.8
      const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate)
      const data = buffer.getChannelData(0)
      for (let i = 0; i < bufferSize; i++) {
        data[i] = (Math.random() * 2 - 1) * Math.exp(-i / (ctx.sampleRate * 0.35))
      }
      const noise = ctx.createBufferSource()
      noise.buffer = buffer
      const filter = ctx.createBiquadFilter()
      filter.type = 'bandpass'
      filter.frequency.value = type === 'cheer' ? 1200 : 800
      const gain = ctx.createGain()
      gain.gain.setValueAtTime(0.25 * volumeScale, now)
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.8)
      noise.connect(filter)
      filter.connect(gain)
      gain.connect(ctx.destination)
      noise.start(now)
    } else if (type === 'boo') {
      // 下滑音模拟嘘声
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sawtooth'
      osc.frequency.setValueAtTime(260, now)
      osc.frequency.exponentialRampToValueAtTime(90, now + 0.9)
      gain.gain.setValueAtTime(0.2 * volumeScale, now)
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.9)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(now)
      osc.stop(now + 0.9)
    } else if (type === 'flower' || type === 'toast') {
      // 清脆和弦风铃 / 碰杯和弦
      const freqs = type === 'toast' ? [784.00, 1046.50, 1318.51, 1567.98] : [523.25, 659.25, 783.99, 1046.50]
      freqs.forEach((f, idx) => {
        const osc = ctx.createOscillator()
        const gain = ctx.createGain()
        osc.type = 'sine'
        osc.frequency.value = f
        const startTime = now + idx * 0.08
        gain.gain.setValueAtTime(0.18 * volumeScale, startTime)
        gain.gain.exponentialRampToValueAtTime(0.001, startTime + 0.7)
        osc.connect(gain)
        gain.connect(ctx.destination)
        osc.start(startTime)
        osc.stop(startTime + 0.7)
      })
    }
  } catch (_) {
    // Web Audio blocked or not supported, ignore silently
  }
}

function triggerEffect(type = 'applause') {
  const config = EFFECT_CONFIGS[type] || EFFECT_CONFIGS.applause

  // 1. 声道静音保护：仅当本机为主播放端且未静音时程序化发声；电视在线或遥控端仅展示视觉效果
  const shouldPlayAudio = Boolean(
    player?.isWebHost &&
    !player?.isTvConsole &&
    !player?.muted &&
    player?.modeOverride !== 'console' &&
    (!player?.webPlayerActive || player?.webPlayerRole === 'ACTIVE')
  )
  if (shouldPlayAudio) {
    playSynthesizedSound(type)
  }

  // 2. 视觉横幅展示
  currentBanner.value = config
  clearTimeout(bannerTimer)
  bannerTimer = setTimeout(() => {
    currentBanner.value = null
  }, 2200)

  // 3. 粒子系统喷发
  const count = 24
  const chars = config.chars
  const newParticles = []
  for (let i = 0; i < count; i++) {
    newParticles.push({
      id: `${Date.now()}_${i}_${Math.random()}`,
      x: 10 + Math.random() * 80,
      y: 60 + Math.random() * 30,
      scale: 0.8 + Math.random() * 1.2,
      rotation: Math.random() * 360,
      duration: 1.6 + Math.random() * 1.0,
      delay: Math.random() * 0.35,
      char: chars[Math.floor(Math.random() * chars.length)],
    })
  }
  activeParticles.value = newParticles

  clearTimeout(particlesTimer)
  particlesTimer = setTimeout(() => {
    activeParticles.value = []
  }, 2600)
}

onMounted(() => {
  if (typeof window !== 'undefined') {
    window.addEventListener('click', wakeEffectAudioOnGesture, { capture: true })
    window.addEventListener('touchend', wakeEffectAudioOnGesture, { capture: true })
    window.addEventListener('keydown', wakeEffectAudioOnGesture, { capture: true })
  }
})

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('click', wakeEffectAudioOnGesture, { capture: true })
    window.removeEventListener('touchend', wakeEffectAudioOnGesture, { capture: true })
    window.removeEventListener('keydown', wakeEffectAudioOnGesture, { capture: true })
  }
  clearTimeout(bannerTimer)
  clearTimeout(particlesTimer)
  if (audioCtx && typeof audioCtx.close === 'function') {
    audioCtx.close().catch(() => {})
    audioCtx = null
  }
})

defineExpose({ triggerEffect })
</script>

<style scoped>
.ktv-effect-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  z-index: 100;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.effect-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 28px;
  background: rgba(18, 24, 38, 0.92);
  border: 2px solid var(--gold, #ffc64b);
  border-radius: 40px;
  box-shadow: 0 0 30px rgba(255, 198, 75, 0.6), 0 12px 36px rgba(0, 0, 0, 0.7);
  color: #fff;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 1px;
}

.banner-icon {
  font-size: 28px;
}

.particles-container {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.effect-particle {
  position: absolute;
  font-size: 32px;
  animation: floatUpAndFade linear forwards;
  will-change: transform, opacity;
}

@keyframes floatUpAndFade {
  0% {
    opacity: 0;
    transform: translateY(0) scale(0.6) rotate(0deg);
  }
  15% {
    opacity: 1;
  }
  80% {
    opacity: 0.9;
  }
  100% {
    opacity: 0;
    transform: translateY(-260px) scale(1.3) rotate(180deg);
  }
}

.banner-pop-enter-active,
.banner-pop-leave-active {
  transition: transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1), opacity 0.3s ease;
}

.banner-pop-enter-from {
  opacity: 0;
  transform: scale(0.6) translateY(20px);
}

.banner-pop-leave-to {
  opacity: 0;
  transform: scale(1.1) translateY(-20px);
}
</style>
