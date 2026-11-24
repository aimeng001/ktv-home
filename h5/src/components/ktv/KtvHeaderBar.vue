<template>
  <header class="ktv-header">
    <!-- 左侧 6 大核心操作入口（对标 activity_main.xml header_bar） -->
    <nav class="shortcut-group" aria-label="KTV 顶栏功能">
      <button type="button" class="hdr-btn" title="系统信息" aria-label="系统信息" @click="$emit('open-modal', 'system')">
        <Info :size="18" />
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
        <SlidersHorizontal :size="18" />
      </button>
    </nav>

    <!-- 中央在播跑马灯 -->
    <div
      class="marquee-box"
      role="button"
      tabindex="0"
      aria-label="切换全屏"
      @click="$emit('toggle-fullscreen')"
      @keydown.enter.prevent="$emit('toggle-fullscreen')"
      @keydown.space.prevent="$emit('toggle-fullscreen')"
    >
      <span v-if="player.nowPlaying?.song" class="marquee-text">
        <b>正在演唱</b>
        {{ player.nowPlaying.song.title }} - {{ player.nowPlaying.song.artist || '未知歌手' }}
        <em v-if="player.nowPlaying.orderedByNick">· {{ player.nowPlaying.orderedByNick }} 点</em>
      </span>
      <span v-else class="marquee-text idle">
        家庭KTV点歌系统 · {{ player.isTvConsole ? '电视在线，当前为 TV 控制台模式' : player.isWebHost ? '无 TV 在线，当前为网页完整 KTV 模式' : '正在同步房间播控状态' }}
      </span>
    </div>

    <!-- 右侧 WS 连接状态、模式徽章与管理/身份入口 -->
    <div class="right-group">
      <span
        class="ws-pill"
        :class="{ ok: linkState.ok, offline: !linkState.ok }"
        :title="linkState.title"
        aria-live="polite"
      >
        <Wifi v-if="linkState.ok" :size="14" />
        <WifiOff v-else :size="14" />
        <span class="ws-text">{{ linkState.label }}</span>
      </span>

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

      <button type="button" class="nick-pill" title="设置昵称与房主身份" @click="$emit('open-modal', 'settings')">
        {{ user.nickname || '访客' }}
      </button>

      <router-link to="/admin" class="admin-pill" title="打开管理后台">后台</router-link>

      <div class="header-clock" aria-label="系统时间">
        <strong>{{ clockTime }}</strong>
        <span>{{ clockDate }}</span>
      </div>
    </div>
  </header>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Info, SlidersHorizontal, Wifi, WifiOff } from 'lucide-vue-next'
import { KTV_ASSETS } from './ktvCatalogPresets'
import { usePlayerStore } from '../../stores/player'
import { useNetStatusStore } from '../../stores/netStatus'
import { useUserStore } from '../../stores/user'

defineEmits(['open-modal', 'open-search', 'toggle-fullscreen', 'toggle-mode'])

const player = usePlayerStore()
const netStatus = useNetStatusStore()
const user = useUserStore()
const assets = KTV_ASSETS
const nowRef = ref(new Date())
let clockTimer = null

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const clockTime = computed(() => {
  const d = nowRef.value
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
})
const clockDate = computed(() => {
  const d = nowRef.value
  return `${d.getMonth() + 1}月${d.getDate()}日 ${WEEKDAYS[d.getDay()]}`
})

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
  height: 52px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  gap: 12px;
  background: linear-gradient(180deg, rgba(11, 14, 22, 0.88), rgba(11, 14, 22, 0.35));
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex: none;
  z-index: 20;
}
.shortcut-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.hdr-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.14);
  color: var(--text);
  transition: var(--transition);
}
.hdr-btn:hover {
  border-color: rgba(255, 198, 75, 0.72);
  background: rgba(255, 198, 75, 0.18);
  transform: translateY(-1px);
}
.hdr-img {
  width: 22px;
  height: 22px;
  object-fit: contain;
}
.marquee-box {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.marquee-text {
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  color: var(--text);
}
.marquee-text b {
  color: var(--gold);
  margin-right: 8px;
}
.marquee-text em {
  font-style: normal;
  color: var(--dim);
  margin-left: 6px;
}
.marquee-text.idle {
  color: var(--dim);
}
.right-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.ws-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.06);
  color: var(--dim);
}
.ws-pill.ok {
  color: var(--mint);
  border-color: rgba(110, 214, 168, 0.35);
  background: rgba(110, 214, 168, 0.12);
}
.ws-pill.offline {
  color: #fca5a5;
  border-color: rgba(239, 68, 68, 0.45);
  background: rgba(239, 68, 68, 0.16);
}
.mode-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 11px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(255, 255, 255, 0.07);
}
.mode-pill .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--cyan);
}
.mode-pill.tv-console {
  color: var(--mint);
  border-color: rgba(110, 214, 168, 0.45);
  background: rgba(110, 214, 168, 0.14);
}
.mode-pill.tv-console .dot { background: var(--mint); }
.mode-pill.web-host {
  color: var(--gold);
  border-color: rgba(255, 198, 75, 0.55);
  background: rgba(255, 198, 75, 0.16);
}
.mode-pill.web-host .dot { background: var(--gold); }
.mode-pill.web-remote {
  color: var(--cyan);
  border-color: rgba(84, 214, 226, 0.45);
  background: rgba(84, 214, 226, 0.14);
}
.nick-pill, .admin-pill {
  height: 32px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: var(--dim);
}
.admin-pill:hover, .nick-pill:hover {
  color: var(--text);
  border-color: rgba(255, 198, 75, 0.55);
}
.header-clock {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 8px;
  border-left: 1px solid rgba(255, 255, 255, 0.25);
  color: var(--text);
}
.header-clock strong {
  font-size: 16px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.header-clock span {
  font-size: 11px;
  color: var(--dim);
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
  .marquee-box {
    order: 3;
    flex: 1 0 100%;
  }
  .right-group {
    margin-left: auto;
  }
  .nick-pill,
  .header-clock,
  .ws-text {
    display: none;
  }
  .ws-pill {
    padding: 0 7px;
    height: 28px;
  }
}
</style>
