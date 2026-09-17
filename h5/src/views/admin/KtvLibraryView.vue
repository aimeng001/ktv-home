<template>
  <AdminLayout active="ktv">
    <header class="page-head"><div><h1>KTV曲库管理</h1><p>正式可播放曲库，手机点歌和 TV 播放均从这里读取</p></div><button class="secondary" @click="showAiTaskPanel=true">AI 任务</button></header>
    <div v-if="loadError" class="notice error-notice" role="alert"><span>KTV曲库读取失败：{{ loadError }}</span><button class="text-btn" @click="load">重试</button></div>
    <div v-if="libraryMode === 'EXTERNAL_READ_ONLY'" class="readonly-notice">外部只读曲库不能在 Home KTV 中删除源歌曲；如需移除歌曲，请在 NAS 中处理后重新扫描。</div>
    <div v-else-if="libraryMode === null" class="readonly-notice">曲库模式尚未确认，删除操作已隐藏；请检查服务连接后重试。</div>
    <!-- 筛选面板 / Filter Panel -->
    <section class="filter-panel">
      <label><span>关键词</span><input v-model.trim="filters.keyword" placeholder="歌名或歌手" @keyup.enter="search" /></label>
      <label><span>版本类型</span><span class="select-control"><select v-model="filters.type" @change="search"><option value="">全部类型</option><option value="KTV_VIDEO">KTV版</option><option value="MV">MV版</option><option value="AUDIO">音频版</option><option value="unrecognized">未识别</option></select><ChevronDown :size="15" aria-hidden="true" /></span></label>
      <label><span>入库来源</span><span class="select-control"><select v-model="filters.source" @change="search"><option value="">全部来源</option><option value="COPIED">自动直拷</option><option value="TRANSCODED">转码入库</option><option value="EXTERNAL_READ_ONLY">外部只读</option><option value="UNKNOWN">历史曲库</option></select><ChevronDown :size="15" aria-hidden="true" /></span></label>
      <div class="filter-actions"><button class="secondary" @click="reset">重置</button><button class="primary" @click="search">查询</button><button class="secondary scrape-entry" @click="goScrape()"><Tags :size="15" />元数据刮削</button></div>
    </section>
    <!-- 歌曲列表表格 / Song List Table -->
    <section class="table-panel">
      <div class="toolbar"><span>共 {{ total }} 首可点歌曲</span><div class="toolbar-actions"><button class="secondary scrape-batch" :disabled="!selected.size" @click="goScrape([...selected])"><Tags :size="14" />刮削已选（{{ selected.size }}）</button><button v-if="canDelete" class="danger" :disabled="!selected.size" @click="deleteSelected">批量删除（{{ selected.size }}）</button></div></div>
      <div class="table-scroll"><table><thead><tr><th><input type="checkbox" :checked="allSelected" @change="toggleAll" /></th><th>歌名</th><th>歌手</th><th>类型</th><th>语种 / 标签</th><th>KTV 文件</th><th>来源</th><th>AudioLayout</th><th>点唱</th><th class="action-cell">操作</th></tr></thead><tbody>
        <tr v-for="song in songs" :key="song.id"><td><input type="checkbox" :checked="selected.has(song.id)" @change="toggle(song.id)" /></td><td><strong>{{ song.title }}</strong></td><td>{{ song.artist }}</td><td><span class="status" :class="typeClass(song.mediaType)">{{ typeText(song.mediaType) }}</span></td><td>{{ song.language || '—' }}<small>{{ (song.tags || []).join(' / ') || '无标签' }}</small></td><td class="path">{{ song.filePath || '—' }}</td><td>{{ sourceText(song.importSource) }}</td><td><span class="status neutral">{{ audioLayoutText(song.audioLayout) }}</span><small>{{ audioLayoutDetail(song.audioLayout) }}</small></td><td>{{ song.playCount || 0 }}</td><td class="action-cell"><div class="row-actions"><button class="link edit-link" title="编辑歌曲基础信息" @click="openSongEditor(song)"><Pencil :size="14" />编辑</button><AudioLayoutEditor :song="song" @updated="updateSongAudioLayout(song, $event)" /><button class="link playlist-link" title="加入已有歌单" @click="openPlaylistPicker(song)"><ListPlus :size="14" />歌单</button><button class="link match-link" title="搜索、筛选并审核平台元数据" @click="goScrape([song.id],true)"><Tags :size="14" />元数据刮削</button><button v-if="canDelete" class="link danger-text" title="删除歌曲" @click="deleteOne(song)"><Trash2 :size="14" />删除</button></div></td></tr>
        <tr v-if="!songs.length"><td colspan="10" class="empty">暂无符合条件的 KTV 曲库歌曲</td></tr>
      </tbody></table></div>
      <div class="pager"><span>第 {{ page + 1 }} / {{ totalPages || 1 }} 页</span><div><button class="secondary" :disabled="page===0" @click="go(page-1)">上一页</button><button class="secondary" :disabled="page>=totalPages-1" @click="go(page+1)">下一页</button></div></div>
    </section>
    <div v-if="songEditorOpen" class="mask" @click.self="closeSongEditor"><div class="modal song-editor-modal">
      <div class="match-head"><div><h2>编辑歌曲信息</h2><p>《{{ editingSong?.title }}》· {{ editingSong?.artist }}</p></div><button class="icon-button" title="关闭" :disabled="songSaving" @click="closeSongEditor"><X :size="17" /></button></div>
      <form class="song-editor-form" @submit.prevent="saveSongEdit">
        <label><span>歌名 <em class="req">*</em></span><input v-model.trim="editForm.title" required maxlength="100" placeholder="歌曲名称" /></label>
        <label><span>歌手 <em class="req">*</em></span><input v-model.trim="editForm.artist" required maxlength="100" placeholder="演唱者" /></label>
        <label><span>语种</span><input v-model.trim="editForm.language" maxlength="20" placeholder="如：国语、粤语、英语" /></label>
        <label><span>标签 / 曲风</span><input v-model.trim="editForm.tags" placeholder="多个标签用逗号分隔，如：流行, 经典" /></label>
        <div class="modal-actions"><button type="button" class="secondary" :disabled="songSaving" @click="closeSongEditor">取消</button><button type="submit" class="primary" :disabled="songSaving || !editForm.title || !editForm.artist">{{ songSaving ? '保存中…' : '保存修改' }}</button></div>
      </form>
    </div></div>
    <div v-if="playlistPickerOpen" class="mask" @click.self="closePlaylistPicker"><div class="modal playlist-picker-modal">
      <div class="match-head"><div><h2>加入歌单</h2><p>《{{ playlistPickerSong?.title }}》· {{ playlistPickerSong?.artist || '未知歌手' }}</p></div><button class="icon-button" title="关闭" @click="closePlaylistPicker"><X :size="17" /></button></div>
      <div v-if="playlistLoading" class="match-loading">正在加载已有歌单…</div>
      <div v-else class="playlist-picker-list">
        <button v-for="playlist in playlistOptions" :key="playlist.id" class="playlist-picker-item" :disabled="playlistAddingId === playlist.id" @click="addSongToPlaylist(playlist)"><span><strong>{{ playlist.name }}</strong><small>{{ playlist.theme || '未设置主题' }} · {{ playlist.songCount || 0 }} / 100 首</small></span><span class="picker-action">{{ playlistAddingId === playlist.id ? '加入中…' : '加入' }}</span></button>
        <div v-if="!playlistOptions.length" class="match-empty">暂无已有歌单，请先在主题歌单页面创建</div>
      </div>
    </div></div>
    <AiTaskPanel v-if="showAiTaskPanel" :selected-ids="Array.from(selected)" @close="showAiTaskPanel=false" @applied="load" />
  </AdminLayout>
</template>

<script setup>
/**
 * KTV曲库管理页面 — 管理正式可播放曲库，支持筛选、编辑、删除歌曲。
 *
 * KTV Library Management Page — manages the official playable song library,
 * supports filtering, editing, and deleting songs.
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronDown, ListPlus, Pencil, Tags, Trash2, X } from 'lucide-vue-next'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import AudioLayoutEditor from './AudioLayoutEditor.vue'
import AiTaskPanel from '../../components/admin/AiTaskPanel.vue'
import { audioLayoutLabel } from './audioLayout'
import { canDeleteSongs } from './libraryMode'
import { sourceLabel } from './librarySource'
import { buildScrapeRoute } from './ktvLibraryScrapeRoute'
import { alertDialog, confirmDialog } from '../../composables/useDialog'
import { buildSongEditPayload } from './editSongState'
import { createLatestRequest } from '../latestRequest'
/** 歌曲列表、总数、当前页、总页数、已选集合 / Song list, total, page, total pages, selected set */
const songs=ref([]),total=ref(0),page=ref(0),totalPages=ref(1),selected=ref(new Set())
const router=useRouter()
const playlistPickerOpen=ref(false),playlistPickerSong=ref(null),playlistOptions=ref([]),playlistLoading=ref(false),playlistAddingId=ref(null)
const showAiTaskPanel=ref(true)
const songEditorOpen=ref(false),editingSong=ref(null),songSaving=ref(false)
const editForm=reactive({title:'',artist:'',language:'',tags:''})
const libraryMode=ref(null),loadError=ref('')
const listRequests=createLatestRequest()
const canDelete=computed(()=>canDeleteSongs(libraryMode.value))
/** 筛选条件 / Filter criteria */
const filters=reactive({keyword:'',type:'',source:''})
/** 是否全选 / Whether all items are selected */
const allSelected=computed(()=>songs.value.length>0&&songs.value.every(s=>selected.value.has(s.id)))
/**
 * 加载歌曲列表，根据筛选条件和分页查询，并清理已删除的选中项。
 *
 * Loads the song list based on filter criteria and pagination,
 * and cleans up selected items that no longer exist.
 */
async function load(){
  const request=listRequests.begin()
  loadError.value=''
  libraryMode.value=null
  try{
    const mode=await api.adminSourceLibrary({page:0,size:1},{signal:request.signal})
    if(!listRequests.isCurrent(request.id))return
    libraryMode.value=mode?.libraryMode||null
  }catch(e){
    if(!listRequests.isCurrent(request.id))return
    libraryMode.value=null
  }
  try{
    const r=await api.adminSongs({...filters,page:page.value,size:20},{signal:request.signal})
    if(!listRequests.isCurrent(request.id))return
    songs.value=r.content||[];total.value=r.total||0;totalPages.value=r.totalPages||1;selected.value=new Set([...selected.value].filter(id=>songs.value.some(s=>s.id===id)))
  }catch(e){
    if(listRequests.isCurrent(request.id) && e.name!=='AbortError')loadError.value=e.message||'曲库加载失败'
  }
}
onBeforeUnmount(()=>listRequests.cancel())
/** 搜索：重置到第一页并加载 / Search: reset to first page and load */
function search(){page.value=0;load()}
/** 重置筛选条件并搜索 / Reset filter criteria and search */
function reset(){Object.assign(filters,{keyword:'',type:'',source:''});search()}
function goScrape(ids=[],review=false){router.push(buildScrapeRoute(ids,review))}
function openSongEditor(song){
  editingSong.value=song
  editForm.title=song.title||''
  editForm.artist=song.artist||''
  editForm.language=song.language||''
  editForm.tags=(song.tags||[]).join(', ')
  songEditorOpen.value=true
}
function closeSongEditor(){
  if(!songSaving.value)songEditorOpen.value=false
}
async function saveSongEdit(){
  if(!editingSong.value||songSaving.value)return
  songSaving.value=true
  try{
    const payload=buildSongEditPayload(editForm)
    const updated=await api.adminEditSong(editingSong.value.id,payload)
    if(updated){
      Object.assign(editingSong.value,updated)
    }
    closeSongEditor()
  }catch(err){
    await alertDialog(err.message||'保存歌曲失败')
  }finally{
    songSaving.value=false
  }
}
async function openPlaylistPicker(song){playlistPickerSong.value=song;playlistPickerOpen.value=true;playlistLoading.value=true;try{playlistOptions.value=await api.adminAiPlaylists()}catch(e){await alertDialog(e.message||'歌单加载失败')}finally{playlistLoading.value=false}}
function closePlaylistPicker(){if(!playlistAddingId.value)playlistPickerOpen.value=false}
async function addSongToPlaylist(playlist){if(!playlistPickerSong.value)return;playlistAddingId.value=playlist.id;try{const result=await api.adminAiAddPlaylistSong(playlist.id,playlistPickerSong.value.id);playlist.songCount=result?.songs?.length??result?.songCount??((playlist.songCount||0)+1);await alertDialog(`《${playlistPickerSong.value.title}》已加入歌单“${playlist.name}”`);playlistPickerOpen.value=false}catch(e){await alertDialog(e.message||'加入歌单失败')}finally{playlistAddingId.value=null}}
/** 跳转到指定页 / Go to a specific page */
function go(p){if(p>=0&&p<totalPages.value){page.value=p;load()}}
/** 切换单首歌曲的选中状态 / Toggle selection of a single song */
function toggle(id){const n=new Set(selected.value);n.has(id)?n.delete(id):n.add(id);selected.value=n}
/** 全选或取消全选当前页的所有歌曲 / Select or deselect all songs on the current page */
function toggleAll(e){if(e.target.checked)songs.value.forEach(s=>selected.value.add(s.id));else songs.value.forEach(s=>selected.value.delete(s.id));selected.value=new Set(selected.value)}
/**
 * 删除单首歌曲，二次确认后执行，包含实际文件删除。
 *
 * Deletes a single song after confirmation, including the actual file.
 * @param {Object} song - 歌曲对象 / Song object
 */
async function deleteOne(song){if(!canDelete.value){await alertDialog('当前曲库不是已确认的 Managed 模式，已禁止删除。');return}if(!await confirmDialog(`《${song.title}》及 /music 中的实际文件将被删除。`,{title:'删除 KTV 歌曲',tone:'warning'}))return;try{await api.adminDeleteSong(song.id);await load()}catch(e){await alertDialog(e.message||'删除失败')}}
/**
 * 批量删除选中的歌曲，确认后删除歌曲及文件。
 *
 * Batch deletes selected songs after confirmation, including actual files.
 */
async function deleteSelected(){if(!canDelete.value){await alertDialog('当前曲库不是已确认的 Managed 模式，已禁止删除。');return}if(!await confirmDialog(`将删除 ${selected.value.size} 首歌曲及其 /music 中的实际文件。`,{title:'批量删除 KTV 歌曲',tone:'warning'}))return;try{await api.adminDeleteSongs([...selected.value]);selected.value=new Set();await load()}catch(e){await alertDialog(e.message||'批量删除失败')}}
/** 媒体类型文本映射 / Media type text mapping */
function typeText(v){return{KTV_VIDEO:'KTV版',MV:'MV版',AUDIO:'音频版'}[v]||v}
/** 媒体类型样式类名 / Media type CSS class */
function typeClass(v){return v==='KTV_VIDEO'?'green':v==='MV'?'blue':'neutral'}
/** 导入来源文本映射 / Import source text mapping */
function sourceText(v){return sourceLabel(v)}
function audioLayoutText(value){return audioLayoutLabel(value)}
function audioLayoutDetail(value){
  if(value?.layout==='DUAL_TRACK') return `Original Track ${value.originalTrackIndex ?? '—'} / Accompaniment Track ${value.accompanimentTrackIndex ?? '—'}`
  if(value?.layout==='DUAL_CHANNEL') return `${value.originalChannel || 'LEFT'} / ${value.accompanimentChannel || 'RIGHT'}`
  return '无原唱/伴唱切换'
}
function updateSongAudioLayout(song, value){song.audioLayout=value}
onMounted(load)
</script>

<style scoped>
.page-head{margin-bottom:18px}.page-head h1{font-size:22px}.page-head p{color:#64748b;font-size:13px;margin-top:6px}.readonly-notice{margin-bottom:14px;padding:11px 14px;border:1px solid #fed7aa;border-radius:8px;background:#fff7ed;color:#9a3412;font-size:12px;line-height:1.6}
.primary,.secondary,.danger{height:34px;padding:0 14px;border-radius:6px;font-size:13px}.primary{background:#2563eb;color:#fff}.secondary{display:inline-flex;align-items:center;gap:6px;background:#fff;border:1px solid #cbd5e1;color:#334155}.danger{background:#fff;border:1px solid #fecaca;color:#b91c1c}.primary:disabled,.secondary:disabled,.danger:disabled{opacity:.45;cursor:not-allowed}
.filter-panel{display:flex;align-items:flex-end;flex-wrap:wrap;gap:10px;padding:12px 14px;background:#fff;border:1px solid #e2e8f0;border-radius:8px;margin-bottom:14px}.filter-panel label{display:flex;flex:0 0 180px;flex-direction:column;gap:5px;color:#475569;font-size:12px}.filter-panel label:first-child{flex-basis:280px}.filter-panel input,.filter-panel select{width:100%;height:36px;border:1px solid #cbd5e1;border-radius:6px;padding:0 10px;background:#fff;color:#172033;font:inherit;font-size:13px;line-height:normal;box-shadow:0 1px 2px rgba(15,23,42,.03)}.filter-panel select{appearance:none;padding-right:34px;cursor:pointer}.select-control{position:relative;display:block}.select-control svg{position:absolute;right:10px;top:50%;color:#64748b;pointer-events:none;transform:translateY(-50%)}.filter-panel input:focus,.filter-panel select:focus{border-color:#60a5fa;box-shadow:0 0 0 3px rgba(37,99,235,.1);outline:0}.filter-actions{display:flex;align-items:flex-end;gap:8px}.filter-actions button{height:36px}
.table-panel{background:#fff;border:1px solid #e2e8f0;border-radius:8px}.toolbar,.pager{display:flex;align-items:center;justify-content:space-between;padding:13px 16px;color:#64748b;font-size:12px}.toolbar{border-bottom:1px solid #e2e8f0}.toolbar-actions{display:flex;align-items:center;gap:8px}.scrape-batch{color:#0f766e;border-color:#99f6e4;background:#f0fdfa}.pager{border-top:1px solid #e2e8f0}.pager div{display:flex;gap:8px}.table-scroll{position:relative;overflow:auto}table{width:100%;border-collapse:separate;border-spacing:0;min-width:1200px;font-size:12px}th{padding:11px 10px;text-align:left;background:#f8fafc;color:#64748b;border-bottom:1px solid #e2e8f0}td{padding:12px 10px;border-bottom:1px solid #eef2f7;color:#334155;background:#fff}td small{display:block;color:#94a3b8;margin-top:4px}.path{max-width:260px;word-break:break-all;color:#64748b}.action-cell{position:sticky;right:0;z-index:2;width:340px;min-width:340px;border-left:1px solid #e2e8f0;box-shadow:-10px 0 14px -14px rgba(15,23,42,.55)}th.action-cell{z-index:3}.status{display:inline-flex;padding:3px 8px;border-radius:999px;font-weight:600}.green{background:#dcfce7;color:#166534}.blue{background:#dbeafe;color:#1d4ed8}.neutral{background:#f1f5f9;color:#475569}.row-actions{display:flex;align-items:center;gap:6px}.link{display:inline-flex;align-items:center;justify-content:center;gap:4px;height:30px;padding:0 9px;border:1px solid #dbe3ee;border-radius:6px;background:#fff;color:#2563eb;font-size:11px;font-weight:600;white-space:nowrap}.link:hover:not(:disabled){border-color:#bfdbfe;background:#eff6ff}.link:disabled{opacity:.5}.edit-link{color:#0284c7}.edit-link:hover:not(:disabled){border-color:#bae6fd;background:#f0f9ff}.playlist-link{color:#7c3aed}.playlist-link:hover:not(:disabled){border-color:#ddd6fe;background:#f5f3ff}.match-link{color:#0f766e}.match-link:hover:not(:disabled){border-color:#99f6e4;background:#f0fdfa}.danger-text{color:#b91c1c}.danger-text:hover:not(:disabled){border-color:#fecaca;background:#fef2f2}.empty{text-align:center;padding:36px;color:#94a3b8}
.mask{position:fixed;inset:0;background:rgba(15,23,42,.45);display:grid;place-items:center;z-index:100}.modal{width:min(440px,calc(100vw - 32px));background:#fff;border-radius:8px;padding:22px;box-shadow:0 18px 50px rgba(15,23,42,.18)}.modal h2{font-size:17px;margin-bottom:18px}.modal>label,.song-editor-form label{display:flex;flex-direction:column;gap:6px;margin-bottom:13px;color:#475569;font-size:12px}.modal input,.modal select,.modal textarea{border:1px solid #cbd5e1;border-radius:6px;padding:0 10px;background:#fff;color:#172033}.modal input,.modal select{height:36px}.modal textarea{padding:9px 10px;resize:vertical}.modal-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:18px}.req{color:#e11d48;font-style:normal}
.playlist-picker-modal,.song-editor-modal{width:min(560px,calc(100vw - 32px));max-height:calc(100vh - 40px);overflow:auto}.playlist-picker-list{margin-top:14px;border:1px solid #e2e8f0;border-radius:7px;overflow:hidden}.playlist-picker-item{display:flex;align-items:center;justify-content:space-between;gap:14px;width:100%;padding:12px 14px;text-align:left;border-bottom:1px solid #e2e8f0;background:#fff}.playlist-picker-item:last-child{border-bottom:0}.playlist-picker-item:hover:not(:disabled){background:#f8fafc}.playlist-picker-item:disabled{opacity:.55}.playlist-picker-item strong,.playlist-picker-item small{display:block}.playlist-picker-item strong{color:#1e293b;font-size:12px}.playlist-picker-item small{margin-top:4px;color:#94a3b8;font-size:10px}.picker-action{color:#2563eb;font-size:11px;font-weight:700}
@media(max-width:700px){.filter-panel label:first-child{flex-basis:100%}.filter-panel label:not(:first-child){flex:1 1 140px}.filter-actions{width:100%;justify-content:flex-end}.toolbar{align-items:flex-start;flex-direction:column}.toolbar-actions{width:100%;flex-wrap:wrap}.toolbar-actions button{flex:1}.action-cell{width:340px;min-width:340px}.link{margin-right:6px}.playlist-picker-modal,.song-editor-modal{padding:16px}}
</style>
