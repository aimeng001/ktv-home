<template>
  <div class="page">
    <header class="top"><button @click="$router.back()">‹</button><strong>最近演唱</strong><span></span></header>
    <main>
      <div class="intro"><span>🕘</span><div><h1>今晚唱过的歌</h1><p>想再来一遍，直接加入队列</p></div></div>
      <div class="filters"><button :class="{ on: !mineOnly }" @click="setMine(false)">全部</button><button :class="{ on: mineOnly }" @click="setMine(true)">我唱的</button></div>
      <div v-if="historyStatus === 'loading'" class="tip">加载中…</div>
      <div v-else-if="historyStatus === 'error'" class="tip error-tip">演唱记录读取失败：{{ historyError?.message || '网络错误，请稍后重试' }} <button class="retry" @click="load">重试</button></div>
      <div v-else-if="!items.length" class="tip">{{ mineOnly ? '你还没有唱过歌曲' : '今晚还没有演唱记录' }}</div>
      <div v-else class="list">
        <article v-for="item in items" :key="item.historyId" class="row">
          <div class="cover"><img v-if="item.song.coverUrl" :src="item.song.coverUrl" /><span v-else>🎵</span></div>
          <div class="info"><strong>{{ item.song.title }}</strong><small>{{ item.song.artist }}</small><em>{{ item.playedByNick }} · {{ timeText(item.playedAt) }}</em></div>
          <button @click="repeat(item)" :disabled="busyId === item.historyId">{{ busyId === item.historyId ? '加入中' : '再唱' }}</button>
        </article>
      </div>
    </main>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 最近演唱历史页面 —— 展示今晚已唱过的歌曲，支持按"全部/我唱的"筛选，
 * 并可一键将历史歌曲重新加入演唱队列。
 *
 * Recent history page — shows songs sung tonight, supports filtering by
 * "All / Mine", and allows one-tap re-adding to the singing queue.
 */
import { onMounted, ref, watch } from 'vue'
import api from '../api/client'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import { confirmDialog } from '../composables/useDialog'
import { useAsyncResource } from '../composables/useAsyncResource'
import TabBar from '../components/TabBar.vue'

const user = useUserStore()
const player = usePlayerStore()
const { toast } = useToast()
const mineOnly = ref(false)
const busyId = ref(null)
const historyResource = useAsyncResource((mine) => api.recentHistory(user.clientToken, mine), [])
const items = historyResource.data
const historyStatus = historyResource.status
const historyError = historyResource.error

onMounted(load)
watch(() => player.historyRevision, load)
/**
 * 加载最近演唱历史列表。
 *
 * Loads the recent singing history list.
 */
async function load() {
  await historyResource.load(mineOnly.value)
}
/** 切换筛选模式（全部/我唱的）并重新加载。 / Toggle filter mode (all/mine) and reload. */
function setMine(value) { mineOnly.value = value; load() }
/**
 * 将历史歌曲重新加入演唱队列。若歌曲已在队列中，弹窗确认后强制重复加入。
 * @param {Object} item - 历史记录条目，含 historyId、song 等字段
 *
 * Re-adds a historical song to the singing queue. If the song is already
 * queued, prompts for confirmation before forcing it in.
 * @param {Object} item - History entry with historyId, song, etc.
 */
async function repeat(item) {
  busyId.value = item.historyId
  try {
    const result = await api.repeatHistory(item.historyId, user.clientToken)
    toast(`已加入队列，第 ${result.position} 位`)
  } catch (error) {
    if (error.code === 'SONG_IN_QUEUE') {
      if (await confirmDialog(error.message, { title: '歌曲仍在队列中' })) {
        try { const result = await api.repeatHistory(item.historyId, user.clientToken, true); toast(`已重复加入，第 ${result.position} 位`) }
        catch (retryError) { toast(retryError.message || '再唱失败') }
      }
    } else toast(error.message || '再唱失败')
  } finally { busyId.value = null }
}
/**
 * 格式化演唱时间：当天显示时分（如 21:30），非当天显示月/日（如 7/31）。
 * @param {string|number|Date} value - 时间值
 * @returns {string} 格式化后的时间文本
 *
 * Formats play time: today shows HH:MM, other days show M/D.
 * @param {string|number|Date} value - Time value
 * @returns {string} Formatted time string
 */
function timeText(value) {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  return date.toLocaleDateString([], { month: 'numeric', day: 'numeric' })
}
</script>

<style scoped>
.page{min-height:100vh;padding-bottom:74px}.top{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 14px;border-bottom:1px solid var(--line);position:sticky;top:0;background:rgba(8,10,15,.94);backdrop-filter:blur(14px);z-index:2}.top button{border:0;background:none;color:var(--text);font-size:30px;width:35px}.top span{width:35px}main{padding:16px}.intro{display:flex;align-items:center;gap:13px;padding:17px;border:1px solid var(--glass-border);border-radius:16px;background:radial-gradient(circle at 90% 0,rgba(240,199,66,.18),transparent 45%),var(--panel2)}.intro>span{font-size:32px}.intro h1{font-size:19px;margin:0 0 4px}.intro p{font-size:12px;color:var(--dim);margin:0}.filters{display:flex;gap:7px;margin:14px 0 9px}.filters button{border:1px solid var(--glass-border);background:var(--panel2);color:var(--dim);border-radius:999px;padding:6px 16px}.filters button.on{color:var(--gold);border-color:rgba(240,199,66,.35);background:var(--gold-glow)}.list{border:1px solid var(--glass-border);border-radius:15px;background:var(--panel2);padding:0 12px}.row{display:flex;align-items:center;gap:11px;padding:11px 0;border-bottom:1px solid var(--line)}.row:last-child{border-bottom:0}.cover{width:48px;height:48px;display:grid;place-items:center;border-radius:10px;overflow:hidden;background:rgba(255,255,255,.05);flex:none}.cover img{width:100%;height:100%;object-fit:cover}.info{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px}.info strong,.info small,.info em{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.info strong{font-size:14px}.info small{font-size:11px;color:var(--dim)}.info em{font-size:10px;color:var(--dim2);font-style:normal}.row>button{border:1px solid rgba(240,199,66,.28);background:var(--gold-glow);color:var(--gold);border-radius:9px;padding:7px 11px}.row>button:disabled{opacity:.5}.tip{text-align:center;color:var(--dim2);padding:55px 10px;font-size:13px}.error-tip{color:var(--coral)}.retry{margin-left:7px;border:1px solid var(--glass-border);border-radius:7px;padding:4px 9px;color:var(--gold);background:var(--panel2)}
</style>
