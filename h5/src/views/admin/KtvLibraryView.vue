<template>
  <AdminLayout active="ktv">
    <header class="page-head"><div><h1>KTV曲库管理</h1><p>正式可播放曲库，手机点歌和 TV 播放均从这里读取</p></div></header>
    <!-- 筛选面板 / Filter Panel -->
    <section class="filter-panel">
      <label><span>关键词</span><input v-model.trim="filters.keyword" placeholder="歌名或歌手" @keyup.enter="search" /></label>
      <label><span>版本类型</span><span class="select-control"><select v-model="filters.type" @change="search"><option value="">全部类型</option><option value="KTV_VIDEO">KTV版</option><option value="MV">MV版</option><option value="AUDIO">音频版</option><option value="unrecognized">未识别</option></select><ChevronDown :size="15" aria-hidden="true" /></span></label>
      <label><span>入库来源</span><span class="select-control"><select v-model="filters.source" @change="search"><option value="">全部来源</option><option value="COPIED">自动直拷</option><option value="TRANSCODED">转码入库</option><option value="UNKNOWN">历史曲库</option></select><ChevronDown :size="15" aria-hidden="true" /></span></label>
      <div class="filter-actions"><button class="secondary" @click="reset">重置</button><button class="primary" @click="search">查询</button><button class="secondary scrape-entry" @click="goScrape()"><Tags :size="15" />元数据刮削</button></div>
    </section>
    <!-- 歌曲列表表格 / Song List Table -->
    <section class="table-panel">
      <div class="toolbar"><span>共 {{ total }} 首可点歌曲</span><div class="toolbar-actions"><button class="secondary scrape-batch" :disabled="!selected.size" @click="goScrape([...selected])"><Tags :size="14" />刮削已选（{{ selected.size }}）</button><button class="danger" :disabled="!selected.size" @click="deleteSelected">批量删除（{{ selected.size }}）</button></div></div>
      <div class="table-scroll"><table><thead><tr><th><input type="checkbox" :checked="allSelected" @change="toggleAll" /></th><th>歌名</th><th>歌手</th><th>类型</th><th>语种 / 标签</th><th>KTV 文件</th><th>来源</th><th>AudioLayout</th><th>点唱</th><th class="action-cell">操作</th></tr></thead><tbody>
        <tr v-for="song in songs" :key="song.id"><td><input type="checkbox" :checked="selected.has(song.id)" @change="toggle(song.id)" /></td><td><strong>{{ song.title }}</strong></td><td>{{ song.artist }}</td><td><span class="status" :class="typeClass(song.mediaType)">{{ typeText(song.mediaType) }}</span></td><td>{{ song.language || '—' }}<small>{{ (song.tags || []).join(' / ') || '无标签' }}</small></td><td class="path">{{ song.filePath || '—' }}</td><td>{{ sourceText(song.importSource) }}</td><td><span class="status neutral">{{ audioLayoutText(song.audioLayout) }}</span><small>{{ audioLayoutDetail(song.audioLayout) }}</small></td><td>{{ song.playCount || 0 }}</td><td class="action-cell"><div class="row-actions"><AudioLayoutEditor :song="song" @updated="updateSongAudioLayout(song, $event)" /><button class="link playlist-link" title="加入已有歌单" @click="openPlaylistPicker(song)"><ListPlus :size="14" />歌单</button><button class="link match-link" title="搜索、筛选并审核平台元数据" @click="goScrape([song.id],true)"><Tags :size="14" />元数据刮削</button><button class="link danger-text" title="删除歌曲" @click="deleteOne(song)"><Trash2 :size="14" />删除</button></div></td></tr>
        <tr v-if="!songs.length"><td colspan="10" class="empty">暂无符合条件的 KTV 曲库歌曲</td></tr>
      </tbody></table></div>
      <div class="pager"><span>第 {{ page + 1 }} / {{ totalPages || 1 }} 页</span><div><button class="secondary" :disabled="page===0" @click="go(page-1)">上一页</button><button class="secondary" :disabled="page>=totalPages-1" @click="go(page+1)">下一页</button></div></div>
    </section>
    <div v-if="scrapeOpen" class="mask" @click.self="closeScrape"><div class="modal scrape-modal">
      <div class="match-head"><div><h2>批量刮削元数据</h2><p>仅勾选匹配可靠的建议；应用前不会修改 KTV 曲库</p></div><button class="icon-button" title="关闭" :disabled="scrapeApplying" @click="closeScrape"><X :size="17" /></button></div>
      <div class="scrape-summary"><span>已处理 {{ scrapeResults.length }} 首</span><span>匹配成功 {{ scrapeResults.filter(item => item.matches.length).length }} 首</span><span>待应用 {{ scrapeApplyIds.size }} 首</span></div>
      <div class="scrape-list">
        <div v-for="item in scrapeResults" :key="item.songId" class="scrape-row" :class="{failed:!bestMatch(item)}">
          <input type="checkbox" :checked="scrapeApplyIds.has(item.songId)" :disabled="!bestMatch(item)" @change="toggleScrapeApply(item.songId)" />
          <div class="scrape-current"><strong>{{ item.title }}</strong><span>{{ item.artist || '未知歌手' }}</span></div>
          <template v-if="bestMatch(item)">
            <span class="scrape-arrow">→</span>
            <div class="scrape-suggestion"><strong>{{ bestMatch(item).track.title }}</strong><span>{{ bestMatch(item).track.artists.join(' / ') || '未知歌手' }}<small>{{ bestMatch(item).track.album || '无专辑' }}</small></span></div>
            <span class="match-score" :class="{good:bestMatch(item).score>=.9}">{{ Math.round(bestMatch(item).score*100) }}%</span>
            <button class="link" @click="reviewScrape(item)">查看候选</button>
          </template>
          <span v-else class="scrape-error">{{ item.error || '未找到匹配候选' }}</span>
        </div>
      </div>
      <div class="modal-actions"><button class="secondary" :disabled="scrapeApplying" @click="closeScrape">取消</button><button class="primary match-apply" :disabled="!scrapeApplyIds.size || scrapeApplying" @click="applyScraped"><Check :size="15" />{{ scrapeApplying ? '应用中…' : `应用 ${scrapeApplyIds.size} 首建议` }}</button></div>
    </div></div>
    <div v-if="matchingSong" class="mask" @click.self="closeMatches"><div class="modal match-modal">
      <div class="match-head"><div><h2>元数据刮削建议</h2><p>《{{ matchingSong.title }}》· {{ matchingSong.artist }}</p></div><button class="icon-button" title="重新刮削" :disabled="matchLoading" @click="loadMatches(true)"><RefreshCw :size="17" /></button></div>
      <div v-if="matchLoading" class="match-loading">正在搜索已启用平台…</div>
      <template v-else>
        <div class="match-layout">
          <div class="candidate-list">
            <button v-for="item in matches" :key="item.track.provider+item.track.externalId" :class="{active:selectedMatch===item}" @click="selectMatch(item)">
              <span class="provider-tag" :class="item.track.provider.toLowerCase()">{{ providerText(item.track.provider) }}</span>
              <strong>{{ item.track.title }}</strong><span>{{ item.track.artists.join(' / ') || '未知歌手' }}</span><small>{{ item.track.album || '无专辑' }} · 匹配度 {{ Math.round(item.score*100) }}%</small>
            </button>
            <div v-if="!matches.length" class="match-empty">没有找到合适的外部候选</div>
          </div>
          <div v-if="selectedMatch" class="comparison">
            <div class="compare-head"><span>字段</span><span>当前值</span><span>外部建议</span></div>
            <label v-for="field in metadataFields" :key="field.key" class="compare-row" :class="{locked:isLocked(field.key)}">
              <span><input type="checkbox" :checked="applyFields.includes(field.key)" :disabled="isLocked(field.key) || !externalValue(field.key)" @change="toggleApplyField(field.key)" />{{ field.label }}<small v-if="isLocked(field.key)">已锁定</small></span>
              <span>{{ currentValue(field.key) || '—' }}</span><strong>{{ externalValue(field.key) || '—' }}</strong>
            </label>
            <div v-if="selectedMatch.track.coverUrl" class="cover-preview"><img :src="selectedMatch.track.coverUrl" alt="" /><span>封面将在确认后下载到本地资源目录</span></div>
          </div>
          <div v-else class="comparison-placeholder">选择左侧候选查看字段差异</div>
        </div>
        <div class="modal-actions"><button class="secondary" @click="closeMatches">取消</button><button class="primary match-apply" :disabled="!selectedMatch || applyingMatch || !applyFields.length" @click="applyMatch"><Check :size="15" />{{ applyingMatch ? '应用中' : '确认应用' }}</button></div>
      </template>
    </div></div>
    <div v-if="playlistPickerOpen" class="mask" @click.self="closePlaylistPicker"><div class="modal playlist-picker-modal">
      <div class="match-head"><div><h2>加入歌单</h2><p>《{{ playlistPickerSong?.title }}》· {{ playlistPickerSong?.artist || '未知歌手' }}</p></div><button class="icon-button" title="关闭" @click="closePlaylistPicker"><X :size="17" /></button></div>
      <div v-if="playlistLoading" class="match-loading">正在加载已有歌单…</div>
      <div v-else class="playlist-picker-list">
        <button v-for="playlist in playlistOptions" :key="playlist.id" class="playlist-picker-item" :disabled="playlistAddingId === playlist.id" @click="addSongToPlaylist(playlist)"><span><strong>{{ playlist.name }}</strong><small>{{ playlist.theme || '未设置主题' }} · {{ playlist.songCount || 0 }} / 100 首</small></span><span class="picker-action">{{ playlistAddingId === playlist.id ? '加入中…' : '加入' }}</span></button>
        <div v-if="!playlistOptions.length" class="match-empty">暂无已有歌单，请先在主题歌单页面创建</div>
      </div>
    </div></div>
  </AdminLayout>
</template>

<script setup>
/**
 * KTV曲库管理页面 — 管理正式可播放曲库，支持筛选、编辑、删除歌曲。
 *
 * KTV Library Management Page — manages the official playable song library,
 * supports filtering, editing, and deleting songs.
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Check, ChevronDown, ListPlus, RefreshCw, Tags, Trash2, X } from 'lucide-vue-next'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import AudioLayoutEditor from './AudioLayoutEditor.vue'
import { audioLayoutLabel } from './audioLayout'
import { alertDialog, confirmDialog } from '../../composables/useDialog'
/** 歌曲列表、总数、当前页、总页数、已选集合 / Song list, total, page, total pages, selected set */
const songs=ref([]),total=ref(0),page=ref(0),totalPages=ref(1),selected=ref(new Set())
const router=useRouter()
const matchingSong=ref(null),matches=ref([]),selectedMatch=ref(null),matchLoading=ref(false),applyingMatch=ref(false),applyFields=ref([])
const scrapeOpen=ref(false),scrapeLoading=ref(false),scrapeApplying=ref(false),scrapeResults=ref([]),scrapeApplyIds=ref(new Set())
const playlistPickerOpen=ref(false),playlistPickerSong=ref(null),playlistOptions=ref([]),playlistLoading=ref(false),playlistAddingId=ref(null)
const metadataFields=[{key:'title',label:'歌名'},{key:'artist',label:'歌手'},{key:'album',label:'专辑'},{key:'releaseDate',label:'发行时间'},{key:'aliases',label:'别名'},{key:'cover',label:'封面'}]
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
async function load(){const r=await api.adminSongs({...filters,page:page.value,size:20});songs.value=r.content||[];total.value=r.total||0;totalPages.value=r.totalPages||1;selected.value=new Set([...selected.value].filter(id=>songs.value.some(s=>s.id===id)))}
/** 搜索：重置到第一页并加载 / Search: reset to first page and load */
function search(){page.value=0;load()}
/** 重置筛选条件并搜索 / Reset filter criteria and search */
function reset(){Object.assign(filters,{keyword:'',type:'',source:''});search()}
function goScrape(ids=[],review=false){router.push({name:'admin-metadata-scrape',query:ids.length?{songIds:ids.join(','),...(review?{review:'1'}:{})}:undefined})}
async function openPlaylistPicker(song){playlistPickerSong.value=song;playlistPickerOpen.value=true;playlistLoading.value=true;try{playlistOptions.value=await api.adminAiPlaylists()}catch(e){await alertDialog(e.message||'歌单加载失败')}finally{playlistLoading.value=false}}
function closePlaylistPicker(){if(!playlistAddingId.value)playlistPickerOpen.value=false}
async function addSongToPlaylist(playlist){if(!playlistPickerSong.value)return;playlistAddingId.value=playlist.id;try{const result=await api.adminAiAddPlaylistSong(playlist.id,playlistPickerSong.value.id);playlist.songCount=result?.songs?.length??result?.songCount??((playlist.songCount||0)+1);await alertDialog(`《${playlistPickerSong.value.title}》已加入歌单“${playlist.name}”`);playlistPickerOpen.value=false}catch(e){await alertDialog(e.message||'加入歌单失败')}finally{playlistAddingId.value=null}}
/** 跳转到指定页 / Go to a specific page */
function go(p){if(p>=0&&p<totalPages.value){page.value=p;load()}}
/** 切换单首歌曲的选中状态 / Toggle selection of a single song */
function toggle(id){const n=new Set(selected.value);n.has(id)?n.delete(id):n.add(id);selected.value=n}
/** 全选/取消全选 / Select all / deselect all */
function toggleAll(){selected.value=allSelected.value?new Set():new Set(songs.value.map(s=>s.id))}
async function openMatches(song){matchingSong.value=song;matches.value=[];selectedMatch.value=null;applyFields.value=[];await loadMatches(false)}
function closeMatches(){if(applyingMatch.value)return;matchingSong.value=null;matches.value=[];selectedMatch.value=null}
async function loadMatches(refresh){if(!matchingSong.value)return;matchLoading.value=true;try{const result=await api.adminSongExternalMatches(matchingSong.value.id,refresh);matches.value=result.matches||[];selectMatch(matches.value[0]||null)}catch(e){if(e.code==='MUSIC_SOURCES_NOT_CONFIGURED'){closeMatches();await alertDialog('请先在系统设置中启用音乐元数据平台。');await router.push({name:'admin-settings',query:{section:'metadata'}})}else await alertDialog(e.message||'外部元数据搜索失败')}finally{matchLoading.value=false}}
function selectMatch(item){selectedMatch.value=item;applyFields.value=metadataFields.map(field=>field.key).filter(key=>!isLocked(key)&&externalValue(key))}
function isLocked(key){return (matchingSong.value?.metadataLocks||[]).includes(key)}
function externalValue(key){const track=selectedMatch.value?.track;if(!track)return '';return{title:track.title,artist:(track.artists||[]).join(' / '),album:track.album,releaseDate:track.releaseDate,aliases:(track.aliases||[]).join(' / '),cover:track.coverUrl?'可用':''}[key]||''}
function currentValue(key){const song=matchingSong.value;if(!song)return '';return{title:song.title,artist:song.artist,album:song.album,releaseDate:song.releaseDate,aliases:(song.aliases||[]).join(' / '),cover:song.coverPath?'已配置':''}[key]||''}
function toggleApplyField(key){applyFields.value=applyFields.value.includes(key)?applyFields.value.filter(item=>item!==key):[...applyFields.value,key]}
function providerText(value){return{NETEASE:'网易云',QQ:'QQ 音乐',KUGOU:'酷狗'}[value]||value}
async function applyMatch(){if(!selectedMatch.value||!matchingSong.value)return;applyingMatch.value=true;try{const track=selectedMatch.value.track;await api.adminApplyExternalMatch(matchingSong.value.id,track.provider,track.externalId,applyFields.value);matchingSong.value=null;matches.value=[];selectedMatch.value=null;await load()}catch(e){await alertDialog(e.message||'应用外部元数据失败')}finally{applyingMatch.value=false}}
function bestMatch(item){return item.matches?.[0]||null}
function closeScrape(){if(scrapeApplying.value)return;scrapeOpen.value=false}
function toggleScrapeApply(songId){const next=new Set(scrapeApplyIds.value);next.has(songId)?next.delete(songId):next.add(songId);scrapeApplyIds.value=next}
async function scrapeSelected(){
  if(!selected.value.size||scrapeLoading.value)return
  scrapeLoading.value=true
  try{
    const result=await api.adminBatchSongExternalMatches([...selected.value])
    scrapeResults.value=result.songs||[]
    scrapeApplyIds.value=new Set(scrapeResults.value.filter(item=>bestMatch(item)?.score>=.9).map(item=>item.songId))
    scrapeOpen.value=true
  }catch(e){
    if(e.code==='MUSIC_SOURCES_NOT_CONFIGURED'){
      await alertDialog('请先在系统设置中启用元数据刮削平台。')
      await router.push({name:'admin-settings',query:{section:'metadata'}})
    }else await alertDialog(e.message||'批量元数据刮削失败')
  }finally{scrapeLoading.value=false}
}
function reviewScrape(item){
  const song=songs.value.find(value=>value.id===item.songId)
  if(!song)return
  scrapeOpen.value=false
  matchingSong.value=song
  matches.value=item.matches||[]
  selectMatch(matches.value[0]||null)
}
async function applyScraped(){
  const chosen=scrapeResults.value.filter(item=>scrapeApplyIds.value.has(item.songId)&&bestMatch(item))
  if(!chosen.length||!await confirmDialog(`将把 ${chosen.length} 首高匹配建议应用到 KTV 曲库，人工锁定字段不会覆盖。`,{title:'应用刮削结果'}))return
  scrapeApplying.value=true
  let success=0
  const errors=[]
  for(const item of chosen){
    const match=bestMatch(item)
    try{await api.adminApplyExternalMatch(item.songId,match.track.provider,match.track.externalId,metadataFields.map(field=>field.key));success++}
    catch(e){errors.push(`《${item.title}》：${e.message||'应用失败'}`)}
  }
  scrapeApplying.value=false
  scrapeOpen.value=false
  await load()
  await alertDialog(errors.length?`已应用 ${success} 首，${errors.length} 首失败。\n${errors.slice(0,3).join('\n')}`:`已应用 ${success} 首歌曲的元数据。`)
}
/**
 * 删除单首歌曲，确认后删除歌曲及文件。
 *
 * Deletes a single song after confirmation, including the actual file.
 * @param {Object} song - 歌曲对象 / Song object
 */
async function deleteOne(song){if(!await confirmDialog(`《${song.title}》及 /music 中的实际文件将被删除。`,{title:'删除 KTV 歌曲',tone:'warning'}))return;try{await api.adminDeleteSong(song.id);await load()}catch(e){await alertDialog(e.message||'删除失败')}}
/**
 * 批量删除选中的歌曲，确认后删除歌曲及文件。
 *
 * Batch deletes selected songs after confirmation, including actual files.
 */
async function deleteSelected(){if(!await confirmDialog(`将删除 ${selected.value.size} 首歌曲及其 /music 中的实际文件。`,{title:'批量删除 KTV 歌曲',tone:'warning'}))return;try{await api.adminDeleteSongs([...selected.value]);selected.value=new Set();await load()}catch(e){await alertDialog(e.message||'批量删除失败')}}
/** 媒体类型文本映射 / Media type text mapping */
function typeText(v){return{KTV_VIDEO:'KTV版',MV:'MV版',AUDIO:'音频版'}[v]||v}
/** 媒体类型样式类名 / Media type CSS class */
function typeClass(v){return v==='KTV_VIDEO'?'green':v==='MV'?'blue':'neutral'}
/** 导入来源文本映射 / Import source text mapping */
function sourceText(v){return{COPIED:'扫描直入',TRANSCODED:'转码入库',UNKNOWN:'历史曲库'}[v]||'历史曲库'}
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
.page-head{margin-bottom:18px}.page-head h1{font-size:22px}.page-head p{color:#64748b;font-size:13px;margin-top:6px}
.primary,.secondary,.danger{height:34px;padding:0 14px;border-radius:6px;font-size:13px}.primary{background:#2563eb;color:#fff}.secondary{display:inline-flex;align-items:center;gap:6px;background:#fff;border:1px solid #cbd5e1;color:#334155}.danger{background:#fff;border:1px solid #fecaca;color:#b91c1c}.primary:disabled,.secondary:disabled,.danger:disabled{opacity:.45;cursor:not-allowed}
.filter-panel{display:flex;align-items:flex-end;flex-wrap:wrap;gap:10px;padding:12px 14px;background:#fff;border:1px solid #e2e8f0;border-radius:8px;margin-bottom:14px}.filter-panel label{display:flex;flex:0 0 180px;flex-direction:column;gap:5px;color:#475569;font-size:12px}.filter-panel label:first-child{flex-basis:280px}.filter-panel input,.filter-panel select{width:100%;height:36px;border:1px solid #cbd5e1;border-radius:6px;padding:0 10px;background:#fff;color:#172033;font:inherit;font-size:13px;line-height:normal;box-shadow:0 1px 2px rgba(15,23,42,.03)}.filter-panel select{appearance:none;padding-right:34px;cursor:pointer}.select-control{position:relative;display:block}.select-control svg{position:absolute;right:10px;top:50%;color:#64748b;pointer-events:none;transform:translateY(-50%)}.filter-panel input:focus,.filter-panel select:focus{border-color:#60a5fa;box-shadow:0 0 0 3px rgba(37,99,235,.1);outline:0}.filter-actions{display:flex;align-items:flex-end;gap:8px}.filter-actions button{height:36px}
.table-panel{background:#fff;border:1px solid #e2e8f0;border-radius:8px}.toolbar,.pager{display:flex;align-items:center;justify-content:space-between;padding:13px 16px;color:#64748b;font-size:12px}.toolbar{border-bottom:1px solid #e2e8f0}.toolbar-actions{display:flex;align-items:center;gap:8px}.scrape-batch{color:#0f766e;border-color:#99f6e4;background:#f0fdfa}.pager{border-top:1px solid #e2e8f0}.pager div{display:flex;gap:8px}.table-scroll{position:relative;overflow:auto}table{width:100%;border-collapse:separate;border-spacing:0;min-width:1200px;font-size:12px}th{padding:11px 10px;text-align:left;background:#f8fafc;color:#64748b;border-bottom:1px solid #e2e8f0}td{padding:12px 10px;border-bottom:1px solid #eef2f7;color:#334155;background:#fff}td small{display:block;color:#94a3b8;margin-top:4px}.path{max-width:260px;word-break:break-all;color:#64748b}.action-cell{position:sticky;right:0;z-index:2;width:280px;min-width:280px;border-left:1px solid #e2e8f0;box-shadow:-10px 0 14px -14px rgba(15,23,42,.55)}th.action-cell{z-index:3}.status{display:inline-flex;padding:3px 8px;border-radius:999px;font-weight:600}.green{background:#dcfce7;color:#166534}.blue{background:#dbeafe;color:#1d4ed8}.neutral{background:#f1f5f9;color:#475569}.row-actions{display:flex;align-items:center;gap:6px}.link{display:inline-flex;align-items:center;justify-content:center;gap:4px;height:30px;padding:0 9px;border:1px solid #dbe3ee;border-radius:6px;background:#fff;color:#2563eb;font-size:11px;font-weight:600;white-space:nowrap}.link:hover:not(:disabled){border-color:#bfdbfe;background:#eff6ff}.link:disabled{opacity:.5}.playlist-link{color:#7c3aed}.playlist-link:hover:not(:disabled){border-color:#ddd6fe;background:#f5f3ff}.match-link{color:#0f766e}.match-link:hover:not(:disabled){border-color:#99f6e4;background:#f0fdfa}.danger-text{color:#b91c1c}.danger-text:hover:not(:disabled){border-color:#fecaca;background:#fef2f2}.empty{text-align:center;padding:36px;color:#94a3b8}
.mask{position:fixed;inset:0;background:rgba(15,23,42,.45);display:grid;place-items:center;z-index:100}.modal{width:min(440px,calc(100vw - 32px));background:#fff;border-radius:8px;padding:22px;box-shadow:0 18px 50px rgba(15,23,42,.18)}.modal h2{font-size:17px;margin-bottom:18px}.modal>label{display:flex;flex-direction:column;gap:6px;margin-bottom:13px;color:#475569;font-size:12px}.modal input,.modal select,.modal textarea{border:1px solid #cbd5e1;border-radius:6px;padding:0 10px;background:#fff;color:#172033}.modal input,.modal select{height:36px}.modal textarea{padding:9px 10px;resize:vertical}.modal-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:18px}
.match-modal{width:min(960px,calc(100vw - 32px));max-height:calc(100vh - 40px);overflow:auto}.match-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding-bottom:14px;border-bottom:1px solid #e2e8f0}.match-head h2{margin:0}.match-head p{margin-top:5px;color:#64748b;font-size:11px}.icon-button{display:grid;place-items:center;width:34px;height:34px;border:1px solid #cbd5e1;border-radius:6px;color:#475569}.icon-button:disabled{opacity:.45}.match-loading,.comparison-placeholder,.match-empty{display:grid;place-items:center;min-height:180px;color:#94a3b8;font-size:12px}.match-layout{display:grid;grid-template-columns:300px minmax(0,1fr);min-height:390px;margin-top:14px;border:1px solid #e2e8f0;border-radius:7px;overflow:hidden}.candidate-list{max-height:450px;overflow:auto;border-right:1px solid #e2e8f0;background:#f8fafc}.candidate-list>button{display:block;width:100%;padding:11px 12px;border-bottom:1px solid #e2e8f0;text-align:left}.candidate-list>button:hover,.candidate-list>button.active{background:#fff}.candidate-list>button.active{box-shadow:inset 3px 0 #2563eb}.candidate-list strong,.candidate-list>button>span,.candidate-list small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.candidate-list strong{margin-top:6px;color:#1e293b;font-size:12px}.candidate-list>button>span:not(.provider-tag){margin-top:4px;color:#475569;font-size:10px}.candidate-list small{margin-top:4px;color:#94a3b8;font-size:9px}.provider-tag{display:inline-block!important;width:max-content;padding:2px 5px;border-radius:4px;font-size:8px;font-weight:700}.provider-tag.netease{background:#fff1f2;color:#b91c1c}.provider-tag.qq{background:#f0fdf4;color:#15803d}.provider-tag.kugou{background:#eff6ff;color:#1d4ed8}
.comparison{min-width:0;padding:10px 14px}.compare-head,.compare-row{display:grid;grid-template-columns:105px minmax(0,1fr) minmax(0,1fr);align-items:center;gap:12px;min-height:48px;border-bottom:1px solid #eef2f7}.compare-head{min-height:34px;color:#94a3b8;font-size:9px}.compare-row{margin:0;color:#475569;font-size:10px}.compare-row>span:first-child{display:flex;align-items:center;gap:6px}.compare-row>span:first-child input{width:auto;height:auto;padding:0}.compare-row>span:first-child small{color:#b45309}.compare-row>span:not(:first-child),.compare-row>strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.compare-row>strong{color:#172033;font-size:11px}.compare-row.locked{opacity:.65}.cover-preview{display:flex;align-items:center;gap:10px;margin-top:12px;color:#64748b;font-size:9px}.cover-preview img{width:48px;height:48px;border-radius:5px;object-fit:cover}.match-apply{display:inline-flex;align-items:center;gap:5px}
.playlist-picker-modal{width:min(560px,calc(100vw - 32px));max-height:calc(100vh - 40px);overflow:auto}.playlist-picker-list{margin-top:14px;border:1px solid #e2e8f0;border-radius:7px;overflow:hidden}.playlist-picker-item{display:flex;align-items:center;justify-content:space-between;gap:14px;width:100%;padding:12px 14px;text-align:left;border-bottom:1px solid #e2e8f0;background:#fff}.playlist-picker-item:last-child{border-bottom:0}.playlist-picker-item:hover:not(:disabled){background:#f8fafc}.playlist-picker-item:disabled{opacity:.55}.playlist-picker-item strong,.playlist-picker-item small{display:block}.playlist-picker-item strong{color:#1e293b;font-size:12px}.playlist-picker-item small{margin-top:4px;color:#94a3b8;font-size:10px}.picker-action{color:#2563eb;font-size:11px;font-weight:700}
.scrape-modal{width:min(980px,calc(100vw - 32px));max-height:calc(100vh - 40px);overflow:auto}.scrape-summary{display:flex;gap:20px;padding:12px 2px;color:#64748b;font-size:11px}.scrape-list{border:1px solid #e2e8f0;border-radius:7px;overflow:hidden}.scrape-row{display:grid;grid-template-columns:22px minmax(140px,1fr) 24px minmax(180px,1.25fr) 52px 72px;align-items:center;gap:10px;min-height:66px;padding:9px 12px;border-bottom:1px solid #e2e8f0}.scrape-row:last-child{border-bottom:0}.scrape-row>input{width:15px;height:15px}.scrape-current,.scrape-suggestion{min-width:0}.scrape-current strong,.scrape-current span,.scrape-suggestion strong,.scrape-suggestion span{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.scrape-current strong,.scrape-suggestion strong{color:#172033;font-size:12px}.scrape-current span,.scrape-suggestion span{margin-top:4px;color:#64748b;font-size:10px}.scrape-suggestion small{display:inline;margin-left:8px;color:#94a3b8}.scrape-arrow{color:#94a3b8;text-align:center}.match-score{padding:3px 5px;border-radius:4px;background:#fef3c7;color:#a16207;text-align:center;font-size:10px;font-weight:700}.match-score.good{background:#dcfce7;color:#15803d}.scrape-error{grid-column:3/-1;color:#b45309;font-size:11px}.scrape-row.failed{background:#fffbeb}
@media(max-width:700px){.filter-panel label:first-child{flex-basis:100%}.filter-panel label:not(:first-child){flex:1 1 140px}.filter-actions{width:100%;justify-content:flex-end}.toolbar{align-items:flex-start;flex-direction:column}.toolbar-actions{width:100%;flex-wrap:wrap}.toolbar-actions button{flex:1}.action-cell{width:280px;min-width:280px}.link{margin-right:6px}.match-modal,.scrape-modal,.playlist-picker-modal{padding:16px}.match-layout{grid-template-columns:1fr;max-height:none}.candidate-list{max-height:210px;border-right:0;border-bottom:1px solid #e2e8f0}.compare-head,.compare-row{grid-template-columns:86px minmax(0,1fr) minmax(0,1fr);gap:7px}.comparison{padding:8px 10px}.scrape-summary{flex-wrap:wrap;gap:7px 14px}.scrape-row{grid-template-columns:20px minmax(0,1fr) 46px 62px;gap:8px}.scrape-arrow{display:none}.scrape-suggestion{grid-column:2/5}.scrape-error{grid-column:2/5}}
</style>
