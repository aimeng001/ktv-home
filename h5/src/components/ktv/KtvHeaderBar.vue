<template>
  <header class="ktv-header">
    <!-- 左侧 6 大核心操作入口（对标 activity_main.xml header_bar 无框白线图标） -->
    <nav class="shortcut-group" aria-label="KTV 顶栏功能">
      <button type="button" class="hdr-btn" title="系统信息" aria-label="系统信息" @click="$emit('open-modal', 'system')">
        <Info :size="20" />
      </button>
      <button type="button" class="hdr-btn" title="手机扫码点歌" aria-label="手机扫码点歌" @click="$emit('open-modal', 'qr')">
        <img :src="assets.iconMobileQr" alt="" class="hdr-img" />
      </button>
      <button type="button" class="hdr-btn" title="系统设置" aria-label="系统设置" @click="$emit('open-modal', 'settings')">
        <img :src="assets.iconSettings" alt="" class="hdr-img" />
      </button>
      <button type="button" class="hdr-btn" title="全局搜索" aria-label="全局搜索" @click="$emit('open-search')">
        <img :src="assets.iconSearch" alt="" class="hdr-img" />
      </button>
      <button type="button" class="hdr-btn" title="更新歌库" aria-label="更新歌库" @click="$emit('open-modal', 'scan')">
        <img :src="assets.iconLibraryScan" alt="" class="hdr-img" />
      </button>
      <button type="button" class="hdr-btn" title="调音与现场音效" aria-label="调音与现场音效" @click="$emit('open-modal', 'mixer')">
        <SlidersHorizontal :size="20" />
      </button>
    </nav>

    <!-- 右侧低干扰模式切换、管理入口、Wi-Fi 状态与 IKTV 大号时钟/双行日期 -->
    <div class="right-group">
      <button
        type="button"
        class="mode-pill"
        :class="modeBadge.cls"
        :title="modeBadge.title"
        @click="$emit('toggle-mode')"
      >
        <i class="dot" />
        <span>{{ modeBadge.label }}</span>
      </button>

      <router-link to="/admin" class="admin-pill" title="打开管理后台">后台</router-link>

      <span
        class="ws-pill"
        :class="{ ok: linkState.ok, offline: !linkState.ok }"
        :title="linkState.title"
        aria-live="polite"
      >
        <img v-if="linkState.ok" :src="assets.iconWifi" alt="" class="wifi-png" />
        <WifiOff v-else :size="15" />
        <span class="ws-text">{{ linkState.label }}</span>
      </span>

      <div class="header-clock" aria-label="系统时间">
        <strong>{{ clockTime }}</strong>
        <span class="clock-sep" aria-hidden="true" />
        <div class="clock-date-col">
          <span>{{ clockYearDate }}</span>
          <span>{{ clockWeekday }}</span>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Info, SlidersHorizontal, WifiOff } from 'lucide-vue-next'
import { KTV_ASSETS } from './ktvCatalogPresets'
import { usePlayerStore } from '../../stores/player'
import { useNetStatusStore } from '../../stores/netStatus'

defineEmits(['open-modal', 'open-search', 'toggle-mode'])

const player = usePlayerStore()
const netStatus = useNetStatusStore()
const assets = KTV_ASSETS
const nowRef = ref(new Date())
let clockTimer = null

const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
const clockTime = computed(() => {
  const d = nowRef.value
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
})
const clockYearDate = computed(() => {
  const d = nowRef.value
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
})
const clockWeekday = computed(() => WEEKDAYS[nowRef.value.getDay()])

onMounted(() => {
  clockTimer = setInterval(() => {
    if (typeof document !== 'undefined' && document.hidden) return
    nowRef.value = new Date()
  }, 15000)
  netStatus.start()
})
onBeforeUnmount(() => {
  if (clockTimer) {
    clearInterval(clockTimer)
    clockTimer = null
  }
  netStatus.stop()
})

/**
 * 顶栏连接状态：先看本机网络，再看 WebSocket。
 *
 * 网络断了时 WS 也会断，如果只显示「重连中」，用户会以为是服务端问题而一直等。
 */
const linkState = computed(() => {
  if (!netStatus.online) {
    return { ok: false, label: '离线', title: '网络已断开，请检查 Wi-Fi 或网线' }
  }
  if (player.connected) {
    return { ok: true, label: '在线', title: 'WebSocket 实时同步已连接' }
  }
  return { ok: false, label: '重连中', title: 'WebSocket 连接断开，正在自动重连' }
})

const modeBadge = computed(() => {
  if (player.effectiveMode === 'TV_CONSOLE') {
    return {
      cls: 'tv-console',
      label: '📺 正在控制电视',
      title: '检测到 TV 大屏在线，当前控制电视播放（电视发声，网页静音预览）'
    }
  }
  if (player.effectiveMode === 'WEB_HOST') {
    return {
      cls: 'web-host',
      label: '💻 本机大屏播放',
      title: '当前无 TV 在线，本机作为完整 KTV 主播放端（发声播放中）'
    }
  }
  return {
    cls: 'web-remote',
    label: (player.modeOverride === 'console' || player.webPlayerActive) ? '📱 遥控控制台' : '💻 本机大屏播放',
    title: (player.modeOverride === 'console' || player.webPlayerActive)
      ? '当前为静音遥控控制台模式，点击切换为本机大屏播放'
      : '当前无 TV 在线，本机作为完整 KTV 主播放端（发声播放中）'
  }
})
</script>

<style scoped>
.ktv-header {
  height: clamp(54px, 6.4vh, 68px);
  padding: 0 clamp(16px, 1.5vw, 26px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: transparent;
  flex: none;
  z-index: 20;
}
.shortcut-group {
  display: flex;
  align-items: center;
  gap: clamp(10px, 1vw, 16px);
  flex: none;
}
.hdr-btn {
  width: clamp(38px, 2.6vw, 46px);
  height: clamp(38px, 2.6vw, 46px);
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.92);
  transition: background-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}
.hdr-btn:hover,
.hdr-btn:focus-visible {
  background: var(--iktv-blue);
  box-shadow: 0 0 14px var(--iktv-blue-glow);
  transform: scale(1.06);
  outline: none;
}
.hdr-img {
  width: clamp(24px, 1.6vw, 30px);
  height: clamp(24px, 1.6vw, 30px);
  object-fit: contain;
}
.right-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
  margin-left: auto;
}
.mode-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.85);
}
.mode-pill:hover {
  border-color: var(--iktv-blue);
  background: rgba(49, 130, 251, 0.18);
}
.mode-pill .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--iktv-blue);
}
.mode-pill.tv-console {
  color: var(--mint);
  border-color: rgba(49, 194, 124, 0.35);
  background: rgba(49, 194, 124, 0.12);
}
.mode-pill.tv-console .dot { background: var(--mint); }
.mode-pill.web-host {
  color: #dbeafe;
  border-color: rgba(49, 130, 251, 0.42);
  background: rgba(49, 130, 251, 0.16);
}
.mode-pill.web-host .dot { background: var(--iktv-blue); }
.mode-pill.web-remote {
  color: var(--cyan);
  border-color: rgba(84, 214, 226, 0.35);
  background: rgba(84, 214, 226, 0.12);
}
.admin-pill {
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.8);
}
.admin-pill:hover {
  color: #fff;
  border-color: var(--iktv-blue);
  background: rgba(49, 130, 251, 0.18);
}
.ws-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.82);
}
.ws-pill.ok {
  color: rgba(255, 255, 255, 0.86);
}
.ws-pill.offline {
  color: #fca5a5;
  background: rgba(239, 68, 68, 0.18);
  padding: 0 9px;
  border: 1px solid rgba(239, 68, 68, 0.4);
}
.wifi-png {
  width: 18px;
  height: 18px;
  object-fit: contain;
}
.ws-text {
  font-size: 11px;
}
.ws-pill.ok .ws-text {
  color: rgba(255, 255, 255, 0.65);
}
.header-clock {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #ffffff;
}
.header-clock strong {
  font-size: clamp(26px, 2.1vw, 34px);
  font-weight: 300;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.clock-sep {
  width: 1px;
  height: clamp(20px, 1.6vw, 26px);
  background: rgba(255, 255, 255, 0.35);
}
.clock-date-col {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  font-size: clamp(11px, 0.78vw, 13px);
  color: rgba(255, 255, 255, 0.88);
}

@media (max-width: 899px) {
  .ktv-header {
    height: auto;
    flex-wrap: wrap;
    padding: 8px 10px;
    gap: 6px;
  }
  .shortcut-group {
    gap: 6px;
  }
  .hdr-btn {
    width: 32px;
    height: 32px;
  }
  .right-group {
    margin-left: auto;
    gap: 6px;
  }
  .header-clock,
  .ws-text {
    display: none;
  }
  .ws-pill {
    padding: 0 6px;
    height: 28px;
  }
}
</style>
