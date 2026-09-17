<template>
  <AdminLayout active="source">
    <!-- 页面头部：标题和操作按钮 / Page header: title and action buttons -->
    <header class="page-head">
      <div><h1>原始音乐管理</h1><p>管理扫描源路径中的素材、重复结果和转码入库任务</p></div>
      <div class="header-actions">
        <template v-if="!externalMode"><button class="primary action-button" :disabled="progress.running || cleaning" @click="transcodeAll"><RefreshCw :size="15" />{{ progress.running ? '转码进行中' : '批量转码所有歌曲' }}</button>
        <button class="danger cleanup-button action-button" :disabled="progress.running || cleaning" @click="cleanupImported"><Trash2 :size="16" />{{ cleaning ? '清理中…' : '自动清理' }}</button></template>
      </div>
    </header>

    <div v-if="loadError" class="notice error-notice" role="alert"><span>源素材读取失败：{{ loadError }}</span><button class="text-btn" @click="load">重试</button></div>
    <section v-if="externalMode" class="readonly-panel"><strong>外部只读曲库</strong><span>当前 NAS 曲库仅允许扫描、索引和播放；转码、删除、移动和自动清理已禁用。</span></section>

    <!-- 筛选面板 / Filter panel -->
    <section v-if="!externalMode" class="filter-panel">
      <label><span>关键词</span><input v-model.trim="filters.keyword" placeholder="歌名、歌手或文件名" @keyup.enter="search" /></label>
      <label><span>处理状态</span><span class="select-control"><select v-model="filters.status" @change="search"><option value="">全部状态</option><option value="pending">待转码</option><option value="duplicate">重复</option><option value="transcoded">已转码</option><option value="unrecognized">未识别</option><option value="failed">失败</option></select><ChevronDown :size="15" aria-hidden="true" /></span></label>
      <label><span>格式分析</span><span class="select-control"><select v-model="filters.formatAnalysis" @change="search"><option value="">全部格式</option><option value="DIRECT_COPY">可直拷</option><option value="TRANSCODE_REQUIRED">需转码</option></select><ChevronDown :size="15" aria-hidden="true" /></span></label>
      <div class="filter-actions"><button class="secondary" @click="reset">重置</button><button class="primary" @click="search">查询</button></div>
    </section>

    <!-- 转码进度面板 / Transcode progress panel -->
    <section v-if="!externalMode && (progress.running || progress.total)" class="progress-panel">
      <div class="progress-copy"><strong>批量转码进度</strong><span>{{ progress.completed || 0 }} / {{ progress.total || 0 }}，成功 {{ progress.transcoded || 0 }}，重复跳过 {{ (progress.skippedSourceDuplicate || 0) + (progress.skippedOutputDuplicate || 0) }}，失败 {{ progress.failed || 0 }}</span></div>
      <div class="progress-value">{{ progressPercent }}%</div>
      <div class="track"><i :style="{ width: progressPercent + '%' }"></i></div>
      <div v-if="progress.currentFile" class="current">正在处理：{{ progress.currentFile }}</div>
    </section>

    <!-- 源素材数据表格 / Source material data table -->
    <section v-if="!externalMode" class="table-panel">
      <div class="toolbar">
        <span>共 {{ total }} 条源素材</span>
        <div class="batch-actions">
          <button class="secondary action-button" :disabled="progress.running || !selectedTranscodableCount" @click="transcodeSelected"><RefreshCw :size="14" />批量转码（{{ selected.length }}）</button>
          <button class="danger action-button" :disabled="!selected.length" @click="deleteSelected"><Trash2 :size="14" />批量删除（{{ selected.length }}）</button>
        </div>
      </div>
      <div class="table-scroll">
        <table>
          <thead><tr><th><input type="checkbox" aria-label="全选当前页" title="全选当前页" :checked="allSelected" @change="setAll($event.target.checked)" /></th><th>源文件</th><th>识别结果</th><th>格式分析</th><th>重复状态</th><th>入库结果</th><th>MD5</th><th class="action-cell">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id">
              <td><input type="checkbox" :aria-label="`选择 ${item.sourceFilename}`" :disabled="item.sourceDeleted" :checked="selected.includes(item.id)" @change="setSelected(item.id, $event.target.checked)" /></td>
              <td><strong>{{ item.sourceFilename }}</strong><small>{{ item.sourcePath }}</small></td>
              <td><strong>{{ item.parsedTitle || '未识别' }}</strong><small>{{ item.parsedArtist || '未知歌手' }} · {{ mediaText(item.mediaType) }}</small></td>
              <td><span class="status" :class="item.transcodeRequired ? 'blue' : 'green'">{{ item.transcodeRequired ? '需转码' : '可直拷' }}</span><small>{{ item.videoCodec || '—' }} / {{ item.audioCodec || '—' }} / {{ item.sourceFormat || '—' }}</small></td>
              <td><span class="status" :class="item.duplicate ? 'amber' : 'neutral'">{{ item.duplicate ? '重复' : '未重复' }}</span><small>{{ item.duplicate ? item.reason : '源/输出 MD5 未命中' }}</small></td>
              <td><span class="status" :class="statusClass(item.displayStatus)">{{ statusText(item.displayStatus) }}</span><small>{{ item.outputPath || item.reason || '—' }}</small></td>
              <td class="md5"><small>源：{{ shortMd5(item.sourceMd5) }}</small><small>输出：{{ shortMd5(item.outputMd5) }}</small></td>
              <td class="action-cell"><div class="row-actions">
                <button class="link action-button" :disabled="progress.running || !isTranscodable(item)" @click="transcodeOne(item)"><RefreshCw :size="14" />{{ progress.currentRecordId === item.id ? '转码中' : '转码' }}</button>
                <button class="link priority-text action-button" :disabled="!canPrioritize(item)" @click="prioritize(item)"><ListRestart :size="14" />{{ priorityText(item) }}</button>
                <button class="link danger-text action-button" :disabled="item.sourceDeleted" @click="deleteOne(item)"><Trash2 :size="14" />删除</button>
              </div></td>
            </tr>
            <tr v-if="!rows.length"><td colspan="8" class="empty">暂无符合条件的源素材</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager"><span>第 {{ page + 1 }} / {{ totalPages || 1 }} 页</span><div><button class="secondary" :disabled="page === 0" @click="go(page - 1)">上一页</button><button class="secondary" :disabled="page >= totalPages - 1" @click="go(page + 1)">下一页</button></div></div>
    </section>
  </AdminLayout>
</template>

<script setup>
/**
 * 原始音乐管理页面 —— 管理扫描源路径中的素材、重复检测和转码入库任务。
 *
 * Source library management page — manages scanned source materials, duplicate detection, and transcode import tasks.
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ChevronDown, ListRestart, RefreshCw, Trash2 } from 'lucide-vue-next'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog, confirmDialog } from '../../composables/useDialog'
import { createLatestRequest } from '../latestRequest'

// 列表数据、分页、选中项 / List data, pagination, selected items
const rows = ref([]), total = ref(0), page = ref(0), totalPages = ref(1), selected = ref([]), externalMode = ref(false)
const cleaning = ref(false)
const loadError = ref('')
// 默认展示全部源素材；支持关键词、处理状态与格式分析筛选。
const filters = reactive({ keyword: '', status: '', formatAnalysis: '' })
const progress = ref({ running:false, total:0, completed:0 })
let timer = null
const listRequests = createLatestRequest()

/** 当前页是否全选（仅非删除项）/ Whether all non-deleted items on current page are selected */
const allSelected = computed(() => rows.value.some(x => !x.sourceDeleted) && rows.value.filter(x => !x.sourceDeleted).every(x => selected.value.includes(x.id)))

/** 已选中可转码数量 / Count of selected transcodeable items */
const selectedTranscodableCount = computed(() => rows.value.filter(x => selected.value.includes(x.id) && isTranscodable(x)).length)

/** 转码进度百分比 / Transcode progress percentage */
const progressPercent = computed(() => progress.value.total ? Math.round(progress.value.completed * 100 / progress.value.total) : 0)

/**
 * 加载源素材列表数据。
 * Load source material list data.
 */
async function load() {
  const request = listRequests.begin()
  loadError.value = ''
  try {
    const r = await api.adminSourceLibrary({ ...filters, page:page.value, size:20 }, { signal: request.signal })
    if (!listRequests.isCurrent(request.id)) return
    externalMode.value=r.libraryMode==='EXTERNAL_READ_ONLY'
    rows.value=r.content||[]
    total.value=r.total||0
    totalPages.value=r.totalPages||1
    selected.value=externalMode.value?[]:selected.value.filter(id=>rows.value.some(x=>x.id===id))
  } catch(e) {
    if (listRequests.isCurrent(request.id) && e.name !== 'AbortError') loadError.value = e.message || '加载源素材失败'
  }
}

/**
 * 轮询转码进度，任务结束后自动清除定时器并刷新列表。
 * Poll transcode progress; auto-clear timer and refresh list when task ends.
 */
async function loadProgress() { progress.value = await api.adminSourceTranscodeProgress().catch(()=>progress.value); if (!progress.value.running && timer) { clearInterval(timer); timer=null; await load() } }

/** 搜索：重置到第一页 / Search: reset to first page */
function search(){ page.value=0; load() }

/** 重置筛选条件并重新查询 / Reset filters and re-query */
function reset(){ Object.assign(filters,{keyword:'',status:'',formatAnalysis:''}); search() }

/** 跳转到指定页 / Navigate to specified page */
function go(p){ if(p>=0&&p<totalPages.value){page.value=p;load()} }

/** 切换单个条目的选中状态 / Toggle selection for a single item */
function setSelected(id, checked){ selected.value=checked ? [...new Set([...selected.value,id])] : selected.value.filter(x=>x!==id) }

/** 全选/取消全选当前页非删除项 / Select/deselect all non-deleted items on current page */
function setAll(checked){ selected.value=checked ? rows.value.filter(x=>!x.sourceDeleted).map(x=>x.id) : [] }

/**
 * 判断素材是否可转码：需要转码、状态为待转码或失败、且源文件未删除。
 * Check if a source item is transcodable: requires transcode, status is pending/failed, and source not deleted.
 */
function isTranscodable(item){ return item.transcodeRequired && ['PENDING_TRANSCODE','FAILED'].includes(item.displayStatus) && !item.sourceDeleted }

/** 判断素材是否已插队 / Check if a source item is already prioritized */
function isPrioritized(item){ return (progress.value.priorityRecordIds||[]).includes(item.id) }

/**
 * 判断素材是否可插队：转码进行中、可转码、非当前处理项、且未插队。
 * Check if a source item can be prioritized: transcode running, transcodable, not current, not already prioritized.
 */
function canPrioritize(item){ return progress.value.running && isTranscodable(item) && progress.value.currentRecordId!==item.id && !isPrioritized(item) }

/** 获取插队按钮文本 / Get priority button text */
function priorityText(item){ return progress.value.currentRecordId===item.id?'转码中':isPrioritized(item)?'已插队':'插队' }

/**
 * 启动转码任务并开始轮询进度。
 * Start transcode task and begin polling progress.
 *
 * @param {number[]} ids - 源素材 ID 数组 / source material ID array
 */
async function startTranscode(ids){ progress.value=await api.adminStartSourceTranscode(ids); if(timer)clearInterval(timer); timer=setInterval(loadProgress,1500) }

/**
 * 批量转码所有待处理的歌曲（全量模式）。
 * Batch transcode all pending songs (full mode).
 */
async function transcodeAll(){
  if(progress.value.running)return
  if(!await confirmDialog('将处理全部待转码和转码失败的源文件。',{title:'批量转码所有歌曲'}))return
  try { progress.value=await api.adminStartSourceTranscode([],true); if(timer)clearInterval(timer); timer=setInterval(loadProgress,1500) } catch(e) { await alertDialog(e.message||'全量转码启动失败') }
}

/**
 * 自动清理已入库素材 —— 仅删除已成功入库且曲库文件仍存在的原始素材。
 * Auto-cleanup imported sources — only removes originals that have been successfully imported and whose library files still exist.
 */
async function cleanupImported(){
  if(progress.value.running||cleaning.value)return
  if(!await confirmDialog('仅删除已经成功入库、且曲库文件仍然存在的原始素材。待转码、失败、重复、未识别和曲库文件缺失的素材都会保留。源文件删除后不可恢复。',{title:'自动清理已入库素材',tone:'warning'}))return
  cleaning.value=true
  try {
    const result=await api.adminCleanupImportedSources()
    selected.value=[]
    await load()
    await alertDialog(`清理完成：删除 ${result.deleted||0} 个源文件，跳过 ${result.skipped||0} 个，不符合安全条件或无需清理；失败 ${result.failed||0} 个。请重新扫描原始音乐路径，以同步目录中的最新文件。`,{title:'自动清理完成',tone:result.failed?'warning':'success'})
  } catch(e) { await alertDialog(e.message||'自动清理失败') }
  finally { cleaning.value=false }
}

/**
 * 批量转码当前选中的源文件。
 * Batch transcode currently selected source files.
 */
async function transcodeSelected(){
  const ids=rows.value.filter(x=>selected.value.includes(x.id)&&isTranscodable(x)).map(x=>x.id)
  if(!ids.length||progress.value.running)return
  if(!await confirmDialog(`将开始处理 ${ids.length} 个已选择的待处理源文件。`,{title:'批量转码'}))return
  try { await startTranscode(ids) } catch(e) { await alertDialog(e.message||'批量转码启动失败') }
}

/**
 * 转码单个源文件。
 * Transcode a single source file.
 *
 * @param {Object} item - 源素材条目 / source material item
 */
async function transcodeOne(item){
  if(!isTranscodable(item)||progress.value.running)return
  if(!await confirmDialog(item.sourcePath,{title:'转码该源文件'}))return
  try { await startTranscode([item.id]) } catch(e) { await alertDialog(e.message||'转码启动失败') }
}

/**
 * 将指定素材插队到转码队列前端。
 * Prioritize a source item to the front of the transcode queue.
 *
 * @param {Object} item - 源素材条目 / source material item
 */
async function prioritize(item){
  if(!canPrioritize(item))return
  try { progress.value=(await api.adminPrioritizeSourceTranscode(item.id)).progress } catch(e) { await alertDialog(e.message||'插队失败') }
}

/**
 * 删除单个源文件。
 * Delete a single source file.
 *
 * @param {Object} item - 源素材条目 / source material item
 */
async function deleteOne(item){
  if(!await confirmDialog(item.sourcePath,{title:'删除源文件',tone:'warning'}))return
  try { await api.adminDeleteSources([item.id]); await load() } catch (e) { await alertDialog(e.message || '删除失败') }
}

/**
 * 批量删除选中的源文件（不可撤销）。
 * Batch delete selected source files (irreversible).
 */
async function deleteSelected(){
  const selectedIds=[...selected.value]
  if(!selectedIds.length)return
  if(!await confirmDialog(`将永久删除 ${selectedIds.length} 个已选择的源文件，此操作不可撤销。`,{title:'批量删除源文件',tone:'warning'}))return
  try {
    await api.adminDeleteSources(selectedIds)
    selected.value=[]; await load()
  } catch (e) { await alertDialog(e.message || '批量删除失败') }
}

/**
 * 缩短 MD5 字符串用于显示（取前8位+后8位）。
 * Shorten MD5 string for display (first 8 + last 8 chars).
 *
 * @param {string} v - 完整 MD5 字符串 / full MD5 string
 * @returns {string} 缩短后的 MD5 / shortened MD5
 */
function shortMd5(v){return v?`${v.slice(0,8)}...${v.slice(-8)}`:'—'}

/**
 * 将媒体类型枚举映射为中文文本。
 * Map media type enum to Chinese display text.
 *
 * @param {string} v - 媒体类型枚举值 / media type enum value
 * @returns {string} 中文显示文本 / Chinese display text
 */
function mediaText(v){return{KTV_VIDEO:'KTV 视频',MV:'MV',AUDIO:'音频'}[v]||'未知格式'}

/**
 * 将处理状态枚举映射为中文文本。
 * Map processing status enum to Chinese display text.
 *
 * @param {string} v - 状态枚举值 / status enum value
 * @returns {string} 中文显示文本 / Chinese display text
 */
function statusText(v){return{AUTO_COPIED:'已移动入库',TRANSCODED:'已转码入库',PENDING_TRANSCODE:'待转码',DUPLICATE:'重复跳过',UNRECOGNIZED:'未识别',FAILED:'失败'}[v]||v||'待处理'}

/**
 * 将处理状态枚举映射为 CSS 类名。
 * Map processing status enum to CSS class name.
 *
 * @param {string} v - 状态枚举值 / status enum value
 * @returns {string} CSS 类名 / CSS class name
 */
function statusClass(v){return{AUTO_COPIED:'green',TRANSCODED:'green',PENDING_TRANSCODE:'blue',DUPLICATE:'amber',UNRECOGNIZED:'red',FAILED:'red'}[v]||'neutral'}

/**
 * 页面挂载：加载列表数据并初始化进度轮询。
 * On mount: load list data and initialize progress polling.
 */
onMounted(async()=>{await Promise.all([load(),loadProgress()]);if(progress.value.running)timer=setInterval(loadProgress,1500)})

/**
 * 页面卸载：清除进度轮询定时器。
 * On unmount: clear progress polling timer.
 */
onUnmounted(()=>{if(timer)clearInterval(timer);listRequests.cancel()})
</script>

<style scoped>
.page-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-head h1{font-size:22px}.page-head p{color:#64748b;font-size:13px;margin-top:6px}.primary,.secondary,.danger{height:34px;padding:0 14px;border-radius:6px;font-size:13px}.primary{background:#2563eb;color:#fff}.secondary{background:#fff;border:1px solid #cbd5e1;color:#334155}.danger{background:#fff;border:1px solid #fecaca;color:#b91c1c}.primary:disabled,.secondary:disabled,.danger:disabled{opacity:.45;cursor:not-allowed}.filter-panel{display:flex;align-items:flex-end;flex-wrap:wrap;gap:10px;padding:12px 14px;background:#fff;border:1px solid #e2e8f0;border-radius:8px;margin-bottom:14px}.filter-panel label{display:flex;flex:0 0 180px;flex-direction:column;gap:5px;color:#475569;font-size:12px}.filter-panel label:first-child{flex-basis:280px}.filter-panel input,.filter-panel select{width:100%;height:36px;border:1px solid #cbd5e1;border-radius:6px;padding:0 10px;background:#fff;color:#172033;font:inherit;font-size:13px;line-height:normal;box-shadow:0 1px 2px rgba(15,23,42,.03)}.filter-panel select{appearance:none;padding-right:34px;cursor:pointer}.select-control{position:relative;display:block}.select-control svg{position:absolute;right:10px;top:50%;color:#64748b;pointer-events:none;transform:translateY(-50%)}.filter-panel input:focus,.filter-panel select:focus{border-color:#60a5fa;box-shadow:0 0 0 3px rgba(37,99,235,.1);outline:0}.filter-actions{display:flex;align-items:flex-end;gap:8px}.filter-actions button{height:36px}.progress-panel,.table-panel,.readonly-panel{background:#fff;border:1px solid #e2e8f0;border-radius:8px}.readonly-panel{display:flex;flex-direction:column;gap:6px;padding:18px 20px;margin-bottom:14px;color:#334155}.readonly-panel strong{color:#1d4ed8}.readonly-panel span{color:#64748b;font-size:13px}.progress-panel{padding:16px;margin-bottom:14px;display:grid;grid-template-columns:1fr auto;gap:8px}.progress-copy{display:flex;flex-direction:column;gap:4px}.progress-copy span,.current{font-size:12px;color:#64748b}.progress-value{color:#2563eb;font-weight:700}.track{grid-column:1/-1;height:6px;background:#e2e8f0;border-radius:4px;overflow:hidden}.track i{display:block;height:100%;background:#2563eb}.current{grid-column:1/-1}.toolbar,.pager{display:flex;align-items:center;justify-content:space-between;padding:13px 16px;color:#64748b;font-size:12px}.toolbar{border-bottom:1px solid #e2e8f0}.pager{border-top:1px solid #e2e8f0}.pager div{display:flex;gap:8px}.table-scroll{position:relative;overflow-x:auto}table{width:100%;border-collapse:separate;border-spacing:0}td,th{padding:12px 14px;font-size:12px;text-align:left;border-bottom:1px solid #e2e8f0;white-space:nowrap}td strong{display:block;font-size:13px;color:#172033;max-width:260px;overflow:hidden;text-overflow:ellipsis}td small{display:block;color:#64748b;font-size:11px;margin-top:2px;max-width:200px;overflow:hidden;text-overflow:ellipsis}.md5 small{font-family:ui-monospace,SFMono-Regular,monospace;font-size:11px}.action-cell{position:sticky;right:0;z-index:2;width:228px;min-width:228px;background:#fff;border-left:1px solid #e2e8f0;box-shadow:-10px 0 14px -14px rgba(15,23,42,.55)}thead .action-cell{z-index:3}.link{height:30px;border:1px solid #dbe3ee;background:#fff;color:#2563eb;font-size:11px;font-weight:600;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;gap:4px;padding:0 8px;border-radius:6px}.link:hover:not(:disabled){border-color:#bfdbfe;background:#eff6ff}.danger-text{color:#b91c1c}.danger-text:hover:not(:disabled){border-color:#fecaca;background:#fef2f2}.priority-text{color:#b45309}.priority-text:hover:not(:disabled){border-color:#fde68a;background:#fffbeb}.status{display:inline-block;padding:2px 8px;font-size:11px;border-radius:4px;font-weight:600}.status.blue{background:#dbeafe;color:#1d4ed8}.status.green{background:#dcfce7;color:#15803d}.status.amber{background:#fef3c7;color:#a16207}.status.red{background:#fee2e2;color:#b91c1c}.status.neutral{background:#f1f5f9;color:#475569}.empty{text-align:center;padding:48px;color:#94a3b8}.header-actions,.batch-actions,.row-actions,.action-button{display:flex;align-items:center}.header-actions,.batch-actions{gap:8px}.action-button{justify-content:center;gap:5px;white-space:nowrap}.row-actions{gap:6px}.priority-text{color:#b45309}@media(max-width:700px){.page-head{align-items:flex-start;gap:12px}.header-actions,.batch-actions{flex-wrap:wrap;justify-content:flex-end}.toolbar{align-items:flex-start;gap:10px}.action-cell{width:220px;min-width:220px}.row-actions{gap:4px}.link{padding-inline:6px}}
.cleanup-button{background:#dc2626;border-color:#dc2626;color:#fff;font-weight:700;box-shadow:0 3px 10px rgba(185,28,28,.28)}.cleanup-button:hover:not(:disabled){background:#b91c1c;border-color:#b91c1c;box-shadow:0 4px 14px rgba(185,28,28,.36)}.cleanup-button:focus-visible{outline:3px solid rgba(248,113,113,.35);outline-offset:2px}
</style>
