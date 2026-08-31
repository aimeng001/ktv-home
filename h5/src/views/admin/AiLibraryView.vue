<template>
  <AdminLayout active="ai">
    <header class="page-head">
      <div>
        <h1>主题歌单</h1>
        <p>管理 H5 展示歌单，并使用 AI 根据曲库标签策划歌曲。</p>
      </div>
      <div class="page-actions">
        <router-link v-if="aiConfigState === 'ready' && !aiConfigured" class="secondary action-button" :to="{ name: 'admin-settings', query: { section: 'ai' } }">
          <Settings2 :size="15" />配置 AI
        </router-link>
        <button class="secondary icon-text-button" :disabled="loading" title="刷新歌单" @click="refreshAll">
          <RefreshCw :size="15" :class="{ spin: loading }" />刷新
        </button>
      </div>
    </header>

    <div v-if="message" class="notice">{{ message }}</div>
    <div v-if="playlistsError" class="notice error-notice" role="alert"><span>歌单读取失败：{{ playlistsError }}</span><button class="text-btn" @click="loadPlaylists">重试</button></div>
    <div v-if="aiConfigError" class="notice error-notice" role="alert"><span>AI 配置读取失败：{{ aiConfigError }}</span><button class="text-btn" @click="loadAiConfig">重试</button></div>

    <section class="filter-panel">
      <label>
        <span>关键词</span>
        <input v-model.trim="filters.keyword" placeholder="歌单名称、主题或描述" @keyup.enter="search" />
      </label>
      <label>
        <span>创建方式</span>
        <span class="select-control">
          <select v-model="filters.source" @change="search">
            <option value="">全部方式</option>
            <option value="ai">AI 生成</option>
            <option value="manual">人工创建</option>
          </select>
          <ChevronDown :size="15" aria-hidden="true" />
        </span>
      </label>
      <label>
        <span>展示状态</span>
        <span class="select-control">
          <select v-model="filters.visibility" @change="search">
            <option value="">全部状态</option>
            <option value="public">H5 公开</option>
            <option value="hidden">暂不公开</option>
          </select>
          <ChevronDown :size="15" aria-hidden="true" />
        </span>
      </label>
      <div class="filter-actions">
        <button class="secondary" @click="resetFilters">重置</button>
        <button class="primary" @click="search"><Search :size="14" />查询</button>
      </div>
    </section>

    <section class="table-panel">
      <div class="toolbar">
        <span>共 {{ filteredPlaylists.length }} 个主题歌单</span>
        <div class="toolbar-actions">
          <button class="secondary action-button" @click="newPlaylist"><Plus :size="15" />新建歌单</button>
          <button class="primary action-button" :disabled="aiConfigState !== 'ready' || !aiConfigured" :title="aiConfigState === 'ready' && aiConfigured ? '使用 AI 策划歌单' : '请先配置 AI 模型'" @click="openGenerator">
            <Sparkles :size="15" />AI 生成歌单
          </button>
        </div>
      </div>

      <div v-if="pagedPlaylists.length" class="table-scroll">
        <table>
          <thead>
            <tr><th>歌单</th><th>主题</th><th>歌曲数量</th><th>创建方式</th><th>展示状态</th><th>更新时间</th><th class="action-cell">操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="item in pagedPlaylists" :key="item.id">
              <td>
                <div class="playlist-name-cell">
                  <div class="cover-thumb" :style="coverStyle(item)"><Music2 v-if="!item.coverUrl" :size="18" /></div>
                  <div><strong>{{ item.name }}</strong><small>{{ item.description || '暂无描述' }}</small></div>
                </div>
              </td>
              <td>{{ item.theme || '未设置' }}</td>
              <td><strong class="song-count">{{ item.songCount || 0 }}</strong><small>人工加入 {{ item.manualCount || 0 }} 首</small></td>
              <td><span class="status" :class="item.aiGenerated ? 'blue' : 'neutral'">{{ item.aiGenerated ? 'AI 生成' : '人工创建' }}</span></td>
              <td><span class="status" :class="item.publicVisible ? 'green' : 'amber'">{{ item.publicVisible ? 'H5 公开' : '暂不公开' }}</span></td>
              <td class="updated-at">{{ formatTime(item.updatedAt) }}</td>
              <td class="action-cell">
                <div class="row-actions">
                  <button class="link" title="管理歌曲" @click="openSongs(item)"><ListMusic :size="14" />歌曲</button>
                  <button class="link" title="编辑歌单" @click="openEdit(item)"><Pencil :size="14" />编辑</button>
                  <button class="link danger-text" title="删除歌单" @click="deletePlaylist(item)"><Trash2 :size="14" />删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="playlistsError" class="table-empty error-empty" role="alert"><ListMusic :size="24" /><strong>歌单暂时无法读取</strong><button class="text-btn" @click="loadPlaylists">重试</button></div>
      <div v-else class="table-empty"><ListMusic :size="24" /><span>暂无符合条件的主题歌单</span></div>

      <div class="pager">
        <span>第 {{ page + 1 }} / {{ totalPages }} 页</span>
        <div><button class="secondary" :disabled="page === 0" @click="go(page - 1)">上一页</button><button class="secondary" :disabled="page >= totalPages - 1" @click="go(page + 1)">下一页</button></div>
      </div>
    </section>

    <div v-if="editorOpen" class="mask" @click.self="closeEditor">
      <section class="modal editor-modal" role="dialog" aria-modal="true" :aria-label="playlistForm.id ? '编辑歌单' : '新建歌单'">
        <header class="modal-head">
          <div><h2>{{ playlistForm.id ? '编辑歌单' : '新建歌单' }}</h2><p>维护歌单基础信息和 H5 展示状态</p></div>
          <button class="icon-button" title="关闭" :disabled="busy" @click="closeEditor"><X :size="18" /></button>
        </header>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field"><span>歌单名称</span><input v-model.trim="playlistForm.name" maxlength="100" placeholder="请输入歌单名称" /></label>
            <label class="field"><span>主题</span><input v-model.trim="playlistForm.theme" maxlength="100" placeholder="例如：家庭聚会" /></label>
            <label class="field wide"><span>描述</span><textarea v-model.trim="playlistForm.description" rows="4" maxlength="500" placeholder="介绍歌单内容和适用场景"></textarea></label>
          </div>
          <div v-if="playlistForm.id" class="cover-row">
            <div class="cover-preview" :style="coverStyle(selectedPlaylist)"><Music2 v-if="!selectedPlaylist?.coverUrl" :size="25" /></div>
            <div><strong>歌单封面</strong><small>支持 JPG、PNG、WebP，最大 5MB</small><label class="upload-button"><Upload :size="14" />上传封面<input type="file" accept="image/jpeg,image/png,image/webp" @change="uploadCover" /></label></div>
          </div>
          <label class="check-row"><input v-model="playlistForm.publicVisible" type="checkbox" /><span><strong>在手机 H5 公开展示</strong><small>关闭后歌单仍保留在管理后台</small></span></label>
        </div>
        <footer class="modal-actions">
          <button class="secondary" :disabled="busy" @click="closeEditor">取消</button>
          <button class="primary action-button" :disabled="busy || !playlistForm.name" @click="savePlaylist"><Check :size="15" />保存歌单</button>
        </footer>
      </section>
    </div>

    <div v-if="generatorOpen" class="mask" @click.self="closeGenerator">
      <section class="modal generator-modal" role="dialog" aria-modal="true" aria-label="AI 生成歌单">
        <header class="modal-head">
          <div><h2>AI 生成歌单</h2><p>根据已刮削的曲库元数据选歌，预览确认后再保存</p></div>
          <button class="icon-button" title="关闭" :disabled="busy" @click="closeGenerator"><X :size="18" /></button>
        </header>
        <div class="modal-body">
          <label class="field"><span>策划要求</span><textarea v-model.trim="playlistInstruction" rows="4" placeholder="例如：适合周末家庭聚会，国语和粤语各半，节奏轻快"></textarea></label>
          <div class="generator-controls">
            <label class="field limit-field"><span>最多歌曲数</span><input v-model.number="generateForm.limit" type="number" min="1" max="100" @blur="normalizePlaylistLimit" /></label>
            <button class="primary action-button" :disabled="busy || !playlistInstruction" @click="previewPlaylist"><Sparkles :size="15" />{{ busy ? '生成中' : '生成预览' }}</button>
            <small>歌单最多 100 首，AI 可以返回少于设定数量的歌曲。</small>
          </div>
          <div v-if="playlistPreview" class="preview-section">
            <div class="preview-head"><div><strong>{{ playlistPreview.name }}</strong><p>{{ playlistPreview.description || '暂无描述' }}</p></div><span>{{ playlistPreview.selectedCount ?? playlistPreview.songs?.length ?? 0 }} 首</span></div>
            <div class="preview-meta">候选 {{ playlistPreview.candidateCount || 0 }} 首 · 可信刮削元数据 {{ playlistPreview.metadataEnrichedCount || 0 }} 首 · 上限 {{ playlistPreview.limit || generateForm.limit }} 首</div>
            <div class="preview-table">
              <table><thead><tr><th>序号</th><th>歌名</th><th>歌手</th><th class="preview-action-cell">操作</th></tr></thead><tbody><tr v-for="(song, index) in playlistPreview.songs || []" :key="song.id"><td>{{ index + 1 }}</td><td><strong>{{ song.title }}</strong></td><td>{{ song.artist }}</td><td class="preview-action-cell"><button class="icon-button small danger-icon" title="从预览中移除" @click="removePreviewSong(song.id)"><Trash2 :size="14" /></button></td></tr><tr v-if="!playlistPreview.songs?.length"><td colspan="4" class="empty compact">AI 未选出符合条件的歌曲，请调整策划要求后重试</td></tr></tbody></table>
            </div>
          </div>
        </div>
        <footer class="modal-actions">
          <button class="secondary" :disabled="busy" @click="closeGenerator">取消</button>
          <button class="primary action-button" :disabled="busy || !playlistPreview?.songIds?.length" @click="savePlaylistPreview"><CheckCircle2 :size="15" />确认保存</button>
        </footer>
      </section>
    </div>

    <div v-if="songsOpen && selectedPlaylist" class="mask" @click.self="closeSongs">
      <section class="modal songs-modal" role="dialog" aria-modal="true" aria-label="歌单歌曲管理">
        <header class="modal-head">
          <div><h2>歌曲管理</h2><p>《{{ selectedPlaylist.name }}》共 {{ selectedPlaylist.songs?.length || 0 }} 首，拖动行或使用箭头调整顺序</p></div>
          <button class="icon-button" title="关闭" :disabled="busy" @click="closeSongs"><X :size="18" /></button>
        </header>
        <div class="modal-body songs-body">
          <div class="add-song-bar">
            <label class="song-search-field"><span>搜索 KTV 曲库</span><input v-model.trim="songSearchKeyword" type="search" placeholder="输入歌名、歌手或拼音" @keyup.enter="searchPlaylistSongs" /></label>
            <button class="secondary action-button" :disabled="busy || searchingSongs || !songSearchKeyword" @click="searchPlaylistSongs"><Search :size="14" />{{ searchingSongs ? '搜索中' : '搜索' }}</button>
            <small>搜索结果可直接加入；最多 100 首，人工加入的歌曲在 AI 更新时保留。</small>
          </div>
          <div v-if="songSearchKeyword && (songSearchResults.length || songSearchDone)" class="song-search-results">
            <div v-if="!songSearchResults.length" class="search-empty">没有找到匹配的 KTV 曲库歌曲</div>
            <div v-for="song in songSearchResults" :key="song.id" class="song-search-result">
              <div><strong>{{ song.title }}</strong><small>{{ song.artist || '未知歌手' }} · #{{ song.id }}</small></div>
              <button class="secondary small-action" :disabled="busy || selectedPlaylist.songs.length >= 100 || isSongInPlaylist(song.id)" @click="addSong(song.id)">{{ isSongInPlaylist(song.id) ? '已在歌单' : '加入' }}</button>
            </div>
          </div>
          <div class="song-table-scroll">
            <table class="song-table">
              <thead><tr><th>排序</th><th>歌名</th><th>歌手</th><th>来源</th><th class="song-action-cell">操作</th></tr></thead>
              <tbody>
                <tr v-for="(song, index) in selectedPlaylist.songs" :key="song.songId" draggable="true" :class="{ dragging: draggedSongId === song.songId }" @dragstart="startDrag(song.songId)" @dragover.prevent @drop="dropSong(song.songId)" @dragend="draggedSongId = null">
                  <td><span class="sort-cell"><GripVertical :size="16" />{{ index + 1 }}</span></td><td><strong>{{ song.title }}</strong><small>#{{ song.songId }}</small></td><td>{{ song.artist || '未知歌手' }}</td><td><span class="status" :class="song.manual ? 'green' : 'blue'">{{ song.manual ? '人工加入' : 'AI 选择' }}</span></td>
                  <td class="song-action-cell"><div class="icon-actions"><button class="icon-button small" title="上移" :disabled="busy || index === 0" @click="moveSong(index, -1)"><ArrowUp :size="14" /></button><button class="icon-button small" title="下移" :disabled="busy || index === selectedPlaylist.songs.length - 1" @click="moveSong(index, 1)"><ArrowDown :size="14" /></button><button class="icon-button small danger-icon" title="移除歌曲" :disabled="busy" @click="removeSong(song.songId)"><Trash2 :size="14" /></button></div></td>
                </tr>
                <tr v-if="!selectedPlaylist.songs.length"><td colspan="5" class="empty">歌单中暂无歌曲</td></tr>
              </tbody>
            </table>
          </div>
        </div>
        <footer class="modal-actions"><button class="primary" :disabled="busy" @click="closeSongs">完成</button></footer>
      </section>
    </div>
  </AdminLayout>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog, confirmDialog } from '../../composables/useDialog'
import {
  ArrowDown, ArrowUp, Check, CheckCircle2, ChevronDown, GripVertical, ListMusic,
  Music2, Pencil, Plus, RefreshCw, Search, Settings2, Sparkles, Trash2, Upload, X
} from 'lucide-vue-next'

const PAGE_SIZE = 20
const loading = ref(false)
const busy = ref(false)
const message = ref('')
const playlists = ref([])
const selectedPlaylist = ref(null)
const aiConfigured = ref(false)
const aiConfigState = ref('loading')
const aiConfigError = ref('')
const playlistsError = ref('')
const page = ref(0)
const editorOpen = ref(false)
const generatorOpen = ref(false)
const songsOpen = ref(false)
const songSearchKeyword = ref('')
const songSearchResults = ref([])
const songSearchDone = ref(false)
const searchingSongs = ref(false)
const draggedSongId = ref(null)
const playlistInstruction = ref('')
const playlistPreview = ref(null)
const generateForm = reactive({ limit: 100 })
const playlistForm = reactive({ id: null, name: '', description: '', theme: '', publicVisible: true })
const filters = reactive({ keyword: '', source: '', visibility: '' })

const filteredPlaylists = computed(() => {
  const keyword = filters.keyword.toLowerCase()
  return playlists.value.filter(item => {
    const text = `${item.name || ''} ${item.theme || ''} ${item.description || ''}`.toLowerCase()
    if (keyword && !text.includes(keyword)) return false
    if (filters.source === 'ai' && !item.aiGenerated) return false
    if (filters.source === 'manual' && item.aiGenerated) return false
    if (filters.visibility === 'public' && !item.publicVisible) return false
    if (filters.visibility === 'hidden' && item.publicVisible) return false
    return true
  })
})
const totalPages = computed(() => Math.max(1, Math.ceil(filteredPlaylists.value.length / PAGE_SIZE)))
const pagedPlaylists = computed(() => filteredPlaylists.value.slice(page.value * PAGE_SIZE, (page.value + 1) * PAGE_SIZE))

onMounted(refreshAll)

async function refreshAll() {
  loading.value = true
  try { await Promise.allSettled([loadPlaylists(), loadAiConfig()]) } finally { loading.value = false }
}
async function loadPlaylists() {
  try {
    playlists.value = await api.adminAiPlaylists()
    playlistsError.value = ''
    if (page.value >= totalPages.value) page.value = totalPages.value - 1
  } catch (error) {
    playlistsError.value = error?.message || '网络错误，请稍后重试'
  }
}
async function loadAiConfig() {
  try {
    const config = await api.adminAiConfig()
    aiConfigured.value = !!(config.enabled && config.baseUrl && config.bulkModel)
    aiConfigError.value = ''
    aiConfigState.value = 'ready'
  } catch (error) {
    aiConfigError.value = error?.message || '网络错误，请稍后重试'
    aiConfigState.value = 'error'
  }
}
function search() { page.value = 0 }
function resetFilters() { Object.assign(filters, { keyword: '', source: '', visibility: '' }); page.value = 0 }
function go(nextPage) { if (nextPage >= 0 && nextPage < totalPages.value) page.value = nextPage }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function coverStyle(playlist) { return playlist?.coverUrl ? { backgroundImage: `url(${playlist.coverUrl})` } : {} }
function flash(value) { message.value = value; setTimeout(() => { message.value = '' }, 2500) }

function newPlaylist() {
  selectedPlaylist.value = null
  Object.assign(playlistForm, { id: null, name: '', description: '', theme: '', publicVisible: true })
  editorOpen.value = true
}
async function selectPlaylist(id) {
  selectedPlaylist.value = await api.adminAiPlaylist(id)
  Object.assign(playlistForm, {
    id: selectedPlaylist.value.id,
    name: selectedPlaylist.value.name,
    description: selectedPlaylist.value.description || '',
    theme: selectedPlaylist.value.theme || '',
    publicVisible: selectedPlaylist.value.publicVisible
  })
}
async function openEdit(item) {
  await run(async () => { await selectPlaylist(item.id); editorOpen.value = true })
}
function closeEditor() { if (!busy.value) editorOpen.value = false }
async function savePlaylist() {
  await run(async () => {
    const body = { name: playlistForm.name, description: playlistForm.description, theme: playlistForm.theme, publicVisible: playlistForm.publicVisible }
    const saved = playlistForm.id ? await api.adminAiUpdatePlaylist(playlistForm.id, body) : await api.adminAiCreatePlaylist(body)
    await loadPlaylists()
    await selectPlaylist(saved.id)
    editorOpen.value = false
    flash('歌单已保存')
  })
}
async function deletePlaylist(item) {
  if (!await confirmDialog(`歌单“${item.name}”及其中的歌曲关联将被删除。`, { title: '删除主题歌单', tone: 'warning' })) return
  await run(async () => {
    await api.adminAiDeletePlaylist(item.id)
    if (selectedPlaylist.value?.id === item.id) selectedPlaylist.value = null
    await loadPlaylists()
    flash('歌单已删除')
  })
}
async function uploadCover(event) {
  const file = event.target.files?.[0]
  if (!file) return
  await run(async () => {
    await api.adminAiUploadPlaylistCover(selectedPlaylist.value.id, file)
    await selectPlaylist(selectedPlaylist.value.id)
    await loadPlaylists()
    flash('歌单封面已更新')
  })
  event.target.value = ''
}

function openGenerator() { playlistPreview.value = null; generatorOpen.value = true }
function closeGenerator() { if (!busy.value) generatorOpen.value = false }
function normalizePlaylistLimit() { generateForm.limit = Math.max(1, Math.min(Number(generateForm.limit) || 100, 100)) }
async function previewPlaylist() {
  normalizePlaylistLimit()
  await run(async () => { playlistPreview.value = await api.adminAiPreviewPlaylist({ instruction: playlistInstruction.value, limit: generateForm.limit }) })
}
async function savePlaylistPreview() {
  await run(async () => {
    const preview = playlistPreview.value
    await api.adminAiSavePlaylistPreview({
      name: preview.name,
      description: preview.description,
      theme: 'AI 策划',
      publicVisible: true,
      songIds: preview.songIds || []
    })
    generatorOpen.value = false
    playlistPreview.value = null
    await loadPlaylists()
    flash(`AI 歌单已保存，共 ${preview.songIds.length} 首`)
  })
}
function removePreviewSong(songId) {
  if (!playlistPreview.value) return
  const key = String(songId)
  playlistPreview.value.songs = (playlistPreview.value.songs || []).filter(song => String(song.id) !== key)
  playlistPreview.value.songIds = (playlistPreview.value.songIds || []).filter(id => String(id) !== key)
  playlistPreview.value.selectedCount = playlistPreview.value.songIds.length
}

async function openSongs(item) {
  await run(async () => { await selectPlaylist(item.id); songSearchKeyword.value = ''; songSearchResults.value = []; songSearchDone.value = false; songsOpen.value = true })
}
function closeSongs() { if (!busy.value) songsOpen.value = false }
async function searchPlaylistSongs() {
  if (!songSearchKeyword.value || searchingSongs.value) return
  searchingSongs.value = true
  songSearchDone.value = false
  try {
    const result = await api.adminSongs({ keyword: songSearchKeyword.value, page: 0, size: 10 })
    songSearchResults.value = result.content || []
    songSearchDone.value = true
  } catch (error) {
    await alertDialog(error.message || '歌曲搜索失败')
  } finally { searchingSongs.value = false }
}
function isSongInPlaylist(songId) { return selectedPlaylist.value?.songs?.some(song => song.songId === songId) }
async function addSong(songId) {
  if (!songId || selectedPlaylist.value.songs.length >= 100 || isSongInPlaylist(songId)) return
  await run(async () => {
    selectedPlaylist.value = await api.adminAiAddPlaylistSong(selectedPlaylist.value.id, songId)
    await loadPlaylists()
  })
}
async function removeSong(songId) {
  await run(async () => {
    await api.adminAiRemovePlaylistSong(selectedPlaylist.value.id, songId)
    await selectPlaylist(selectedPlaylist.value.id)
    await loadPlaylists()
  })
}
function startDrag(songId) { draggedSongId.value = songId }
async function dropSong(targetSongId) {
  if (!draggedSongId.value || draggedSongId.value === targetSongId) return
  const songs = [...selectedPlaylist.value.songs]
  const from = songs.findIndex(song => song.songId === draggedSongId.value)
  const to = songs.findIndex(song => song.songId === targetSongId)
  const [moved] = songs.splice(from, 1)
  songs.splice(to, 0, moved)
  await saveOrder(songs)
  draggedSongId.value = null
}
async function moveSong(index, offset) {
  const songs = [...selectedPlaylist.value.songs]
  const target = index + offset
  if (target < 0 || target >= songs.length) return
  ;[songs[index], songs[target]] = [songs[target], songs[index]]
  await saveOrder(songs)
}
async function saveOrder(songs) {
  await run(async () => {
    selectedPlaylist.value = await api.adminAiReorderPlaylistSongs(selectedPlaylist.value.id, songs.map(song => song.songId))
    flash('歌曲顺序已保存')
  })
}
async function run(action) {
  busy.value = true
  try { await action() } catch (error) { await alertDialog(error.message || '操作失败') } finally { busy.value = false }
}
</script>

<style scoped>
.page-head{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:18px}.page-head h1{margin:0;color:#172033;font-size:22px;line-height:1.25}.page-head p{margin:6px 0 0;color:#64748b;font-size:13px}.page-actions,.toolbar-actions,.filter-actions,.row-actions,.action-button,.icon-text-button,.icon-actions{display:flex;align-items:center}.page-actions,.toolbar-actions,.filter-actions{gap:8px}.primary,.secondary{height:36px;padding:0 14px;border-radius:6px;font-size:12px;font-weight:600}.primary{display:inline-flex;align-items:center;justify-content:center;gap:6px;border:1px solid #2563eb;background:#2563eb;color:#fff}.primary:hover:not(:disabled){border-color:#1d4ed8;background:#1d4ed8}.secondary{display:inline-flex;align-items:center;justify-content:center;gap:6px;border:1px solid #cbd5e1;background:#fff;color:#475569}.secondary:hover:not(:disabled){border-color:#94a3b8;background:#f8fafc;color:#172033}.primary:disabled,.secondary:disabled,.link:disabled,.icon-button:disabled{cursor:not-allowed;opacity:.45}.spin{animation:spin .8s linear infinite}
.notice{margin-bottom:14px;padding:10px 12px;border:1px solid #bbf7d0;border-radius:6px;background:#f0fdf4;color:#166534;font-size:12px}.error-notice{display:flex;align-items:center;justify-content:space-between;gap:12px;border-color:#fecaca;background:#fef2f2;color:#b91c1c}.error-empty{color:#b91c1c}.filter-panel{display:flex;align-items:flex-end;flex-wrap:wrap;gap:10px;padding:12px 14px;margin-bottom:14px;border:1px solid #e2e8f0;border-radius:8px;background:#fff}.filter-panel>label{display:flex;flex:0 0 180px;flex-direction:column;gap:5px;color:#475569;font-size:12px}.filter-panel>label:first-child{flex-basis:280px}.filter-panel input,.filter-panel select{width:100%;height:36px;padding:0 10px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#172033;font:inherit;font-size:13px;line-height:normal;outline:0}.filter-panel select{appearance:none;padding-right:34px;cursor:pointer}.select-control{position:relative;display:block}.select-control svg{position:absolute;right:10px;top:50%;color:#64748b;pointer-events:none;transform:translateY(-50%)}.filter-panel input:focus,.filter-panel select:focus,.field input:focus,.field textarea:focus,.add-song-bar input:focus{border-color:#60a5fa;box-shadow:0 0 0 3px rgba(37,99,235,.1)}
.table-panel{overflow:hidden;border:1px solid #e2e8f0;border-radius:8px;background:#fff}.toolbar,.pager{display:flex;align-items:center;justify-content:space-between;padding:13px 16px;color:#64748b;font-size:12px}.toolbar{border-bottom:1px solid #e2e8f0}.pager{border-top:1px solid #e2e8f0}.pager>div{display:flex;gap:8px}.table-scroll{position:relative;overflow:auto}.table-empty{display:grid;min-height:220px;place-content:center;justify-items:center;gap:9px;color:#94a3b8;font-size:12px}.table-empty svg{color:#cbd5e1}table{width:100%;min-width:1050px;border-collapse:separate;border-spacing:0}th,td{padding:11px 12px;border-bottom:1px solid #eef2f7;text-align:left;white-space:nowrap}th{background:#f8fafc;color:#64748b;font-size:11px;font-weight:600}td{background:#fff;color:#334155;font-size:12px}td small{display:block;margin-top:4px;color:#94a3b8;font-size:10px}.playlist-name-cell{display:flex;align-items:center;gap:10px;min-width:230px}.playlist-name-cell>div:last-child{min-width:0}.playlist-name-cell strong,.playlist-name-cell small{display:block;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.cover-thumb{display:grid;place-items:center;width:42px;height:42px;flex:none;border:1px solid #dbeafe;border-radius:6px;background:#eff6ff center/cover no-repeat;color:#2563eb}.song-count{font-size:13px}.updated-at{color:#64748b}.status{display:inline-flex;padding:3px 8px;border-radius:4px;font-size:10px;font-weight:600}.status.green{background:#dcfce7;color:#15803d}.status.blue{background:#dbeafe;color:#1d4ed8}.status.amber{background:#fef3c7;color:#a16207}.status.neutral{background:#f1f5f9;color:#475569}.action-cell{position:sticky;right:0;z-index:2;width:220px;min-width:220px;border-left:1px solid #e2e8f0;box-shadow:-10px 0 14px -14px rgba(15,23,42,.55)}th.action-cell{z-index:3}.row-actions{gap:6px}.link{display:inline-flex;align-items:center;justify-content:center;gap:4px;height:30px;padding:0 8px;border:1px solid #dbe3ee;border-radius:6px;background:#fff;color:#2563eb;font-size:11px;font-weight:600}.link:hover:not(:disabled){border-color:#bfdbfe;background:#eff6ff}.danger-text{color:#b91c1c}.danger-text:hover:not(:disabled){border-color:#fecaca;background:#fef2f2}.empty{padding:48px;text-align:center;color:#94a3b8}.empty.compact{padding:26px}
.mask{position:fixed;inset:0;z-index:100;display:grid;place-items:center;padding:20px;background:rgba(15,23,42,.48)}.modal{display:flex;width:min(620px,calc(100vw - 32px));max-height:calc(100vh - 40px);flex-direction:column;overflow:hidden;border-radius:8px;background:#fff;box-shadow:0 20px 55px rgba(15,23,42,.22)}.generator-modal,.songs-modal{width:min(920px,calc(100vw - 32px))}.modal-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding:18px 20px;border-bottom:1px solid #e2e8f0}.modal-head h2{margin:0;color:#172033;font-size:17px}.modal-head p{margin:5px 0 0;color:#64748b;font-size:11px}.modal-body{overflow:auto;padding:18px 20px}.modal-actions{display:flex;justify-content:flex-end;gap:8px;padding:13px 20px;border-top:1px solid #e2e8f0;background:#f8fafc}.icon-button{display:grid;place-items:center;width:34px;height:34px;flex:none;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#475569}.icon-button:hover:not(:disabled){background:#f8fafc;color:#172033}.icon-button.small{width:30px;height:30px}.danger-icon{border-color:#fecaca;color:#b91c1c}.danger-icon:hover:not(:disabled){background:#fef2f2}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.field{display:flex;flex-direction:column;gap:6px;color:#475569;font-size:12px}.field.wide{grid-column:1/-1}.field input,.field textarea,.add-song-bar input{width:100%;min-height:36px;padding:8px 10px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#172033;font:inherit;font-size:12px;outline:0}.field textarea{resize:vertical;line-height:1.55}.cover-row{display:flex;align-items:center;gap:14px;margin-top:16px;padding-top:16px;border-top:1px solid #e2e8f0}.cover-preview{display:grid;place-items:center;width:72px;height:72px;flex:none;border:1px solid #dbeafe;border-radius:6px;background:#eff6ff center/cover no-repeat;color:#2563eb}.cover-row>div:last-child{display:flex;align-items:flex-start;flex-direction:column;gap:5px}.cover-row small,.check-row small{color:#94a3b8;font-size:10px}.upload-button{display:inline-flex;align-items:center;gap:5px;margin-top:3px;color:#2563eb;font-size:11px;font-weight:600;cursor:pointer}.upload-button input{display:none}.check-row{display:flex;align-items:flex-start;gap:9px;margin-top:16px;padding:12px;border:1px solid #e2e8f0;border-radius:6px;background:#f8fafc;color:#334155;font-size:12px}.check-row input{width:15px;height:15px;margin-top:2px;accent-color:#2563eb}.check-row span{display:flex;flex-direction:column;gap:3px}
.generator-controls{display:flex;align-items:flex-end;gap:10px;margin-top:12px}.limit-field{width:130px;flex:none}.generator-controls>small{align-self:center;color:#64748b;font-size:10px}.preview-section{margin-top:18px;padding-top:16px;border-top:1px solid #e2e8f0}.preview-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.preview-head strong{color:#172033;font-size:14px}.preview-head p{margin:5px 0 0;color:#64748b;font-size:11px}.preview-head>span{padding:3px 8px;border-radius:4px;background:#dbeafe;color:#1d4ed8;font-size:10px;font-weight:700}.preview-meta{margin:10px 0;color:#64748b;font-size:10px}.preview-table,.song-table-scroll{overflow:auto;border:1px solid #e2e8f0;border-radius:6px}.preview-table{max-height:280px}.preview-table table,.song-table{min-width:100%;}.preview-table th,.preview-table td,.song-table th,.song-table td{padding:9px 10px}.preview-table th:first-child{width:64px}.preview-action-cell{width:74px;text-align:center}.preview-action-cell .icon-button{margin:auto}
.songs-body{display:flex;min-height:360px;flex-direction:column;gap:14px}.add-song-bar{display:flex;align-items:flex-end;gap:9px}.add-song-bar label{display:flex;width:330px;flex-direction:column;gap:5px;color:#475569;font-size:11px}.add-song-bar>small{align-self:center;color:#64748b;font-size:10px}.small-action{height:30px;padding-inline:10px;font-size:11px}.song-search-results{overflow:hidden;border:1px solid #e2e8f0;border-radius:6px}.song-search-result{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 11px;border-bottom:1px solid #eef2f7}.song-search-result:last-child{border-bottom:0}.song-search-result strong,.song-search-result small{display:block}.song-search-result strong{color:#334155;font-size:12px}.song-search-result small{margin-top:3px;color:#94a3b8;font-size:10px}.search-empty{padding:20px;text-align:center;color:#94a3b8;font-size:11px}.song-table-scroll{max-height:480px}.song-table{min-width:720px}.song-table tr.dragging{opacity:.35}.song-table td strong{display:block;color:#334155}.sort-cell{display:flex;align-items:center;gap:6px;color:#64748b;cursor:grab}.song-action-cell{width:126px}.icon-actions{gap:5px}
@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:760px){.page-head{align-items:flex-start;flex-direction:column}.page-actions{width:100%}.page-actions>*{flex:1}.filter-panel>label:first-child{flex-basis:100%}.filter-panel>label:not(:first-child){flex:1 1 140px}.filter-actions{width:100%;justify-content:flex-end}.toolbar{align-items:flex-start;flex-direction:column;gap:10px}.toolbar-actions{width:100%;flex-wrap:wrap}.toolbar-actions button{flex:1}.action-cell{width:210px;min-width:210px}.modal{width:calc(100vw - 20px);max-height:calc(100vh - 20px)}.modal-head,.modal-body,.modal-actions{padding-left:14px;padding-right:14px}.form-grid{grid-template-columns:1fr}.field.wide{grid-column:auto}.generator-controls,.add-song-bar{align-items:stretch;flex-wrap:wrap}.generator-controls>small,.add-song-bar>small{width:100%}.add-song-bar label{width:100%}.generator-controls .primary{align-self:flex-end}.cover-row{align-items:flex-start}}
</style>
