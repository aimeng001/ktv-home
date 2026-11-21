<template>
  <div class="standby-screen" role="region" aria-label="KTV 待机大屏" @click="$emit('wake')">
    <div class="standby-bg" :style="{ backgroundImage: `url(${assets.bgDashboard})` }" />
    <div class="standby-overlay" />

    <!-- 顶部状态栏（对标电视端待机顶栏） -->
    <header class="standby-header">
      <div class="standby-brand">
        <img :src="standbyLogoUrl || assets.logoPlaceholder" alt="KTV Logo" class="standby-logo" />
        <div class="brand-text">
          <h1>家庭卡拉OK</h1>
          <span>16:9 全屏视听点歌系统</span>
        </div>
      </div>
      <div class="standby-stats">
        <span class="lib-badge">曲库就绪 · 200,000+ 首</span>
        <span class="room-badge">包厢模式 · 极速点歌</span>
      </div>
    </header>

    <!-- 中部扫码与互动卡片 -->
    <main class="standby-center">
      <div class="qr-showcase" @click.stop="$emit('wake')">
        <div class="qr-frame">
          <img :src="qrUrl" alt="手机扫码点歌二维码" class="standby-qr-img" />
        </div>
        <div class="qr-meta">
          <h2>手机扫码点歌</h2>
          <p>微信或手机浏览器扫码，多人无线点歌与遥控</p>
          <div class="ip-pill">{{ currentHostUrl }}</div>
        </div>
      </div>
    </main>

    <!-- 底部轻触唤醒指引 -->
    <footer class="standby-footer">
      <div class="wake-hint">
        <span class="mic-icon">🎤</span>
        <span class="hint-text">轻触屏幕任意位置，开始选歌</span>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import bgDashboard from '../../assets/ktv/ktv_dashboard_background_iktv50.png'
import defaultBanner from '../../assets/ktv/home_ktv_tv_banner.png'

const props = defineProps({
  standbyLogoUrl: { type: String, default: '' },
})

defineEmits(['wake'])

const assets = {
  bgDashboard,
  logoPlaceholder: defaultBanner,
}

const qrUrl = computed(() => '/api/qr?room=default&size=400')
const currentHostUrl = computed(() => {
  if (typeof window !== 'undefined' && window.location) {
    return `${window.location.protocol}//${window.location.host}/m/`
  }
  return 'http://ktv-home:54001/m/'
})
</script>

<style scoped>
.standby-screen {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 32px 48px;
  background: #060910;
  color: #fff;
  cursor: pointer;
  user-select: none;
  overflow: hidden;
}

.standby-bg {
  position: absolute;
  inset: -20px;
  background-size: cover;
  background-position: center;
  filter: blur(8px) brightness(0.65);
  animation: kenBurns 24s ease-in-out infinite alternate;
  z-index: 0;
}

.standby-overlay {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at center, rgba(6, 9, 16, 0.4) 0%, rgba(6, 9, 16, 0.88) 100%);
  z-index: 1;
}

@keyframes kenBurns {
  0% { transform: scale(1) translate(0, 0); }
  100% { transform: scale(1.08) translate(-10px, -6px); }
}

.standby-header {
  position: relative;
  z-index: 2;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.standby-brand {
  display: flex;
  align-items: center;
  gap: 16px;
}

.standby-logo {
  height: 48px;
  object-fit: contain;
}

.brand-text h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 1px;
  background: linear-gradient(135deg, #fff, #ffc64b);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.brand-text span {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.65);
}

.standby-stats {
  display: flex;
  gap: 12px;
}

.lib-badge, .room-badge {
  padding: 8px 16px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.2);
  backdrop-filter: blur(12px);
}

.lib-badge {
  color: #ffc64b;
  border-color: rgba(255, 198, 75, 0.45);
}

.standby-center {
  position: relative;
  z-index: 2;
  display: flex;
  justify-content: center;
  align-items: center;
  flex: 1;
}

.qr-showcase {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 28px 40px;
  border-radius: 24px;
  background: rgba(14, 20, 32, 0.78);
  border: 1px solid rgba(255, 198, 75, 0.35);
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.65), 0 0 32px rgba(255, 198, 75, 0.15);
  backdrop-filter: blur(20px);
}

.qr-frame {
  padding: 12px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.standby-qr-img {
  display: block;
  width: 180px;
  height: 180px;
}

.qr-meta {
  max-width: 320px;
}

.qr-meta h2 {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 800;
  color: #fff;
}

.qr-meta p {
  margin: 0 0 16px;
  font-size: 14px;
  line-height: 1.5;
  color: rgba(255, 255, 255, 0.7);
}

.ip-pill {
  display: inline-block;
  padding: 6px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  font-family: monospace;
  font-size: 13px;
  color: #ffc64b;
}

.standby-footer {
  position: relative;
  z-index: 2;
  display: flex;
  justify-content: center;
  align-items: center;
}

.wake-hint {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 24px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.2);
  backdrop-filter: blur(12px);
  animation: pulseWake 2.4s ease-in-out infinite;
}

.mic-icon {
  font-size: 20px;
}

.hint-text {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #fff;
}

@keyframes pulseWake {
  0%, 100% { opacity: 0.85; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.04); }
}
</style>
