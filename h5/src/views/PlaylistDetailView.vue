<template>
  <div class="page">
    <!-- 顶部导航栏 / Top navigation bar -->
    <header class="top"><button @click="$router.back()">‹</button><strong>歌单详情</strong><button class="share" @click="share">↗</button></header>
    <main v-if="playlist">
      <!-- 歌单封面与信息 / Playlist cover & info -->
      <section class="hero">
        <div class="cover" :style="playlist.coverUrl ? { backgroundImage: `url(${playlist.coverUrl})` } : {}"><span v-if="!playlist.coverUrl">{{ coverEmoji(playlist.theme) }}</span></div>
        <div class="hero-info"><span v-if="playlist.aiGenerated" class="ai">AI 精选</span><h1>{{ playlist.name }}</h1><p>{{ playlist.description || '家庭欢唱精选歌单' }}</p><small>{{ playlist.songs.length }} 首歌曲</small></div>
      </section>
      <!-- 整单点歌按钮 / Bulk order button -->
      <button class="order-all" @click="orderAll" :disabled="ordering || !playlist.songs.length">{{ ordering ? '正在加入…' : '▶ 整单点歌' }}</button>
      <!-- 歌曲列表 / Song list -->
      <section class="songs">
        <SongRow v-for="(song,index) in playlist.songs" :key="song.id" :song="song" :rank="index+1" :ordered="orderedIds.has(song.id)" @order="orderSong" />
      </section>
    </main>
    <!-- 加载中 / Loading state -->
    <div v-else-if="loading" class="tip">加载中…</div>
    <!-- 歌单不存在 / Playlist not found -->
    <div v-else class="tip">歌单不存在或尚未公开</div>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 歌单详情页 — 展示歌单封面、歌曲列表，支持单首点歌、整单点歌和分享。
 *
 * Playlist detail page — displays playlist cover, song list,
 * and supports single-song order, bulk order, and sharing.
 */
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import api, { makeControls } from '../api/client'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import { useOrderLock } from '../composables/useOrderLock'
import SongRow from '../components/SongRow.vue'
import TabBar from '../components/TabBar.vue'

const route = useRoute()
const user = useUserStore()
const controls = makeControls(user.clientToken)
const { toast } = useToast()
const { executeOrder } = useOrderLock()

/** 当前歌单数据 / Current playlist data */
const playlist = ref(null)
/** 是否正在加载 / Whether data is loading */
const loading = ref(true)
/** 是否正在整单点歌 / Whether bulk ordering is in progress */
const ordering = ref(false)
/** 已点歌曲 ID 集合 / Set of already-ordered song IDs */
const orderedIds = reactive(new Set())

onMounted(async () => { try { playlist.value = await api.playlistDetail(route.params.id) } catch { playlist.value = null } finally { loading.value = false } })

/**
 * 将单首歌曲加入点歌队列（带防抖并发锁）。
 *
 * Order a single song into the playback queue.
 *
 * @param {Object} song - 歌曲对象 / Song object
 * @param {string} song.id - 歌曲 ID / Song ID
 */
async function orderSong(song) {
  await executeOrder(song.id, async () => {
    try {
      await controls.order(song.id)
      orderedIds.add(song.id)
      toast('已加入队列')
    } catch (error) {
      toast(error.code === 'SONG_IN_QUEUE' ? (error.message || '已在队列中') : (error.message || '点歌失败'))
    }
  })
}
/**
 * 整单点歌 — 将歌单中所有歌曲一次性加入点歌队列。
 *
 * Bulk-order all songs in the playlist at once.
 */
async function orderAll() {
  ordering.value = true
  try {
    const result = await api.orderPlaylist(playlist.value.id, user.clientToken)
    const queuedSongs = [result.snapshot?.playing?.song, ...(result.snapshot?.list || []).map(item => item.song)].filter(Boolean)
    queuedSongs.forEach(song => orderedIds.add(song.id))
    toast(result.skipped ? `已加入 ${result.ordered} 首，跳过 ${result.skipped} 首` : `已加入 ${result.ordered} 首歌曲`)
  } catch (error) { toast(error.message || '整单点歌失败') }
  finally { ordering.value = false }
}
/**
 * 分享歌单 — 优先使用 Web Share API，回退到复制链接。
 *
 * Share the playlist — prefers Web Share API, falls back to copying the link.
 */
async function share() {
  const data = { title: playlist.value?.name || '家庭KTV主题歌单', text: playlist.value?.description || '一起来唱歌吧', url: location.href }
  try {
    if (navigator.share) await navigator.share(data)
    else { await navigator.clipboard.writeText(location.href); toast('歌单链接已复制') }
  } catch (error) { if (error.name !== 'AbortError') toast('分享失败，请复制浏览器地址') }
}
/**
 * 根据歌单主题返回对应的封面 emoji。
 *
 * Return a cover emoji matching the playlist theme.
 *
 * @param {string} [theme=''] - 主题名称 / Theme name
 * @returns {string} 对应的 emoji / Matching emoji
 */
function coverEmoji(theme = '') {
  if (/儿歌|儿童/.test(theme)) return '🧸'; if (/摇滚|热血/.test(theme)) return '🎸'; if (/情歌|浪漫/.test(theme)) return '💞'; if (/怀旧|年代|经典/.test(theme)) return '📻'; if (/对唱|合唱/.test(theme)) return '👥'; return '🎶'
}
</script>

<style scoped>
.page{min-height:100vh;padding-bottom:74px}.top{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 14px;border-bottom:1px solid var(--line);position:sticky;top:0;background:rgba(8,10,15,.94);backdrop-filter:blur(14px);z-index:2}.top button{border:0;background:none;color:var(--text);font-size:30px;width:35px}.top .share{font-size:22px}main{padding:16px}.hero{display:flex;gap:16px;align-items:center;padding:16px;border-radius:17px;background:radial-gradient(circle at 90% 0,rgba(240,199,66,.18),transparent 45%),var(--panel2);border:1px solid var(--glass-border)}.cover{width:94px;height:94px;border-radius:17px;display:grid;place-items:center;background:linear-gradient(145deg,rgba(240,199,66,.24),rgba(139,92,246,.2));background-size:cover;background-position:center;font-size:42px;flex:none}.hero-info{min-width:0}.hero h1{font-size:20px;margin:5px 0}.hero p{font-size:12px;color:var(--dim);margin:0 0 8px;line-height:1.5}.hero small{color:var(--dim2)}.ai{font-size:9px;color:var(--gold);border:1px solid rgba(240,199,66,.3);padding:2px 6px;border-radius:999px}.order-all{width:100%;margin:14px 0 8px;padding:12px;border:0;border-radius:12px;background:linear-gradient(135deg,var(--gold),var(--gold2));color:#1c1705;font-weight:800}.order-all:disabled{opacity:.5}.songs{background:var(--panel2);border:1px solid var(--glass-border);border-radius:15px;padding:0 12px}.tip{text-align:center;color:var(--dim2);padding:70px 15px}
</style>
