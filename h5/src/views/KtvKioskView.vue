<template>
  <div class="ktv-kiosk-shell" :style="{ backgroundImage: `url(${assets.bgDashboard})` }">
    <KtvHeaderBar
      @open-modal="openModal"
      @open-search="openGlobalSearch"
      @toggle-fullscreen="toggleFullscreen"
      @toggle-mode="toggleQuickMode"
    />

    <div v-if="user.registrationError" class="identity-banner" role="alert">
      身份初始化失败：{{ user.registrationError }}
      <button type="button" :disabled="identityRetrying" @click="retryIdentity">
        {{ identityRetrying ? '重试中…' : '重试' }}
      </button>
    </div>

    <main class="kiosk-stage">
      <KtvBentoDashboard
        v-if="viewMode === 'dashboard'"
        @select-tile="onSelectTile"
      />
      <KtvThreeColumnBrowser
        v-else
        ref="browserRef"
        :initial-tab="browserTab"
        :initial-query="browserQuery"
        :initial-artist="browserArtist"
        :initial-language="browserLanguage"
        :initial-tag="browserTag"
        :initial-vocal-form="browserVocalForm"
        :initial-playlist-id="browserPlaylistId"
        @back-hall="backToDashboard"
      />
    </main>

    <KtvBottomDock
      ref="dockRef"
      :standby-logo-url="standbyLogoUrl"
      @toggle-play="togglePlay"
      @toggle-vocal="toggleVocal"
      @restart="restartSong"
      @next="confirmNext"
      @volume-delta="adjustVolume"
      @pitch-delta="adjustPitch"
      @open-mixer="openModal('mixer')"
      @open-queue="drawerOpen = true"
      @enter-fullscreen="fullscreen = true"
    />

    <KtvMediaSurface
      :fullscreen="fullscreen"
      :pip-rect="pipRect"
      @enter-fullscreen="fullscreen = true"
      @exit-fullscreen="fullscreen = false"
      @open-browser="openBrowserFromFullscreen"
      @open-home="openHomeFromFullscreen"
      @toggle-play="togglePlay"
      @toggle-vocal="toggleVocal"
      @swap-vocal="swapVocalTracks"
      @toggle-mute="toggleMute"
      @restart="restartSong"
      @next="confirmNext"
      @seek="seekTo"
      @volume-delta="adjustVolume"
      @pitch-delta="adjustPitch"
      @open-mixer="openModal('mixer')"
      @open-queue="drawerOpen = true"
    />

    <KtvQueueDrawer
      :open="drawerOpen"
      @close="drawerOpen = false"
      @next="confirmNext"
    />

    <KtvModals
      :active-modal="activeModal"
      :auto-fullscreen="autoFullscreen"
      @close="activeModal = ''"
      @claim-web-host="claimWebHost"
      @toggle-auto-fullscreen="toggleAutoFullscreen"
      @set-volume="setVolume"
      @toggle-mute="toggleMute"
      @set-vocal="setVocalMode"
      @swap-vocal="swapVocalTracks"
      @pitch-delta="adjustPitch"
      @pitch-reset="resetPitch"
      @effect="sendEffect"
    />
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api, { makeControls } from '../api/client'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import { confirmDialog } from '../composables/useDialog'
import { KTV_ASSETS } from '../components/ktv/ktvCatalogPresets'
import KtvHeaderBar from '../components/ktv/KtvHeaderBar.vue'
import KtvBentoDashboard from '../components/ktv/KtvBentoDashboard.vue'
import KtvThreeColumnBrowser from '../components/ktv/KtvThreeColumnBrowser.vue'
import KtvBottomDock from '../components/ktv/KtvBottomDock.vue'
import KtvMediaSurface from '../components/ktv/KtvMediaSurface.vue'
import KtvQueueDrawer from '../components/ktv/KtvQueueDrawer.vue'
import KtvModals from '../components/ktv/KtvModals.vue'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const user = useUserStore()
const { toast } = useToast()

const assets = KTV_ASSETS
const controls = makeControls(user.ensureToken(), { onResponse: res => player.applyControlResponse(res) })

const viewMode = ref('dashboard') // 'dashboard' | 'browser'
const browserTab = ref('song_name')
const browserQuery = ref('')
const browserArtist = ref(null)
const browserLanguage = ref('')
const browserTag = ref('')
const browserVocalForm = ref('')
const browserPlaylistId = ref(null)
const standbyLogoUrl = ref('')

const fullscreen = ref(false)
const drawerOpen = ref(false)
const identityRetrying = ref(false)
const activeModal = ref('')
const autoFullscreen = ref((() => {
  try { return localStorage.getItem('ktv_auto_fullscreen') === 'true' } catch { return false }
})())
const browserRef = ref(null)
const dockRef = ref(null)
const pipRect = ref(null)

let pipObserver = null
let idleFullscreenTimer = null
let awaitingHostClaimToast = false

function resetBrowserFilters() {
  browserQuery.value = ''
  browserArtist.value = null
  browserLanguage.value = ''
  browserTag.value = ''
  browserVocalForm.value = ''
  browserPlaylistId.value = null
}

function updatePipRect() {
  const el = dockRef.value?.pipSlotEl
  if (!el || typeof el.getBoundingClientRect !== 'function') return
  const r = el.getBoundingClientRect()
  pipRect.value = { left: r.left, top: r.top, width: r.width, height: r.height }
}

function applyRouteIntent() {
  const name = route.name
  drawerOpen.value = false
  activeModal.value = ''
  fullscreen.value = false
  if (name === 'entry' || name === 'home') {
    viewMode.value = 'dashboard'
    resetBrowserFilters()
    return
  }
  if (name === 'search') {
    resetBrowserFilters()
    browserTab.value = 'song_name'
    browserQuery.value = typeof route.query.q === 'string' ? route.query.q : ''
    viewMode.value = 'browser'
    return
  }
  if (name === 'browse') {
    resetBrowserFilters()
    const t = String(route.query.tab || 'artists')
    browserTab.value = t === 'languages' ? 'language' : t === 'tags' ? 'theme' : 'singer'
    if (typeof route.query.language === 'string') browserLanguage.value = route.query.language
    if (typeof route.query.tag === 'string') browserTag.value = route.query.tag
    if (typeof route.query.vocalForm === 'string') browserVocalForm.value = route.query.vocalForm
    viewMode.value = 'browser'
    return
  }
  if (name === 'artist') {
    resetBrowserFilters()
    const m = String(route.query.mode || '')
    const routeValue = typeof route.query.value === 'string'
      ? route.query.value
      : (route.params.name && route.params.name !== 'all' ? String(route.params.name) : '')
    if (m === 'language') {
      browserTab.value = 'language'
      browserLanguage.value = routeValue
    } else if (m === 'tag') {
      browserTab.value = 'theme'
      browserTag.value = routeValue
    } else if (m === 'vocalForm') {
      browserTab.value = 'hot_new'
      browserVocalForm.value = routeValue
    } else if (m === 'all') {
      browserTab.value = 'hot_new'
    } else {
      browserTab.value = 'singer'
      browserArtist.value = {
        name: String(route.params.name || ''),
        artistKey: typeof route.query.artistKey === 'string' ? route.query.artistKey : ''
      }
    }
    viewMode.value = 'browser'
    return
  }
  if (name === 'playlists' || name === 'playlist-detail') {
    resetBrowserFilters()
    browserTab.value = 'featured'
    browserPlaylistId.value = route.params.id || null
    viewMode.value = 'browser'
    return
  }
  if (name === 'recent-history') {
    resetBrowserFilters()
    browserTab.value = 'recent'
    viewMode.value = 'browser'
    return
  }
  if (name === 'favorites') {
    resetBrowserFilters()
    browserTab.value = 'favorites'
    viewMode.value = 'browser'
    return
  }
  if (name === 'queue') {
    drawerOpen.value = true
    return
  }
  if (name === 'remote') {
    activeModal.value = 'mixer'
    return
  }
  if (name === 'lyric') {
    fullscreen.value = true
  }
}

function onSelectTile(tile) {
  resetBrowserFilters()
  browserTab.value = tile.tab
  viewMode.value = 'browser'
}

function backToDashboard() {
  viewMode.value = 'dashboard'
  if (route.name !== 'home' && route.name !== 'entry') {
    router.replace({ name: 'home' })
  }
}

function openBrowserFromFullscreen() {
  fullscreen.value = false
  viewMode.value = 'browser'
}

function openHomeFromFullscreen() {
  fullscreen.value = false
  backToDashboard()
}

function toggleAutoFullscreen() {
  autoFullscreen.value = !autoFullscreen.value
  try { localStorage.setItem('ktv_auto_fullscreen', String(autoFullscreen.value)) } catch { /* ignore */ }
  resetIdleFullscreenTimer()
}

function resetIdleFullscreenTimer() {
  clearTimeout(idleFullscreenTimer)
  if (!autoFullscreen.value || fullscreen.value || !player.nowPlaying || activeModal.value || drawerOpen.value) {
    return
  }
  idleFullscreenTimer = setTimeout(() => {
    if (autoFullscreen.value && !fullscreen.value && player.nowPlaying && !activeModal.value && !drawerOpen.value) {
      fullscreen.value = true
    }
  }, 60_000)
}

async function openGlobalSearch() {
  resetBrowserFilters()
  browserTab.value = 'song_name'
  viewMode.value = 'browser'
  await nextTick()
  browserRef.value?.switchTab?.('song_name')
  browserRef.value?.focusSearchKeyboard?.()
}

function openModal(name) {
  activeModal.value = name
}

/** 身份初始化失败后手动重试；成功后补齐可写身份并确保实时连接存在。 */
async function retryIdentity() {
  if (identityRetrying.value) return
  identityRetrying.value = true
  try {
    const profile = await api.registerUser(user.clientToken, user.nickname)
    if (!user.markRegistrationSuccess(profile)) throw new Error('服务端未返回有效用户身份')
    player.connect()
    toast(`身份已恢复：${user.nickname}`)
  } catch (error) {
    user.markRegistrationFailure(error)
    toast(error.message || '身份初始化仍然失败，请稍后重试')
  } finally {
    identityRetrying.value = false
  }
}

function toggleFullscreen() {
  fullscreen.value = !fullscreen.value
}

function toggleQuickMode() {
  if (player.tvOnline === true) {
    toast('当前检测到 TV 端在线，已锁定为 TV 控制台模式')
    return
  }
  if (player.isWebHost) {
    awaitingHostClaimToast = false
    player.setModeOverride('console')
    toast('已切换为仅控制台模式（不再在网页出声）')
  } else {
    awaitingHostClaimToast = true
    player.setModeOverride('host')
    toast('已申请接管本机作为 Web KTV 主播放端')
  }
}

function claimWebHost() {
  if (player.tvOnline === true) {
    toast('TV 端在线期间不可抢占为主屏')
    return
  }
  awaitingHostClaimToast = true
  player.setModeOverride('host')
  toast('已发送主屏接管申请，等待房间状态确认…')
}

async function togglePlay() {
  try {
    player.isPlaying ? await controls.pause() : await controls.play()
  } catch (e) { toast(e.message || '操作失败') }
}

async function toggleVocal() {
  const canVocal = player.nowPlaying?.song?.hasVocalTrack === true
    || ['DUAL_TRACK', 'DUAL_CHANNEL'].includes(player.audioLayout?.layout)
  if (!canVocal) {
    toast('当前曲目无伴唱音轨')
    return
  }
  const next = player.vocalMode === 'original' ? 'accompaniment' : 'original'
  await setVocalMode(next)
}

async function setVocalMode(mode) {
  try {
    await controls.setVocal(mode)
    toast(mode === 'original' ? '已切换为原唱' : '已切换为伴唱')
  } catch (e) { toast(e.message || '切换失败') }
}

async function swapVocalTracks() {
  const songTitle = player.nowPlaying?.song?.title || ''
  if (!await confirmDialog(`将永久更正《${songTitle}》的原唱和伴唱标记。`, { title: '纠正音轨标记' })) return
  try {
    await controls.swapVocalTracks()
    toast('已纠正并记住该歌曲的音轨映射')
  } catch (e) { toast(e.message || '纠正失败') }
}

async function restartSong() {
  try {
    await controls.restart()
    toast('重唱：从头播放')
  } catch (e) { toast(e.message || '重唱失败') }
}

async function confirmNext() {
  const title = player.nowPlaying?.song?.title || '当前歌曲'
  if (!await confirmDialog(`确认切掉《${title}》并播放下一首？`, { title: '确认切歌', tone: 'warning' })) return
  try {
    await controls.next()
    toast('已切到下一首')
  } catch (e) { toast(e.message || '切歌失败') }
}

async function seekTo(positionMs) {
  try {
    await controls.seek(positionMs)
  } catch (e) { toast(e.message || '进度跳转失败') }
}

async function adjustVolume(delta) {
  const next = Math.max(0, Math.min(100, (player.volume ?? 60) + delta))
  await setVolume(next)
}

async function setVolume(val) {
  try {
    await controls.setVolume(val)
  } catch (e) { toast(e.message || '调音量失败') }
}

async function toggleMute() {
  const targetMuted = !player.muted
  try {
    await controls.setMuted(targetMuted)
    toast(targetMuted ? '已静音' : '已取消静音')
  } catch (e) { toast(e.message || '静音切换失败') }
}

function adjustPitch() {
  toast(player.isTvConsole
    ? 'TV 端实时变调请在电视端调音菜单调节'
    : '网页浏览器端暂不支持无变速实时升降调，请保持原调或切至 TV 端调节')
}

function resetPitch() {
  toast(player.isTvConsole
    ? '请在电视端调音菜单恢复原调'
    : '当前网页端默认保持原调播放')
}

async function sendEffect(fx) {
  try {
    await controls.effect(fx.id)
    toast(`已发送现场音效：${fx.label}`)
  } catch (e) { toast(e.message || '发送音效失败') }
}

function onKeyDown(e) {
  if (e.key === 'Escape') {
    if (activeModal.value) activeModal.value = ''
    else if (drawerOpen.value) drawerOpen.value = false
    else if (fullscreen.value) fullscreen.value = false
    else if (viewMode.value === 'browser') backToDashboard()
  }
}

watch(() => route.fullPath, applyRouteIntent, { immediate: true })
watch(() => player.webPlayerRole, (role) => {
  if (!awaitingHostClaimToast) return
  if (role === 'ACTIVE' && player.isWebHost) {
    awaitingHostClaimToast = false
    toast('已接管本机为 Web KTV 主播放端')
  } else if (role === 'STANDBY' && player.modeOverride !== 'host') {
    awaitingHostClaimToast = false
    toast('未能接管为主屏，已保持控制台模式')
  }
})
watch([viewMode, fullscreen], () => nextTick(updatePipRect))
watch(
  [autoFullscreen, fullscreen, () => Boolean(player.nowPlaying), activeModal, drawerOpen],
  resetIdleFullscreenTimer,
  { immediate: true }
)

onMounted(async () => {
  await nextTick()
  updatePipRect()
  window.addEventListener('resize', updatePipRect)
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('pointerdown', resetIdleFullscreenTimer, { passive: true })
  window.addEventListener('keydown', resetIdleFullscreenTimer, { passive: true })
  if (typeof ResizeObserver !== 'undefined') {
    pipObserver = new ResizeObserver(updatePipRect)
    if (dockRef.value?.pipSlotEl) pipObserver.observe(dockRef.value.pipSlotEl)
    if (dockRef.value?.$el) pipObserver.observe(dockRef.value.$el)
  }
  try {
    const content = await api.standbyContent?.()
    if (content?.logoUrl) {
      standbyLogoUrl.value = content.logoUrl
    }
  } catch {
    // fallback to built-in standby banner asset
  }
})

onBeforeUnmount(() => {
  clearTimeout(idleFullscreenTimer)
  window.removeEventListener('resize', updatePipRect)
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('pointerdown', resetIdleFullscreenTimer)
  window.removeEventListener('keydown', resetIdleFullscreenTimer)
  pipObserver?.disconnect()
  pipObserver = null
})
</script>

<style scoped>
.ktv-kiosk-shell {
  height: 100vh;
  width: 100vw;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background-color: #090d16;
  background-size: cover;
  background-position: center;
  color: var(--text);
}
.kiosk-stage {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.identity-banner {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 36px;
  padding: 4px 16px;
  font-size: 13px;
  color: #4a1b0c;
  background: #f5c4b3;
  border-bottom: 1px solid rgba(0, 0, 0, 0.18);
}
.identity-banner button {
  flex: none;
  padding: 4px 14px;
  border-radius: 999px;
  border: 1px solid rgba(0, 0, 0, 0.28);
  background: rgba(255, 255, 255, 0.72);
  color: #4a1b0c;
  font-size: 12px;
  cursor: pointer;
}
.identity-banner button:disabled {
  opacity: 0.6;
  cursor: progress;
}

@media (max-width: 899px) {
  .ktv-kiosk-shell {
    min-height: 100vh;
    height: 100dvh;
  }
  .kiosk-stage {
    overflow-y: auto;
  }
}
</style>
