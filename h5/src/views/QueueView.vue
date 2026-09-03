<template>
  <div class="page">
    <!-- 电视离线提示 / TV offline warning -->
    <div v-if="!player.tvOnline" class="offline">电视未连接，歌曲会先排队，电视上线后自动播放</div>
    <div class="sec hd"><b>已点歌曲</b><span>{{ player.queueCount }} 首待唱</span></div>

    <!-- 房主与队列管理区 / Host & queue management -->
    <section class="sec host-wrap">
      <div class="host-card">
        <div><strong>{{ host.isHost ? '你是房主' : host.claimed ? `房主：${host.hostNickname}` : '尚未设置房主' }}</strong><small>{{ host.isHost ? '可以管理聚会队列' : host.claimed ? '所有人都可以智能打散演唱顺序' : '首位认领者可管理聚会队列' }}</small></div>
        <button v-if="!host.claimed" class="chip gold" @click="claimHost">认领房主</button>
        <button v-else-if="host.isHost" class="chip" @click="releaseHost">释放</button>
      </div>
      <button v-if="player.queue.length > 1" class="shuffle" @click="shuffleQueue">智能打散</button>
    </section>

    <!-- 正在演唱卡 -->
    <!-- 正在演唱卡片 / Now playing card -->
    <section class="sec" v-if="player.nowPlaying">
      <div class="now" :class="{ 'has-cover': nowPlayingCover }" :style="nowPlayingStyle">
        <div class="grow">
          <small>正在演唱</small><div class="t">{{ player.nowPlaying.song?.title }}
            <span class="a">· {{ player.nowPlaying.song?.artist }}</span></div>
          <div class="s">{{ player.nowPlaying.orderedByNick || '' }} 点</div>
        </div>
        <button class="cut" @click="confirmNext">切歌</button>
      </div>
    </section>

    <!-- 待播列表 -->
    <!-- 待播列表 / Upcoming queue -->
    <section class="sec grow list">
      <div v-if="!player.queue.length" class="tip">还没有待播歌曲</div>
      <div v-for="(q, i) in player.queue" :key="q.queueId" class="qrow">
        <span class="no">{{ i + 1 }}</span>
        <div class="grow">
          <div class="t">{{ q.song?.title }}</div>
          <div class="s">{{ q.song?.artist }} · {{ q.orderedByNick || '他人' }} 点</div>
        </div>
        <template v-if="isMine(q) || host.isHost">
          <button class="chip gold" @click="top(q)">顶歌</button>
          <button class="chip" @click="cancel(q)">删除</button>
        </template>
        <span v-else class="mine-tip">他人的歌</span>
      </div>

      <!-- 已播历史（P3.2）/ Playback history -->
      <div class="hist" v-if="history.length">
        <button class="hist-hd" :aria-expanded="showHist" @click="showHist = !showHist">
          <History :size="15" />已播历史（{{ history.length }} 首）<span class="grow"></span>{{ showHist ? '收起' : '展开' }}
          <ChevronUp v-if="showHist" :size="14" /><ChevronDown v-else :size="14" />
        </button>
        <div v-if="showHist" class="hist-chips">
          <button v-for="(h, i) in history" :key="i" class="chip" @click="reorder(h)">{{ h.title }}<RotateCcw :size="12" /></button>
        </div>
      </div>
    </section>

    <TabBar active="queue" />
  </div>
</template>

<script setup>
/**
 * 队列页面 — 查看当前播放、待播列表和已播历史，支持顶歌、删除、切歌及房主管理。
 *
 * Queue view — shows now-playing, upcoming queue, and playback history,
 * with support for boosting, removing, skipping tracks, and host management.
 */
import { computed, ref, onMounted, watch } from 'vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'
import api, { makeControls } from '../api/client'
import { useToast } from '../composables/useToast'
import { confirmDialog } from '../composables/useDialog'
import TabBar from '../components/TabBar.vue'
import { ChevronDown, ChevronUp, History, RotateCcw } from 'lucide-vue-next'

const player = usePlayerStore()
const user = useUserStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)

const history = ref([])
const showHist = ref(false)
const host = ref({ claimed: false, isHost: false, hostNickname: null })
const nowPlayingCover = computed(() => player.nowPlaying?.song?.coverUrl || '')
const nowPlayingStyle = computed(() => nowPlayingCover.value ? {
  backgroundImage: `linear-gradient(90deg,rgba(8,11,14,.94),rgba(8,11,14,.38)),url(${nowPlayingCover.value})`
} : {})

onMounted(async () => {
  const [loadedHistory, loadedHost] = await Promise.all([loadHistory(), api.roomHostStatus(user.clientToken).catch(() => null)])
  if (loadedHost) host.value = loadedHost
})

watch(() => player.historyRevision, loadHistory)

async function loadHistory() {
  const loadedHistory = await api.history().catch(() => [])
  history.value = loadedHistory
  return loadedHistory
}

/**
 * 从已播历史中重新点歌，将歌曲加入待播队列。
 *
 * Re-order a song from playback history back into the queue.
 * @param {Object} song - 歌曲对象 / song object (must contain `id`)
 */
async function reorder(song) {
  try {
    await controls.order(song.id)
    toast('已重新点歌')
  } catch (e) {
    toast(e.code === 'SONG_IN_QUEUE' ? (e.message || '已在队列中') : (e.message || '点歌失败'))
  }
}

/**
 * 判断当前队列项是否由本人点歌。
 * orderedBy 为服务端 user id，H5 无法直接比对 id，改用昵称匹配（家庭场景足够）。
 *
 * Check whether a queue entry was ordered by the current user.
 * The server exposes `orderedBy` as a user id; H5 matches by nickname
 * instead (serviceable for a family karaoke scenario).
 * @param {Object} q - 队列项 / queue entry
 * @returns {boolean}
 */
function isMine(q) {
  return q.orderedByNick && q.orderedByNick === user.nickname
}

/**
 * 顶歌 — 将指定队列项移至下一首播放。
 *
 * Boost a queue entry to play next.
 * @param {Object} q - 队列项 / queue entry (must contain `queueId`)
 */
async function top(q) {
  try { await controls.top(q.queueId); toast('已顶到下一首') }
  catch (e) { toast(e.message || '操作失败') }
}

/**
 * 从队列中删除自己点的歌曲。
 *
 * Remove own ordered song from the queue.
 * @param {Object} q - 队列项 / queue entry (must contain `queueId` and `song`)
 */
async function cancel(q) {
  if (!await confirmDialog(`将从队列删除《${q.song?.title}》。`, { title: '删除已点歌曲', tone: 'warning' })) return
  try { await controls.cancel(q.queueId); toast('已删除') }
  catch (e) { toast(e.message || '删除失败') }
}

/**
 * 确认并执行切歌 — 跳过当前正在播放的歌曲。
 *
 * Confirm and skip the currently playing track.
 */
async function confirmNext() {
  if (!await confirmDialog(`将切掉《${player.nowPlaying?.song?.title}》。`, { title: '确认切歌', tone: 'warning' })) return
  try { await controls.next(); toast('已切歌') }
  catch (e) { toast(e.message || '切歌失败') }
}
/**
 * 认领房主身份。
 *
 * Claim the room host role.
 */
async function claimHost() {
  try { host.value = await api.claimRoomHost(user.clientToken); toast('你已成为房主') }
  catch (error) { toast(error.message || '认领失败'); host.value = await api.roomHostStatus(user.clientToken).catch(() => host.value) }
}
/**
 * 释放房主身份，允许其他人认领。
 *
 * Release the host role so others can claim it.
 */
async function releaseHost() {
  if (!await confirmDialog('释放后其他人可认领房主身份。', { title: '释放房主身份' })) return
  try { host.value = await api.releaseRoomHost(user.clientToken); toast('已释放房主身份') }
  catch (error) { toast(error.message || '释放失败') }
}
/**
 * 智能打散队列 — 尽量避免同一位点歌人连续演唱。
 *
 * Shuffle the queue intelligently to avoid consecutive tracks from the same person.
 */
async function shuffleQueue() {
  if (!await confirmDialog('将尽量避免同一位点歌人连续演唱。', { title: '智能打散队列' })) return
  try { await controls.shuffle(); toast('队列已智能打散') }
  catch (error) { toast(error.message || '打散失败') }
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; display: flex; flex-direction: column; }
.sec { padding: 0 16px; }
.hd { display:flex;align-items:center;justify-content:space-between;padding-top:14px;margin-bottom:8px; }.hd b { font-size:18px; }.hd span { color:var(--dim2);font-size:11px; }
.host-wrap { margin-bottom:12px; }.host-card { display:flex;align-items:center;gap:12px;padding:10px 12px;border:1px solid rgba(255,198,75,.2);border-radius:6px;background:rgba(255,198,75,.06); }.host-card>div { flex:1;display:flex;flex-direction:column;gap:3px; }.host-card strong { font-size:11px; }.host-card small { color:var(--dim2);font-size:9px; }.shuffle { margin-top:8px;padding:7px 9px;border-radius:3px;background:rgba(255,198,75,.1);color:var(--gold);font-size:10px;font-weight:700; }
.chip {
  background: var(--panel2); border: 1px solid var(--glass-border); border-radius: 999px;
  padding: 5px 12px; font-size: 11px; color: var(--dim);
}
.chip.gold { color: var(--gold); border-color: rgba(240,199,66,.25); }
.now { min-height:145px;position:relative;overflow:hidden;border-radius:7px;padding:18px;display:flex;align-items:flex-end;background:linear-gradient(135deg,#222936,#141820);background-position:center;background-size:cover; }
.now small { color:var(--coral);font-size:9px;font-weight:800; }
.cover {
  width: 56px; height: 56px; border-radius: 10px; flex: none;
  background: radial-gradient(circle at 30% 26%, rgba(240,199,66,.12), transparent 42%),
              linear-gradient(145deg, rgba(40,46,66,.85), rgba(20,24,36,.92));
  border: 1px solid var(--glass-border);
}
.now .t { font-size: 14px; font-weight: 700; }
.now .a { color: var(--dim); font-weight: 400; font-size: 12px; }
.now .s { font-size: 11px; color: var(--dim); margin-top: 3px; }
.cut { position:absolute;right:13px;top:13px;padding:6px 8px;border:1px solid rgba(255,255,255,.25);border-radius:4px;color:#fff;background:rgba(8,10,14,.5);font-size:10px; }
.list { margin-top: 14px; overflow-y: auto; }
.qrow { display: flex; align-items: center; gap: 10px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.qrow .no { width: 22px; text-align: center; color: var(--dim); font-weight: 700; }
.qrow .t { font-size: 15px; font-weight: 600; }
.qrow .s { font-size: 12px; color: var(--dim); margin-top: 3px; }
.mine-tip { font-size: 11px; color: var(--dim2); }
.tip { color: var(--dim2); font-size: 13px; padding: 30px 0; text-align: center; }
.offline {
  margin: 12px 16px 0; padding: 10px 14px; border-radius: 10px; font-size: 12px;
  background: rgba(248,113,113,.1); border: 1px solid rgba(248,113,113,.3); color: var(--red);
}
.hist { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 12px; }
.hist-hd { width:100%;display:flex;align-items:center;gap:6px;font-size:12px;color:var(--dim);padding:6px 0;text-align:left; }
.hist-chips { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.hist-chips .chip {
  display:inline-flex;align-items:center;gap:5px;background:var(--panel2);border:1px solid var(--glass-border);border-radius:999px;
  padding:5px 10px;font-size:11px;color:var(--dim);
}
</style>
