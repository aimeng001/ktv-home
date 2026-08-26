<template>
  <div class="admin-shell">
    <aside class="side">
      <!-- 品牌标识区 / Brand identity area -->
      <div class="brand">
        <strong>家庭KTV</strong>
        <span>管理后台</span>
        <button class="logout" type="button" @click="logout">退出管理</button>
      </div>
      <!-- 侧边栏导航菜单 / Sidebar navigation menu -->
      <nav>
        <div class="nav-label">菜单导航</div>
        <router-link class="mi" :class="{ on: active === 'dashboard' }" :to="{ name: 'admin-dashboard' }"><span>▦</span>仪表盘</router-link>
        <router-link class="mi" :class="{ on: active === 'source' }" :to="{ name: 'admin-source-library' }"><span>▤</span>原始音乐管理</router-link>
        <router-link class="mi" :class="{ on: active === 'ktv' }" :to="{ name: 'admin-ktv-library' }"><span>♫</span>KTV曲库</router-link>
        <router-link class="mi" :class="{ on: active === 'artists' }" :to="{ name: 'admin-artists' }"><span>♙</span>歌手库</router-link>
        <router-link class="mi" :class="{ on: active === 'ai' }" :to="{ name: 'admin-ai' }"><span>✦</span>主题歌单</router-link>
        <router-link class="mi" :class="{ on: active === 'settings' }" :to="{ name: 'admin-settings' }"><span>⚙</span>系统设置</router-link>
      </nav>
    </aside>
    <!-- 主内容区 / Main content area -->
    <main class="main"><slot /></main>

    <div v-if="releaseNoticeOpen" class="notice-mask" @click.self="dismissReleaseNotice">
      <section class="release-notice" role="dialog" aria-modal="true" aria-labelledby="release-notice-title">
        <header>
          <div class="notice-mark">TV</div>
          <div>
            <span class="notice-kicker">版本 {{ releaseInfo.version }}</span>
            <h2 id="release-notice-title">{{ releaseInfo.announcement.title }}</h2>
          </div>
          <button class="notice-close" type="button" title="稍后提醒" aria-label="稍后提醒" @click="dismissReleaseNotice">×</button>
        </header>
        <p class="notice-message">{{ releaseInfo.announcement.message }}</p>
        <div class="package-list">
          <a v-if="releaseInfo.tv.arm64V8a.available" :href="releaseInfo.tv.arm64V8a.url" class="package-link">
            <strong>64 位安装包</strong>
            <span>arm64-v8a · {{ formatBytes(releaseInfo.tv.arm64V8a.size) }}</span>
          </a>
          <a v-if="releaseInfo.tv.armeabiV7a.available" :href="releaseInfo.tv.armeabiV7a.url" class="package-link">
            <strong>32 位安装包</strong>
            <span>armeabi-v7a · {{ formatBytes(releaseInfo.tv.armeabiV7a.size) }}</span>
          </a>
        </div>
        <footer>
          <button class="notice-secondary" type="button" @click="dismissReleaseNotice">稍后提醒</button>
          <button class="notice-primary" type="button" @click="markReleaseNoticeRead">标记已读</button>
        </footer>
      </section>
    </div>
  </div>
</template>

<script setup>
/**
 * 管理后台布局组件 —— 左侧固定侧边栏 + 右侧内容区。
 *
 * Admin layout component — fixed left sidebar with right content area.
 */
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../../api/client'
import { useAdminAuthStore } from '../../stores/adminAuth'

/**
 * active 当前激活的菜单项标识（dashboard | source | ktv | settings）
 *
 * @type {String}
 * active — identifier of the currently active menu item.
 */
defineProps({ active: { type: String, default: '' } })

const READ_KEY = 'home-ktv.admin.releaseNoticeRead'
const DISMISSED_KEY = 'home-ktv.admin.releaseNoticeDismissed'
const releaseInfo = ref(null)
const releaseNoticeOpen = ref(false)
const adminAuth = useAdminAuthStore()
const router = useRouter()
const route = useRoute()

function handleAuthRequired() {
  adminAuth.clearToken()
  router.replace({ name: 'admin-login', query: { redirect: route.fullPath } })
}

onMounted(async () => {
  window.addEventListener('home-ktv-admin-auth-required', handleAuthRequired)
  try {
    const status = await api.adminAuthStatus()
    if (!status?.authenticated) handleAuthRequired()
  } catch {
    // The API client handles expired admin sessions and redirects via the event above.
  }
  try {
    const info = await api.releaseInfo()
    const noticeId = info?.announcement?.id
    const packageAvailable = info?.tv?.arm64V8a?.available || info?.tv?.armeabiV7a?.available
    releaseInfo.value = info
    releaseNoticeOpen.value = Boolean(
      info?.announcement?.enabled && noticeId && packageAvailable &&
      localStorage.getItem(READ_KEY) !== noticeId &&
      sessionStorage.getItem(DISMISSED_KEY) !== noticeId
    )
  } catch {
    // Release notices must never block the administration UI.
  }
})

onUnmounted(() => window.removeEventListener('home-ktv-admin-auth-required', handleAuthRequired))

async function logout() {
  try { await api.adminLogout() } catch { /* local logout must still complete */ }
  adminAuth.clearToken()
  router.replace({ name: 'admin-login' })
}

function dismissReleaseNotice() {
  const noticeId = releaseInfo.value?.announcement?.id
  if (noticeId) sessionStorage.setItem(DISMISSED_KEY, noticeId)
  releaseNoticeOpen.value = false
}

function markReleaseNoticeRead() {
  const noticeId = releaseInfo.value?.announcement?.id
  if (noticeId) localStorage.setItem(READ_KEY, noticeId)
  releaseNoticeOpen.value = false
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '大小未知'
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<style scoped>
.admin-shell { --admin-blue:#2563eb; --admin-border:#e2e8f0; --admin-muted:#64748b; display:flex; min-height:100vh; background:#f5f7fa; color:#172033; }
.side { width:220px; flex:none; background:#fff; border-right:1px solid var(--admin-border); }
.brand { height:76px; padding:18px 20px; border-bottom:1px solid var(--admin-border); display:flex; flex-direction:column; justify-content:center; }
.brand strong { font-size:18px; line-height:1; }.brand span { margin-top:7px; color:#94a3b8; font-size:11px; }
.logout { align-self:flex-start; margin-top:9px; padding:0; border:0; background:transparent; color:#64748b; font-size:11px; cursor:pointer; }.logout:hover { color:#1d4ed8; }
nav { padding:16px 12px; }.nav-label { padding:0 10px 8px; font-size:11px; color:#94a3b8; }
.mi { display:flex; align-items:center; gap:10px; min-height:42px; padding:0 12px; margin-bottom:4px; border-radius:6px; color:#475569; font-size:13px; border:1px solid transparent; }
.mi span { width:18px; text-align:center; font-size:16px; color:#64748b; }.mi:hover { background:#f8fafc; color:#172033; }
.mi.on { color:var(--admin-blue); background:#eff6ff; border-color:#bfdbfe; font-weight:600; box-shadow:inset 3px 0 0 var(--admin-blue); }.mi.on span { color:var(--admin-blue); }
.main { flex:1; min-width:0; padding:24px 28px 48px; overflow:auto; }
.notice-mask { position:fixed; inset:0; z-index:200; display:grid; place-items:center; padding:20px; background:rgba(15,23,42,.48); }
.release-notice { width:min(520px,calc(100vw - 28px)); overflow:hidden; border:1px solid #dbe3ee; border-radius:8px; background:#fff; box-shadow:0 24px 70px rgba(15,23,42,.24); }
.release-notice header { display:flex; align-items:center; gap:12px; padding:19px 20px 16px; border-bottom:1px solid var(--admin-border); }
.notice-mark { display:grid; width:42px; height:42px; flex:none; place-items:center; border-radius:7px; background:#eff6ff; color:var(--admin-blue); font-size:13px; font-weight:800; }
.release-notice header>div:nth-child(2) { min-width:0; }
.notice-kicker { display:block; color:var(--admin-blue); font-size:10px; font-weight:700; }
.release-notice h2 { margin:4px 0 0; overflow:hidden; color:#172033; font-size:17px; text-overflow:ellipsis; white-space:nowrap; }
.notice-close { display:grid; width:32px; height:32px; margin-left:auto; flex:none; place-items:center; border:1px solid #cbd5e1; border-radius:6px; background:#fff; color:#64748b; font-size:20px; }
.notice-message { margin:0; padding:17px 20px 12px; color:#475569; font-size:12px; line-height:1.7; }
.package-list { display:grid; grid-template-columns:1fr 1fr; gap:9px; padding:4px 20px 20px; }
.package-link { min-width:0; padding:12px 13px; border:1px solid #bfdbfe; border-radius:7px; background:#f8fbff; color:#172033; }
.package-link:hover { border-color:#60a5fa; background:#eff6ff; }
.package-link strong,.package-link span { display:block; }
.package-link strong { color:#1d4ed8; font-size:12px; }
.package-link span { margin-top:5px; overflow:hidden; color:#64748b; font-size:10px; text-overflow:ellipsis; white-space:nowrap; }
.release-notice footer { display:flex; justify-content:flex-end; gap:8px; padding:13px 20px; border-top:1px solid var(--admin-border); background:#f8fafc; }
.notice-secondary,.notice-primary { min-height:34px; padding:0 13px; border-radius:6px; font-size:11px; font-weight:600; }
.notice-secondary { border:1px solid #cbd5e1; background:#fff; color:#475569; }
.notice-primary { border:1px solid var(--admin-blue); background:var(--admin-blue); color:#fff; }
@media (max-width:760px) {
  .admin-shell { display:block; }
  .side { position:sticky; top:0; z-index:20; width:100%; border-right:0; border-bottom:1px solid var(--admin-border); }
  .brand { height:52px; padding:10px 14px; }
  .brand strong { font-size:16px; }.brand span { margin-top:4px; font-size:9px; }
  nav { display:flex; gap:5px; padding:8px 10px; overflow-x:auto; scrollbar-width:none; }
  nav::-webkit-scrollbar { display:none; }
  .nav-label { display:none; }
  .mi { flex:none; min-height:36px; margin:0; padding:0 10px; font-size:11px; white-space:nowrap; }
  .mi span { width:15px; font-size:13px; }
  .mi.on { box-shadow:none; }
  .main { padding:16px 12px 36px; overflow:visible; }
  .package-list { grid-template-columns:1fr; }
}
</style>
