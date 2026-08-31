<template>
  <div class="page">
    <header class="top"><button @click="$router.back()">‹</button><strong>主题歌单</strong><span></span></header>
    <main>
      <!-- 顶部标题区 / Hero banner -->
      <div class="hero"><div class="spark">✨</div><h1>今晚唱什么</h1><p>根据曲风、年代和聚会主题整理的精选歌单</p></div>
      <!-- 加载状态 / Loading state -->
      <div v-if="loading" class="tip">加载中…</div>
      <!-- 加载失败 / Load error -->
      <div v-else-if="loadError" class="tip error-tip" role="alert">
        <span>歌单加载失败：{{ loadError }}</span>
        <button class="retry" @click="load">重试</button>
      </div>
      <!-- 空数据提示 / Empty state -->
      <div v-else-if="!playlists.length" class="tip">暂无公开歌单，请先在管理后台生成</div>
      <!-- 歌单卡片列表 / Playlist card list -->
      <router-link v-for="playlist in playlists" :key="playlist.id" class="card" :to="{ name: 'playlist-detail', params: { id: playlist.id } }">
        <div class="cover" :style="playlist.coverUrl ? { backgroundImage: `url(${playlist.coverUrl})` } : {}"><span v-if="!playlist.coverUrl">{{ coverEmoji(playlist.theme) }}</span><em v-if="playlist.aiGenerated">AI</em></div>
        <div class="info"><h2>{{ playlist.name }}</h2><p>{{ playlist.description || playlist.theme || '家庭欢唱精选' }}</p><small>{{ playlist.songCount }} 首 · {{ previewText(playlist.preview) }}</small></div>
        <span class="arrow">›</span>
      </router-link>
    </main>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 主题歌单列表页 —— 展示按曲风、年代、聚会主题整理的精选歌单。
 * 支持 AI 生成标记，点击卡片进入歌单详情。
 *
 * Theme playlist list page — displays curated playlists organized by genre,
 * era, and party theme. Supports AI-generated badges and links to detail view.
 */
import { onMounted, ref } from 'vue'
import api from '../api/client'
import TabBar from '../components/TabBar.vue'
import { loadPlaylists } from './playlistLoader'

/** 歌单列表 / Playlist list */
const playlists = ref([])
/** 是否正在加载 / Is loading */
const loading = ref(true)
/** 加载错误 / Load error */
const loadError = ref('')
const playlistState = { items: playlists, loading, error: loadError }

// 挂载后拉取歌单列表 / Fetch playlist list on mount
function load() { return loadPlaylists(api, playlistState) }
onMounted(load)

/**
 * 将歌曲数组拼接为预览文本，用顿号分隔。
 * 若数组为空或全为假值则返回默认占位文案。
 *
 * Joins song titles into a preview string separated by "、".
 * Falls back to a placeholder when the array is empty or all falsy.
 *
 * @param {Array<{title?: string}>} values - 歌曲对象数组 / array of song objects
 * @returns {string} 预览文本 / preview text
 */
function previewText(values = []) { return values.filter(Boolean).map(song => song.title).join('、') || '等待添加歌曲' }
/**
 * 根据歌单主题关键词返回对应的封面 emoji。
 * 匹配儿歌/儿童、摇滚/热血、情歌/浪漫、怀旧/年代/经典、对唱/合唱等场景。
 *
 * Returns a cover emoji based on playlist theme keywords.
 * Matches children's songs, rock, love songs, retro/classics, and duets.
 *
 * @param {string} theme - 歌单主题标签 / playlist theme tag
 * @returns {string} emoji 字符 / emoji character
 */
function coverEmoji(theme = '') {
  if (/儿歌|儿童/.test(theme)) return '🧸' // 儿歌 / children
  if (/摇滚|热血/.test(theme)) return '🎸'  // 摇滚 / rock
  if (/情歌|浪漫/.test(theme)) return '💞'  // 情歌 / love songs
  if (/怀旧|年代|经典/.test(theme)) return '📻' // 怀旧 / retro
  if (/对唱|合唱/.test(theme)) return '👥'  // 对唱/合唱 / duets
  return '🎶' // 默认 / default
}
</script>

<style scoped>
.page{min-height:100vh;padding-bottom:74px}.top{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 14px;border-bottom:1px solid var(--line);position:sticky;top:0;background:rgba(8,10,15,.94);backdrop-filter:blur(14px);z-index:2}.top button{border:0;background:none;color:var(--text);font-size:30px;width:35px}.top span{width:35px}main{padding:16px}.hero{padding:20px 18px;margin-bottom:14px;border-radius:18px;background:radial-gradient(circle at 80% 0,rgba(240,199,66,.2),transparent 42%),linear-gradient(135deg,rgba(255,255,255,.06),rgba(255,255,255,.02));border:1px solid var(--glass-border)}.spark{font-size:26px}.hero h1{font-size:22px;margin:7px 0 4px}.hero p{color:var(--dim);font-size:12px;margin:0}.card{display:flex;align-items:center;gap:13px;padding:13px;margin-bottom:10px;background:var(--panel2);border:1px solid var(--glass-border);border-radius:15px;color:var(--text)}.cover{width:64px;height:64px;display:grid;place-items:center;position:relative;border-radius:13px;background:linear-gradient(145deg,rgba(240,199,66,.22),rgba(139,92,246,.18));background-size:cover;background-position:center;font-size:27px;flex:none}.cover em{position:absolute;right:4px;top:4px;font-size:8px;font-style:normal;color:var(--gold);border:1px solid rgba(240,199,66,.35);border-radius:6px;padding:1px 4px}.info{min-width:0;flex:1}.info h2{font-size:15px;margin:0 0 5px}.info p,.info small{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.info p{font-size:12px;color:var(--dim);margin:0 0 7px}.info small{font-size:10px;color:var(--dim2)}.arrow{color:var(--dim2);font-size:24px}.tip{text-align:center;color:var(--dim2);padding:50px 10px;font-size:13px}.error-tip{display:flex;align-items:center;justify-content:center;gap:10px;flex-wrap:wrap}.retry{padding:5px 10px;border:1px solid var(--line);border-radius:6px;color:var(--gold);font-size:11px}
</style>
