<template>
  <!-- 有歌曲正在播放时 / When a song is currently playing -->
  <div v-if="player.nowPlaying" class="now" @click="$router.push({ name: 'lyric' })">
    <div class="cover" :style="coverStyle"><Music2 v-if="!coverUrl" :size="20" /></div>
    <div class="grow">
      <div class="title">
        {{ player.nowPlaying.song?.title }}
        <span class="artist">· {{ player.nowPlaying.song?.artist }}</span>
      </div>
      <div class="bar"><i :style="{ width: progressPct + '%' }"></i></div>
    </div>
    <span class="chip">{{ stateText }}</span>
  </div>
  <!-- 无人点歌时的空状态 / Empty state when no songs are queued -->
  <div v-else class="now empty">
    <div class="grow hint">还没人点歌，来点第一首吧</div>
  </div>
</template>

<script setup>
/**
 * 底部正在播放栏组件 —— 显示当前歌曲信息、播放进度及状态。
 *
 * Bottom now-playing bar — shows current song info, playback progress, and status.
 */
import { computed } from 'vue'
import { usePlayerStore } from '../stores/player'
import { Music2 } from 'lucide-vue-next'

const player = usePlayerStore()
const coverUrl = computed(() => player.nowPlaying?.song?.coverUrl || '')
const coverStyle = computed(() => coverUrl.value ? { backgroundImage: `url(${coverUrl.value})` } : {})

/**
 * 当前播放进度百分比（0–100）。
 *
 * Current playback progress percentage (0–100).
 */
const progressPct = computed(() => {
  const dur = player.nowPlaying?.song?.durationMs || 0
  if (!dur) return 0
  return Math.min(100, Math.round((player.positionMs / dur) * 100))
})

/**
 * 播放状态对应的中文展示文案。
 *
 * Chinese display text for current playback state.
 */
const stateText = computed(() => {
  const playbackStatus = String(player.playback?.status || 'IDLE').toUpperCase()
  if (playbackStatus === 'PREPARING') return '准备 MV 中'
  if (playbackStatus === 'FAILED') return 'MV 准备失败'
  return ({ playing: '演唱中', paused: '已暂停', idle: '' }[player.state] || '')
})
</script>

<style scoped>
.now { min-height:68px;background:#171b22;border-left:3px solid var(--coral);padding:10px;display:flex;gap:11px;align-items:center; }
.now.empty { border-color: var(--glass-border); justify-content: center; }
.hint { color: var(--dim2); font-size: 13px; text-align: center; }
.cover {
  width:48px;height:48px;border-radius:6px;flex:none;display:grid;place-items:center;
  background:#202630 center / cover no-repeat;color:var(--dim2);border:1px solid var(--glass-border);
}
.title { font-size: 14px; font-weight: 700; }
.artist { color: var(--dim); font-weight: 400; font-size: 12px; }
.bar { height: 4px; background: rgba(255,255,255,.06); border-radius: 3px; margin-top: 8px; overflow: hidden; }
.bar i { display:block;height:100%;background:var(--coral);transition:width .5s linear; }
.chip {
  flex:none;color:#192019;border:0;border-radius:3px;padding:5px 7px;font-size:9px;font-weight:800;background:var(--mint);
}
</style>
