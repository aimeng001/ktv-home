<template>
  <section class="ai-panel">
    <div class="panel-head">
      <div><strong>批量 AI 生成标签</strong><span>识别歌名、歌手、语种、演唱形式、年代、曲风和主题</span></div>
      <div class="head-actions"><router-link v-if="!configured" class="button ghost" :to="{name:'admin-settings',query:{section:'ai'}}"><Settings2 :size="14" />配置 AI</router-link><button class="icon-button" title="刷新任务" :disabled="loading" @click="refresh"><RefreshCw :size="16" :class="{spin:loading}" /></button><button class="icon-button" title="关闭" @click="$emit('close')"><X :size="17" /></button></div>
    </div>

    <div class="batch-tools">
      <button v-if="selectedIds.length" class="button" :disabled="busy || !configured" @click="createSelected"><Sparkles :size="15" />为选中歌曲生成（{{ selectedIds.length }}）</button>
      <span v-else class="selection-hint">请在 KTV 曲库选择歌曲后批量生成</span>
      <label><span>未分类数量</span><input v-model.number="batchLimit" type="number" min="1" max="500" /></label>
      <button class="button ghost" :disabled="busy || !configured" @click="createUnclassified">分析未分类歌曲</button>
      <button class="button ghost" :disabled="busy || !configured" @click="repairLibrary"><WandSparkles :size="15" />修复现有曲库</button>
    </div>

    <div v-if="!configured" class="notice warning">AI 尚未配置，当前仍使用本地解析。<router-link :to="{name:'admin-settings',query:{section:'ai'}}">前往配置</router-link></div>
    <div v-if="loadError" class="notice error-notice" role="alert">AI 任务读取失败：{{ loadError }} <button class="button ghost small" @click="refresh">重试</button></div>
    <div v-if="message" class="notice">{{ message }}</div>
    <div v-if="repairBatchId" class="repair-status">
      <div><strong>修复批次</strong><span>{{ repairProgress.completed }} / {{ repairProgress.total }}</span><span>待审核 {{ repairProgress.review }}</span><span>失败 {{ repairProgress.failed }}</span></div>
      <div class="actions"><button v-if="repairProgress.running" class="button ghost small" :disabled="busy" @click="pauseRepair"><Pause :size="13" />暂停</button><button v-if="repairProgress.paused" class="button ghost small" :disabled="busy" @click="resumeRepair"><Play :size="13" />继续</button><button v-if="repairProgress.failed" class="button ghost small" :disabled="busy" @click="retryFailedRepair"><RefreshCw :size="13" />重试失败项</button></div>
    </div>

    <div class="task-list">
      <article v-for="task in tasks" :key="task.id" class="task" :class="`status-${task.status}`">
        <div class="task-top">
          <div class="task-name"><strong>{{ targetTitle(task) }}</strong><span>{{ targetArtist(task) }}</span></div>
          <span class="status">{{ statusLabel(task.status) }}</span>
        </div>
        <div class="meta">任务 #{{ task.id }} · {{ targetId(task) }} · 模型 {{ task.model || '—' }} · 置信度 <b :class="confidenceClass(task)">{{ confidenceText(task) }}</b> · {{ formatTime(task.createdAt) }}</div>
        <div v-if="task.errorMessage" class="error">{{ friendlyError(task.errorMessage) }}</div>
        <div v-if="task.status === 'review'" class="result">
          <div class="result-grid">
            <label><span>建议歌名</span><input v-model="draft(task).title" /></label><label><span>建议歌手</span><input v-model="draft(task).artist" /></label>
            <label><span>语种</span><input v-model="draft(task).language" /></label><label><span>演唱形式</span><input v-model="draft(task).vocalForm" /></label>
            <label><span>歌手类型</span><select v-model="draft(task).artistGender"><option>未知</option><option>男歌手</option><option>女歌手</option><option>组合</option></select></label>
            <label><span>年代</span><input v-model="draft(task).era" /></label><label><span>适龄</span><input v-model="draft(task).ageRange" /></label>
            <label class="wide"><span>曲风（逗号分隔）</span><input v-model="draft(task).genresText" /></label><label class="wide"><span>主题（逗号分隔）</span><input v-model="draft(task).themesText" /></label>
            <label class="wide"><span>判断说明</span><textarea v-model="draft(task).reason" rows="2"></textarea></label>
          </div>
          <div class="actions"><button class="button small" :disabled="busy" @click="applyTask(task)"><CheckCircle2 :size="14" />确认并应用</button><template v-if="task.targetType !== 'IMPORT_RECORD'"><input v-model.number="mergeTargets[task.id]" class="merge-id" type="number" min="1" placeholder="保留歌曲 ID" /><button class="button ghost small" :disabled="busy || !mergeTargets[task.id]" @click="mergeTaskSong(task)">合并重复歌曲</button></template></div>
        </div>
        <div v-if="task.status === 'failed'" class="actions task-actions"><button class="button ghost small" :disabled="busy" @click="retryTask(task.id)"><RefreshCw :size="13" />重试</button></div>
      </article>
      <div v-if="!tasks.length && !loading" class="empty">暂无 AI 分析任务</div>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { CheckCircle2, Pause, Play, RefreshCw, Settings2, Sparkles, WandSparkles, X } from 'lucide-vue-next'
import api from '../../api/client'
import { alertDialog, confirmDialog } from '../../composables/useDialog'
import { loadAiTaskData } from '../../views/admin/aiTaskState'

const props=defineProps({selectedIds:{type:Array,default:()=>[]}})
const emit=defineEmits(['close','applied'])
const loading=ref(false),busy=ref(false),configured=ref(false),message=ref(''),loadError=ref(''),tasks=ref([]),batchLimit=ref(50),repairBatchId=ref('')
const drafts=reactive({}),mergeTargets=reactive({}),repairProgress=reactive({total:0,completed:0,review:0,failed:0,paused:0,running:false})

onMounted(refresh)
async function refresh(){
  loading.value=true
  loadError.value=''
  try{
    const result=await loadAiTaskData(()=>api.adminAiTasks(),()=>api.adminAiConfig())
    tasks.value=result.tasks
    const config=result.config
    configured.value=!!(config.enabled&&config.baseUrl&&config.bulkModel)
    const errors=Object.values(result.errors).filter(Boolean)
    if(errors.length)loadError.value=errors.map(error=>error.message||'读取失败').join('；')
  }finally{loading.value=false}
}
function parseResult(task){try{return JSON.parse(task.resultJson||'{}')}catch{return{}}}
function draft(task){if(!drafts[task.id]){const value=parseResult(task);drafts[task.id]={title:value.title||task.targetTitle||'',artist:value.artist||task.targetArtist||'',artistGender:value.artistGender||'未知',language:value.language||'未知',era:value.era||'未知',ageRange:value.ageRange||'未知',vocalForm:value.vocalForm||'未知',confidence:Number(value.confidence||0),genresText:(value.genres||[]).join(', '),themesText:(value.themes||[]).join(', '),reason:value.reason||''}}return drafts[task.id]}
function splitTags(value){return String(value||'').split(/[,，]/).map(item=>item.trim()).filter(Boolean)}
function statusLabel(status){return({pending:'待处理',processing:'处理中',paused:'已暂停',review:'待人工复核',applied:'人工已应用',auto_applied:'高置信自动应用',failed:'失败'})[status]||status}
function confidence(task){return Number(parseResult(task).confidence||0)}
function confidenceText(task){return task.resultJson?`${Math.round(confidence(task)*100)}%`:'—'}
function confidenceClass(task){return confidence(task)>=.9?'confidence-high':task.resultJson?'confidence-low':''}
function formatTime(value){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):''}
function targetTitle(task){return task.targetTitle?`《${task.targetTitle}》`:(task.targetType==='IMPORT_RECORD'?'待识别文件':'歌曲待识别')}
function targetArtist(task){return task.targetArtist||'未知歌手'}
function targetId(task){return task.targetType==='IMPORT_RECORD'?`导入记录 #${task.targetId}`:`歌曲 #${task.songId}`}
function friendlyError(value){return String(value||'').includes('Cannot deserialize')?'AI 返回的字段格式不兼容，可重试；新任务已支持数组和嵌套证据。':value}
function flash(value){message.value=value;setTimeout(()=>message.value='',3000)}
async function run(action){busy.value=true;try{await action()}catch(error){await alertDialog(error.message||'操作失败')}finally{busy.value=false}}
async function createSelected(){await run(async()=>{const results=await Promise.allSettled(props.selectedIds.map(id=>api.adminAiCreateTask(id)));const created=results.filter(item=>item.status==='fulfilled').length;const skipped=results.length-created;flash(`已创建 ${created} 个任务${skipped?`，跳过 ${skipped} 首`:''}`);await refresh()})}
async function createUnclassified(){await run(async()=>{const result=await api.adminAiCreateUnclassified(batchLimit.value);flash(`已创建 ${result.created} 个任务`);await refresh()})}
async function repairLibrary(){await run(async()=>{const result=await api.adminAiRepair();repairBatchId.value=result.batchId;await loadRepairProgress();flash(`已创建 ${result.created} 个存量修复任务`);await refresh()})}
async function loadRepairProgress(){if(repairBatchId.value)Object.assign(repairProgress,await api.adminAiRepairProgress(repairBatchId.value))}
async function pauseRepair(){await run(async()=>{Object.assign(repairProgress,await api.adminAiPauseRepair(repairBatchId.value));await refresh()})}
async function resumeRepair(){await run(async()=>{await api.adminAiResumeRepair(repairBatchId.value);await loadRepairProgress();await refresh()})}
async function retryFailedRepair(){await run(async()=>{await api.adminAiRetryFailedRepair(repairBatchId.value);await loadRepairProgress();await refresh()})}
async function retryTask(id){await run(async()=>{await api.adminAiRetryTask(id);delete drafts[id];await refresh()})}
async function applyTask(task){const value=draft(task);await run(async()=>{await api.adminAiApplyTask(task.id,{title:value.title,artist:value.artist,artistGender:value.artistGender,language:value.language,era:value.era,ageRange:value.ageRange,vocalForm:value.vocalForm,genres:splitTags(value.genresText),themes:splitTags(value.themesText),recommendedPlaylists:[],reason:value.reason,confidence:value.confidence,titleConfidence:1,artistConfidence:1,languageConfidence:1,vocalFormConfidence:1});delete drafts[task.id];flash('AI 标签已应用');emit('applied');await refresh()})}
async function mergeTaskSong(task){const keepId=mergeTargets[task.id];if(!await confirmDialog(`${targetTitle(task)}的文件源和全部引用将迁移到歌曲 #${keepId}。`,{title:'合并重复歌曲',tone:'warning'}))return;await run(async()=>{await api.adminMergeSong(keepId,task.songId);delete mergeTargets[task.id];emit('applied');await refresh()})}
</script>

<style scoped>
.ai-panel{margin-bottom:14px;overflow:hidden;border:1px solid #bfdbfe;border-radius:8px;background:#fff;box-shadow:0 1px 2px rgba(15,23,42,.04)}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:15px 16px;border-bottom:1px solid #dbeafe;background:#f8fbff}.panel-head>div:first-child{display:flex;flex-direction:column;gap:4px}.panel-head strong{color:#1e3a8a;font-size:14px}.panel-head span{color:#64748b;font-size:11px}.head-actions,.actions,.batch-tools,.repair-status>div{display:flex;align-items:center;gap:8px}.icon-button{display:grid;place-items:center;width:34px;height:34px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#64748b}.button{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:34px;padding:0 12px;border-radius:6px;background:#2563eb;color:#fff;font-size:12px;font-weight:600;white-space:nowrap}.button.ghost{border:1px solid #cbd5e1;background:#fff;color:#475569}.button.small{min-height:30px;padding:0 10px;font-size:11px}.button:disabled,.icon-button:disabled{opacity:.45;cursor:not-allowed}.batch-tools{flex-wrap:wrap;padding:13px 16px;border-bottom:1px solid #eef2f7}.batch-tools label{display:flex;align-items:center;gap:7px;margin-left:auto;color:#64748b;font-size:11px}.batch-tools input{width:72px}.notice{margin:12px 16px 0;padding:9px 11px;border:1px solid #bbf7d0;border-radius:6px;background:#f0fdf4;color:#166534;font-size:11px}.notice.warning{border-color:#fde68a;background:#fffbeb;color:#92400e}.notice a{margin-left:5px;color:#1d4ed8;font-weight:600}.repair-status{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:12px 16px 0;padding:10px 12px;border:1px solid #dbeafe;border-radius:6px;background:#f8fbff}.repair-status span{color:#64748b;font-size:11px}.task-list{display:grid;gap:10px;padding:14px 16px}.task{overflow:hidden;border:1px solid #e2e8f0;border-left:3px solid #cbd5e1;border-radius:7px;background:#fff}.task.status-review{border-left-color:#f59e0b}.task.status-failed{border-left-color:#ef4444}.task.status-applied,.task.status-auto_applied{border-left-color:#22c55e}.task.status-processing{border-left-color:#3b82f6}.task-top{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:13px 14px 0}.task-name{display:flex;min-width:0;flex-direction:column;gap:4px}.task-name strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#1e293b;font-size:13px}.task-name span{color:#64748b;font-size:11px}.status{padding:4px 8px;border-radius:999px;background:#f1f5f9;color:#475569;font-size:10px;font-weight:600}.status-failed .status{background:#fee2e2;color:#b91c1c}.status-review .status{background:#fef3c7;color:#92400e}.status-applied .status,.status-auto_applied .status{background:#dcfce7;color:#166534}.meta{padding:7px 14px 12px;color:#94a3b8;font-size:10px}.confidence-high{color:#15803d}.confidence-low{color:#b45309}.error{margin:0 14px 12px;padding:9px 10px;border:1px solid #fecaca;border-radius:6px;background:#fef2f2;color:#b91c1c;font-size:11px;line-height:1.5}.result{padding:13px 14px;border-top:1px solid #e2e8f0;background:#fbfcfe}.result-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.result-grid label{display:flex;flex-direction:column;gap:5px;color:#64748b;font-size:10px}.result-grid .wide{grid-column:1/-1}input,textarea,select{width:100%;min-height:34px;padding:7px 9px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#172033;font:inherit;font-size:12px}textarea{resize:vertical}.merge-id{width:120px}.task-actions{padding:0 14px 12px}.empty{padding:28px;text-align:center;color:#94a3b8;font-size:12px}.spin{animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:700px){.panel-head{align-items:flex-start}.panel-head>div:first-child span{max-width:220px}.batch-tools label{width:100%;margin-left:0}.batch-tools>.button{flex:1}.repair-status{align-items:flex-start;flex-direction:column}.result-grid{grid-template-columns:1fr}.result-grid .wide{grid-column:auto}}
</style>
