<template>
  <div class="page">
    <header class="topbar">
      <div class="brand"><img class="brand-mark" src="../assets/home-ktv-logo.png" alt="Home KTV"><div><b>Home KTV</b><small>客厅欢唱局</small></div></div>
      <span class="room" :class="{unknown: player.tvOnline === null, offline: player.tvOnline === false}"><i></i>{{ player.tvOnline === null ? '电视状态未知' : player.tvOnline ? '电视在线' : '电视离线' }}</span>
    </header>

    <section class="sec greeting">
      <h1>晚上好，{{ user.nickname }}</h1>
      <p>想唱什么？曲库已经准备好了。</p>
    </section>

    <!-- ① 当前播放条 / Now Playing Bar -->
    <section class="sec">
      <div class="search" @click="$router.push({ name: 'search' })">
        <Search :size="19" /><span class="ph">搜索歌名、歌手或拼音</span>
      </div>
    </section>

    <section class="sec"><NowPlayingBar /></section>

    <!-- ③ 分类宫格 / Category Grid -->
    <section class="sec">
      <div class="quick-grid">
        <div v-for="c in cats" :key="c.label" class="cat" @click="onCat(c)">
          <div class="ic"><component :is="c.icon" :size="18" /></div>{{ c.label }}
        </div>
      </div>
    </section>

    <!-- ④ 热门榜 / Hot Ranking -->
    <section class="sec grow">
      <div class="row hd"><b>今晚热门</b><span class="sub">近 30 天点唱</span></div>
      <div v-if="hotStatus === 'loading'" class="tip">加载中…</div>
      <div v-else-if="hotStatus === 'error'" class="tip error-tip">热门歌曲读取失败：{{ hotError?.message || '网络错误，请稍后重试' }} <button class="retry" @click="loadHot">重试</button></div>
      <div v-else-if="!hot.length" class="tip">曲库还没有歌，先去后台扫描入库</div>
      <SongRow v-for="(s, i) in hot" :key="s.id" :song="s" :rank="i + 1"
               :ordered="orderedIds.has(s.id)" @order="order" />
    </section>

    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 首页视图 —— 家庭KTV 主页面。
 * 包含：当前播放条、搜索入口、分类宫格、热门榜。
 *
 * Home view — the main page of Home KTV.
 * Contains: now-playing bar, search entry, category grid, hot ranking.
 */
import { onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import api, { makeControls } from '../api/client'
import { useUserStore } from '../stores/user'
import { usePlayerStore } from '../stores/player'
import { useToast } from '../composables/useToast'
import TabBar from '../components/TabBar.vue'
import SongRow from '../components/SongRow.vue'
import NowPlayingBar from '../components/NowPlayingBar.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { useOrderLock } from '../composables/useOrderLock'
import { Search, UserRound, Sparkles, UsersRound, ListMusic, Heart, Languages, LayoutGrid } from 'lucide-vue-next'

const router = useRouter()
const user = useUserStore()
const player = usePlayerStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)
const { executeOrder } = useOrderLock()

const { data: hot, status: hotStatus, error: hotError, load: loadHot } = useAsyncResource(async () => {
  const ranked = await api.ranking(30)
  return ranked.length ? ranked : await api.newSongs()
}, [])
const orderedIds = reactive(new Set())

/** 首页分类宫格数据 / Home page category grid items */
const cats = [
  { icon: UserRound, label: '歌手' },
  { icon: Languages, label: '语种' },
  { icon: LayoutGrid, label: '分类' },
  { icon: Sparkles, label: '新歌' },
  { icon: UsersRound, label: '对唱' },
  { icon: ListMusic, label: '歌单' },
  { icon: Heart, label: '我的收藏' }
]

/**
 * 页面挂载时加载热门歌曲列表。
 * 优先请求真实点唱排行；若为空（新库无播放记录）则退回最新入库。
 *
 * Load hot song list on mount.
 * Prefer real play-count ranking; fall back to latest songs if ranking is empty (new library with no play history).
 */
onMounted(loadHot)

/**
 * 点歌：将指定歌曲加入播放队列（带防抖并发锁）。
 * @param {Object} song - 歌曲对象，需含 id 字段
 */
async function order(song) {
  await executeOrder(song.id, async () => {
    try {
      await controls.order(song.id)
      orderedIds.add(song.id)
      toast('已加入队列')
    } catch (e) {
      if (e.code === 'SONG_IN_QUEUE') {
        toast(e.message || '这首歌已在队列中')
      } else {
        toast(e.message || '点歌失败')
      }
    }
  })
}

/**
 * 根据分类条目跳转到对应页面。
 * @param {Object} c - 分类对象，含 label 字段
 */
function onCat(c) {
  if (c.label === '歌手') router.push({ name: 'browse', query: { tab: 'artists' } })
  else if (c.label === '语种') router.push({ name: 'browse', query: { tab: 'languages' } })
  else if (c.label === '分类') router.push({ name: 'browse', query: { tab: 'tags' } })
  else if (c.label === '歌单') router.push({ name: 'playlists' })
  else if (c.label === '新歌') router.push({ name: 'artist', params: { name: 'all' }, query: { mode: 'all', sort: 'new' } })
  else if (c.label === '对唱') router.push({ name: 'artist', params: { name: 'all' }, query: { mode: 'vocalForm', value: '对唱' } })
  else if (c.label === '儿歌') router.push({ name: 'artist', params: { name: 'all' }, query: { mode: 'tag', value: '儿歌' } })
  else if (c.label === '影视金曲') router.push({ name: 'artist', params: { name: 'all' }, query: { mode: 'tag', value: '影视' } })
  else if (c.label === '最近唱') router.push({ name: 'recent-history' })
  else if (c.label === '我的收藏') router.push({ name: 'favorites' })
  else toast('分类「' + c.label + '」即将开放')
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; display: flex; flex-direction: column; }
.topbar { height: 58px; padding: 8px 16px 0; display: flex; align-items: center; justify-content: space-between; }
.brand { display:flex;align-items:center;gap:9px; }.brand-mark { width:30px;height:30px;border-radius:7px;object-fit:cover; }
.brand b,.brand small { display:block; }.brand b { font-size:14px; }.brand small { margin-top:2px;color:var(--dim2);font-size:9px; }
.room { display:flex;align-items:center;gap:6px;color:var(--mint);font-size:11px; }.room i { width:6px;height:6px;border-radius:50%;background:var(--mint); }.room.unknown { color:var(--dim); }.room.unknown i { background:var(--dim); }.room.offline { color:var(--coral); }.room.offline i { background:var(--coral); }
.sec { padding: 0 16px; margin-top: 12px; }
.greeting { margin-top:18px; }.greeting h1 { font-size:24px;line-height:1.2; }.greeting p { margin-top:5px;color:var(--dim);font-size:12px; }
.search {
  height:46px;display:flex;align-items:center;gap:9px;background:var(--panel);border:1px solid rgba(255,198,75,.28);border-radius:8px;
  padding:0 13px;color:var(--gold);font-size:13px;
}
.search .ph { color: var(--dim2); }
.quick-grid { display:grid;grid-template-columns:repeat(4,1fr);gap:13px 4px; }
.cat {
  color:var(--dim);padding:2px 0;text-align:center;font-size:11px;transition:var(--transition);
}
.cat:active { transform: scale(.95); }
.cat .ic { width:42px;height:42px;display:grid;place-items:center;margin:0 auto 6px;border:1px solid var(--line);border-radius:50%;color:var(--gold);background:var(--panel); }
.hd { margin-bottom:4px;padding-bottom:8px;border-bottom:1px solid var(--line); }
.hd b { font-size: 15px; }
.hd .sub { margin-left:auto;font-size:11px;color:var(--dim2); }
.tip { color: var(--dim2); font-size: 13px; padding: 20px 0; text-align: center; }
.error-tip { color: var(--coral); display: flex; align-items: center; justify-content: center; gap: 8px; }
.retry { border: 1px solid var(--line); background: var(--panel2); color: var(--text); border-radius: 4px; padding: 2px 8px; font-size: 12px; }
.row { display: flex; align-items: center; }
</style>
