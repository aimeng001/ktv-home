<template>
  <div v-if="open" class="drawer-backdrop" @click.self="$emit('close')">
    <aside
      class="queue-drawer"
      role="dialog"
      aria-label="已点队列与历史记录"
      :style="{ backgroundImage: `linear-gradient(180deg, rgba(14, 18, 28, 0.9), rgba(8, 11, 20, 0.94)), url(${assets.queueDrawerBg})` }"
    >
      <header class="drawer-hd">
        <div class="drawer-tabs">
          <button type="button" :class="{ on: activeTab === 'queue' }" @click="activeTab = 'queue'">
            已点队列 ({{ player.queueCount }})
          </button>
          <button type="button" :class="{ on: activeTab === 'history' }" @click="openHistoryTab">
            已唱历史 ({{ history.length }})
          </button>
        </div>
        <button type="button" class="close-btn" aria-label="关闭抽屉" @click="$emit('close')">
          <X :size="18" />
        </button>
      </header>

      <div v-if="player.tvOnline === false && !player.isWebHost" class="offline-banner">
        电视未连接，当前歌曲将在排队后同步播放
      </div>

      <!-- 队列 Tab -->
      <div v-if="activeTab === 'queue'" class="drawer-body">
        <section class="host-bar">
          <div>
            <strong>{{ host.isHost ? '你是房主' : host.claimed ? `房主：${host.hostNickname}` : '尚未认领房主' }}</strong>
            <small>{{ host.isHost ? '可顶歌/删除任意队列曲目' : '首位认领者可管理聚会队列' }}</small>
          </div>
          <button v-if="!host.claimed" type="button" class="mini-btn gold" @click="claimHost">认领房主</button>
          <button v-else-if="host.isHost" type="button" class="mini-btn" @click="releaseHost">释放</button>
          <button v-if="player.queue.length > 1" type="button" class="mini-btn" @click="shuffleQueue">智能打散</button>
          <p v-if="hostStatus === 'error'" class="host-error">
            房主状态获取失败
            <button type="button" class="mini-btn" @click="refreshHost">重试</button>
          </p>
        </section>

        <section v-if="player.nowPlaying" class="now-card">
          <div class="q-avatar now" :style="!songThumbUrl(player.nowPlaying.song) ? getArtistAvatarStyle(player.nowPlaying.song?.artist || player.nowPlaying.song?.title) : undefined">
            <img
              v-if="songThumbUrl(player.nowPlaying.song)"
              :src="songThumbUrl(player.nowPlaying.song)"
              alt=""
              loading="lazy"
              decoding="async"
              @error="markThumbFailed(songThumbUrl(player.nowPlaying.song))"
            />
            <span v-else>{{ songAvatarInitial(player.nowPlaying.song) }}</span>
          </div>
          <div class="now-meta">
            <small>{{ player.nowPlaying.autoPlay ? '随机公播中 · 点歌后立即切入' : '正在演唱' }}</small>
            <strong>{{ player.nowPlaying.song?.title }}</strong>
            <span>{{ player.nowPlaying.song?.artist }} · {{ player.nowPlaying.autoPlay ? '随机公播' : `${player.nowPlaying.orderedByNick || '家人'} 点` }}</span>
          </div>
          <div class="now-actions">
            <button type="button" class="mini-btn" @click="$emit('restart')">重唱</button>
            <button type="button" class="mini-btn danger" @click="$emit('next')">切歌</button>
          </div>
        </section>

        <div v-if="!player.queue.length" class="empty-tip">{{ player.nowPlaying?.autoPlay ? '还没有待播歌曲，当前为历史随机公播，点歌后将立即切入' : '还没有待播歌曲，快去点几首歌吧' }}</div>
        <div v-else class="queue-list">
          <article v-for="(item, idx) in player.queue" :key="item.queueId" class="q-item">
            <span class="q-index">{{ idx + 1 }}</span>
            <div class="q-avatar" :style="!songThumbUrl(item.song) ? getArtistAvatarStyle(item.song?.artist || item.song?.title) : undefined">
              <img
                v-if="songThumbUrl(item.song)"
                :src="songThumbUrl(item.song)"
                alt=""
                loading="lazy"
                decoding="async"
                @error="markThumbFailed(songThumbUrl(item.song))"
              />
              <span v-else>{{ songAvatarInitial(item.song) }}</span>
            </div>
            <div class="q-meta">
              <strong>{{ item.song?.title }}</strong>
              <small>{{ item.song?.artist }} · {{ item.orderedByNick || '他人' }} 点</small>
            </div>
            <div v-if="isMine(item) || host.isHost" class="q-ops">
              <button type="button" class="mini-btn gold" :disabled="busyQueueId === item.queueId" @click="topItem(item)">{{ busyQueueId === item.queueId ? '置顶中' : '顶歌' }}</button>
              <button type="button" class="mini-btn" :disabled="busyQueueId === item.queueId" @click="cancelItem(item)">{{ busyQueueId === item.queueId ? '删除中' : '删除' }}</button>
            </div>
            <span v-else class="other-tag">他人点唱</span>
          </article>
        </div>
      </div>

      <!-- 历史 Tab -->
      <div v-else class="drawer-body">
        <div class="hist-filters">
          <button type="button" :class="{ on: !mineOnly }" @click="switchMine(false)">全部记录</button>
          <button type="button" :class="{ on: mineOnly }" @click="switchMine(true)">我唱的</button>
        </div>
        <div v-if="historyStatus === 'loading'" class="empty-tip">读取中…</div>
        <div v-else-if="historyStatus === 'error'" class="empty-tip err">
          读取失败：{{ historyError?.message || '网络错误' }}
          <button type="button" class="mini-btn" @click="loadHistory">重试</button>
        </div>
        <div v-else-if="!history.length" class="empty-tip">暂无演唱记录</div>
        <div v-else class="queue-list">
          <article v-for="entry in history" :key="entry.historyId" class="q-item">
            <div class="q-avatar" :style="!songThumbUrl(entry.song) ? getArtistAvatarStyle(entry.song?.artist || entry.song?.title) : undefined">
              <img
                v-if="songThumbUrl(entry.song)"
                :src="songThumbUrl(entry.song)"
                alt=""
                loading="lazy"
                decoding="async"
                @error="markThumbFailed(songThumbUrl(entry.song))"
              />
              <span v-else>{{ songAvatarInitial(entry.song) }}</span>
            </div>
            <div class="q-meta">
              <strong>{{ entry.song?.title }}</strong>
              <small>{{ entry.song?.artist }} · {{ entry.playedByNick }} · {{ formatTimeClock(entry.playedAt) }}</small>
            </div>
            <button type="button" class="mini-btn gold" :disabled="busyHistoryId === entry.historyId" @click="repeatSong(entry)">
              {{ busyHistoryId === entry.historyId ? '加入中' : '再唱' }}
            </button>
          </article>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'
import api, { makeControls } from '../../api/client'
import { usePlayerStore } from '../../stores/player'
import { useUserStore } from '../../stores/user'
import { useToast } from '../../composables/useToast'
import { confirmDialog } from '../../composables/useDialog'
import { formatTimeClock, KTV_ASSETS } from './ktvCatalogPresets'
import { getArtistAvatarStyle } from '../../utils/avatarColor'
import { rememberFailedThumbUrl, resolveSongThumbUrl, songAvatarInitial } from '../../utils/songThumbnail'

const assets = KTV_ASSETS

const props = defineProps({
  open: { type: Boolean, default: false }
})
defineEmits(['close', 'next', 'restart'])

const player = usePlayerStore()
const user = useUserStore()
const { toast } = useToast()
const controls = makeControls(user.ensureToken(), { onResponse: res => player.applyControlResponse(res) })

const activeTab = ref('queue')
const history = ref([])
const historyStatus = ref('idle')
const historyError = ref(null)
const mineOnly = ref(false)
const busyHistoryId = ref(null)
const busyQueueId = ref(null)
let historyReqSeq = 0

const host = ref({ claimed: false, isHost: false, hostNickname: null, revision: 0 })
const hostStatus = ref('idle')
const failedThumbUrls = ref(new Set())

function songThumbUrl(song) {
  return resolveSongThumbUrl(song, failedThumbUrls.value)
}
function markThumbFailed(url) {
  if (!url) return
  failedThumbUrls.value = rememberFailedThumbUrl(failedThumbUrls.value, url)
}

function mergeHost(value) {
  if (!value || typeof value !== 'object') return
  const incRev = Number(value.revision)
  const curRev = Number(host.value.revision) || 0
  if (Number.isFinite(incRev) ? incRev < curRev : curRev > 0) return
  host.value = {
    ...host.value,
    ...value,
    revision: Number.isFinite(incRev) ? incRev : curRev
  }
  hostStatus.value = 'ready'
}

/** 拉取房主状态；失败置 error 但保留认领按钮，避免「取不到 → 无法认领 → 永不广播」死锁。 */
async function refreshHost() {
  hostStatus.value = 'loading'
  try {
    const value = await api.roomHostStatus(user.clientToken)
    mergeHost(value)
    hostStatus.value = 'ready'
  } catch {
    hostStatus.value = 'error'
  }
}

watch(() => player.roomHost, value => mergeHost(value), { deep: true })
watch(() => player.historyRevision, () => {
  if (props.open && activeTab.value === 'history') loadHistory()
})
watch(() => props.open, (isOpen) => {
  if (isOpen) {
    refreshHost()
    loadHistory()
  }
})

onMounted(() => {
  if (user.clientToken) {
    refreshHost()
  }
})

function openHistoryTab() {
  activeTab.value = 'history'
  loadHistory()
}

function switchMine(val) {
  mineOnly.value = val
  loadHistory()
}

async function loadHistory() {
  const seq = ++historyReqSeq
  historyStatus.value = 'loading'
  historyError.value = null
  try {
    const res = await api.recentHistory(user.clientToken, mineOnly.value)
    if (seq !== historyReqSeq) return
    history.value = Array.isArray(res) ? res : []
    historyStatus.value = 'ready'
  } catch (err) {
    if (seq !== historyReqSeq) return
    historyStatus.value = 'error'
    historyError.value = err
  }
}

function isMine(item) {
  return Number.isInteger(user.serverUserId) && item.orderedBy === user.serverUserId
}

async function claimHost() {
  try {
    mergeHost(await api.claimRoomHost(user.clientToken))
    toast('已认领房主')
  } catch (e) { toast(e.message || '认领失败') }
}

async function releaseHost() {
  try {
    mergeHost(await api.releaseRoomHost(user.clientToken))
    toast('已释放房主')
  } catch (e) { toast(e.message || '释放失败') }
}

async function shuffleQueue() {
  try {
    await controls.shuffle()
    toast('已智能打散待播队列')
  } catch (e) { toast(e.message || '打散失败') }
}

async function topItem(item) {
  if (busyQueueId.value) return
  busyQueueId.value = item.queueId
  try {
    await controls.top(item.queueId)
    toast(`已置顶《${item.song?.title || ''}》`)
  } catch (e) {
    toast(e.message || '置顶失败')
  } finally {
    busyQueueId.value = null
  }
}

async function cancelItem(item) {
  if (busyQueueId.value) return
  busyQueueId.value = item.queueId
  try {
    await controls.cancel(item.queueId)
    toast(`已移除《${item.song?.title || ''}》`)
  } catch (e) {
    toast(e.message || '移除失败')
  } finally {
    busyQueueId.value = null
  }
}

async function repeatSong(entry) {
  busyHistoryId.value = entry.historyId
  try {
    const res = await api.repeatHistory(entry.historyId, user.clientToken)
    toast(`已加入队列，第 ${res.position} 位`)
  } catch (error) {
    if (error.code === 'SONG_IN_QUEUE') {
      if (await confirmDialog(error.message, { title: '歌曲已在队列中' })) {
        try {
          const retry = await api.repeatHistory(entry.historyId, user.clientToken, true)
          toast(`已重复加入，第 ${retry.position} 位`)
        } catch (err2) { toast(err2.message || '再唱失败') }
      }
    } else {
      toast(error.message || '再唱失败')
    }
  } finally {
    busyHistoryId.value = null
  }
}
</script>

<style scoped>
.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(5, 8, 14, 0.65);
  z-index: 60;
  display: flex;
  justify-content: flex-end;
}
.queue-drawer {
  width: min(480px, 94vw);
  height: 100%;
  background-color: rgba(12, 16, 26, 0.96);
  background-position: center;
  background-size: cover;
  border-left: 1px solid rgba(255, 255, 255, 0.14);
  display: flex;
  flex-direction: column;
  padding: 16px;
  gap: 12px;
}
.drawer-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  padding-bottom: 6px;
}
.drawer-tabs {
  display: flex;
  gap: 14px;
}
.drawer-tabs button {
  position: relative;
  padding: 8px 6px;
  font-size: 14px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.62);
}
.drawer-tabs button.on {
  color: #ffffff;
  font-weight: 800;
}
.drawer-tabs button.on::after {
  content: '';
  position: absolute;
  left: 16%;
  right: 16%;
  bottom: -7px;
  height: 3px;
  border-radius: 999px;
  background: var(--iktv-rose);
}
.close-btn {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.08);
}
.offline-banner {
  font-size: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 107, 97, 0.14);
  color: var(--coral-soft);
}
.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.host-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.host-error {
  flex-basis: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 12px;
  color: #f09595;
}
.host-bar > div { flex: 1; min-width: 0; }
.host-bar strong { display: block; font-size: 13px; }
.host-bar small { font-size: 11px; color: var(--dim); }
.now-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(49, 130, 251, 0.18), rgba(16, 22, 36, 0.88));
  border: 1px solid rgba(49, 130, 251, 0.42);
}
.now-card > .now-meta { flex: 1; min-width: 0; }
.now-card small { color: #93c5fd; font-size: 11px; font-weight: 700; display: block; }
.now-card strong { display: block; font-size: 15px; margin: 2px 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.now-card span { font-size: 12px; color: var(--dim); }
.q-avatar {
  width: 34px; height: 34px; border-radius: 50%; overflow: hidden; flex: none;
  display: grid; place-items: center; font-size: 14px; font-weight: 800; color: #191304;
}
.q-avatar.now { width: 42px; height: 42px; font-size: 17px; }
.q-avatar img { width: 100%; height: 100%; object-fit: cover; }
.now-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
}
.queue-list { display: flex; flex-direction: column; gap: 8px; }
.q-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.q-index { width: 22px; text-align: center; font-weight: 700; color: #7dd3fc; font-size: 13px; }
.q-meta { flex: 1; min-width: 0; }
.q-meta strong { display: block; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.q-meta small { font-size: 11px; color: var(--dim); }
.q-ops { display: flex; gap: 6px; }
.other-tag { font-size: 11px; color: var(--dim2); }
.mini-btn {
  padding: 5px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.1);
  color: var(--text);
}
.mini-btn.gold { background: var(--iktv-blue); color: #ffffff; font-weight: 700; }
.mini-btn.danger { background: rgba(233, 51, 89, 0.22); color: #fda4af; border: 1px solid rgba(233, 51, 89, 0.48); }
.hist-filters { display: flex; gap: 8px; }
.hist-filters button { padding: 5px 12px; border-radius: 999px; font-size: 12px; background: rgba(255, 255, 255, 0.07); color: var(--dim); }
.hist-filters button.on { background: var(--iktv-blue); color: #ffffff; font-weight: 700; }
.empty-tip { padding: 30px 10px; text-align: center; color: var(--dim); font-size: 13px; }
.empty-tip.err { color: var(--coral-soft); }
</style>
