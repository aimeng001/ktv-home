<template>
  <AdminLayout active="dashboard">
    <header class="page-head">
      <div><h1>仪表盘</h1><p>扫描源路径并查看曲库、转码与播放服务状态</p></div>
      <div class="header-actions">
        <button class="secondary" :disabled="downloadingDiag" @click="downloadDiag"><Download :size="15" />{{ downloadingDiag ? '打包中…' : '下载诊断包' }}</button>
        <button class="primary" :disabled="scanning" @click="scan">{{ scanning ? '扫描中…' : externalMode ? '扫描外部只读曲库' : '扫描源路径' }}</button>
      </div>
    </header>
    <!-- 统计卡片 / Stats cards -->
    <section class="stats"><article><span>{{ externalMode ? '外部曲库文件' : '原始素材' }}</span><strong>{{ sourceTotal }}</strong><small>{{ externalMode ? 'NAS 只读索引' : '/source-music' }}</small></article><article><span>KTV曲库</span><strong>{{ d.totalSongs ?? 0 }}</strong><small>/music，可点歌</small></article><article><span>{{ externalMode ? '源文件转码' : '待转码' }}</span><strong>{{ pendingCount }}</strong><small>{{ externalMode ? '只读模式已禁用' : '等待批量转码入库' }}</small></article><article><span>未识别</span><strong>{{ d.unrecognizedCount ?? 0 }}</strong><small>需补录元数据</small></article></section>
    <!-- 扫描进度 / Scan progress -->
    <section v-if="scanning || scanResult" class="scan-progress" :class="{complete:!scanning && scanProgress.state !== 'FAILED', failed: scanProgress.state === 'FAILED'}">
      <div class="progress-head">
        <div>
          <strong>{{ scanning ? scanPhaseLabel(scanProgress.phase, scanProgress.state) : scanProgress.state === 'FAILED' ? '扫描失败' : scanProgress.state === 'PARTIAL' ? '部分扫描完成' : '扫描完成' }}</strong>
          <span v-if="scanning">{{ scanProgress.currentFile || scanPhaseHint }}</span>
          <span v-else-if="scanProgress.state === 'FAILED'" class="error-msg">{{ scanProgress.errorMessage || '曲库文件读取受阻，请检查 NAS 读取/遍历权限' }}</span>
          <span v-else>{{ scanResult.finishedAt ? `完成于 ${formatTime(scanResult.finishedAt)}` : '' }}</span>
        </div>
        <b>{{ scanPercent }}%</b>
      </div>
      <div class="track"><i :style="{width:`${scanPercent}%`}"></i></div>
      <div class="progress-meta">
        <span v-if="scanProgress.phase">已发现 {{ scanProgress.discovered || 0 }}</span>
        <span v-if="scanProgress.phase">快速索引 {{ scanProgress.fastIndexed || 0 }}</span>
        <span v-if="scanProgress.phase === 'MEDIA_PROBE'">媒体探测 {{ scanProgress.probeCompleted || 0 }} / {{ scanProgress.probeQueued || 0 }}</span>
        <span v-else>已处理 {{ scanProgress.completed || 0 }} / {{ scanProgress.total || 0 }}</span>
        <span v-if="scanning && scanProgress.probedPerSecond">速率 {{ scanProgress.probedPerSecond }} 首/秒</span>
        <span v-if="scanning && scanProgress.estimatedRemainingSeconds">预计剩余 {{ formatEta(scanProgress.estimatedRemainingSeconds) }}</span>
        <span>{{ scanSummary.primaryLabel }} {{ scanSummary.primaryCount }}</span>
        <span>{{ scanSummary.secondaryLabel }} {{ scanSummary.secondaryCount }}</span>
        <span v-if="scanSummary.mode === 'MANAGED'">重复 {{ scanSummary.duplicateCount }}</span>
        <span>未识别 {{ scanSummary.unrecognizedCount }}</span>
        <span v-if="scanSummary.failedCount" class="failed">失败/受阻 {{ scanSummary.failedCount }}</span>
      </div>
    </section>
    <!-- 运行状态面板 / Status panel -->
    <section class="panel"><div class="panel-head"><strong>运行状态</strong><button class="text-btn" @click="load">刷新</button></div><table><thead><tr><th>模块</th><th>当前状态</th><th>详情</th><th>操作</th></tr></thead><tbody>
      <tr><td><strong>源路径扫描</strong><small>{{ externalMode ? '分析并建立外部只读索引' : '分析、去重、兼容文件直接移动入库' }}</small></td><td><span class="status green">{{ scanning ? '扫描中' : '就绪' }}</span></td><td>{{ externalMode ? '只读取 NAS 文件并建立索引，不修改、移动或转码源文件。' : '兼容文件扫描后移入 KTV 曲库；需转码文件只进入待处理列表。' }}</td><td><button class="link" @click="scan" :disabled="scanning">重新扫描</button></td></tr>
      <tr v-if="!externalMode"><td><strong>批量转码</strong><small>原始音乐管理任务</small></td><td><span class="status" :class="progress.running?'blue':'neutral'">{{ progress.running ? '进行中' : '空闲' }}</span></td><td>{{ progress.running ? `${progress.completed}/${progress.total}，当前：${progress.currentFile || '准备中'}` : lastProgressText }}</td><td><router-link class="link" :to="{name:'admin-source-library'}">查看进度</router-link></td></tr>
      <tr v-else><td><strong>批量转码</strong><small>外部只读模式</small></td><td><span class="status neutral">已禁用</span></td><td>外部 NAS 源文件不可转码或生成源旁路输出。</td><td>—</td></tr>
      <tr><td><strong>播放服务</strong><small>TV 与手机点歌</small></td><td><span class="status green">{{ queueState }}</span></td><td>当前连接 {{ d.connectedClients ?? 0 }} 台客户端，正式曲库 KTV {{ d.ktvCount||0 }} / MV {{ d.mvCount||0 }} / 音频 {{ d.audioCount||0 }}。</td><td><router-link class="link" :to="{name:'admin-ktv-library'}">管理曲库</router-link></td></tr>
    </tbody></table></section>
  </AdminLayout>
</template>
<script setup>
/**
 * 管理后台仪表盘页面 —— 展示源路径扫描、曲库统计、批量转码与播放服务状态。
 *
 * Admin dashboard page — displays source scan status, song library stats,
 * batch transcoding progress, and playback service status.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Download } from 'lucide-vue-next'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog } from '../../composables/useDialog'
import { normalizeScanProgress, scanPercent as calculateScanPercent, scanPhaseLabel, formatEta } from './scanProgress'
const d=ref({}),queue=ref({}),progress=ref({}),scanning=ref(false),scanResult=ref(null),scanProgress=ref({}),sourceTotal=ref(0),pendingCount=ref(0)
const downloadingDiag=ref(false)
const libraryMode=ref('MANAGED')
let scanTimer=null
/**
 * 播放队列当前状态的中文映射。
 *
 * Chinese label for the current playback queue state.
 */
const queueState=computed(()=>({playing:'播放中',paused:'已暂停',idle:'空闲'}[queue.value.state]||'空闲'))
/**
 * 上次批量转码完成时的文本摘要。
 *
 * Text summary of the last batch transcoding run.
 */
const lastProgressText=computed(()=>progress.value.finishedAt?`上次完成：成功 ${progress.value.transcoded||0}，失败 ${progress.value.failed||0}`:'暂无批量转码记录')
/**
 * 扫描进度百分比（0–100），根据已完成数与总数计算。
 *
 * Scan progress percentage (0–100), computed from completed/total count.
 */
const scanPercent=computed(()=>calculateScanPercent(scanProgress.value))
const scanPhaseHint=computed(()=>({DISCOVERING:'正在读取文件列表…',FAST_INDEX:'正在保存基础曲库…',MEDIA_PROBE:'正在读取媒体信息…'}[scanProgress.value.phase]||'正在扫描源路径…'))
/**
 * 重复文件总数（源路径重复 + 输出路径重复）。
 *
 * Total duplicate file count (source duplicates + output duplicates).
 */
const scanSummary=computed(()=>normalizeScanProgress(scanProgress.value))
const externalMode=computed(()=>libraryMode.value==='EXTERNAL_READ_ONLY'||scanSummary.value.mode==='EXTERNAL_READ_ONLY')
/**
 * 加载仪表盘全部数据：服务状态、队列、转码进度、源库统计、扫描进度。
 * 若扫描正在运行则自动开启轮询；若已完成则展示结果。
 *
 * Load all dashboard data: service status, queue, transcoding progress,
 * source library stats, and scan progress. Automatically starts polling
 * if a scan is running, or shows the result if one has finished.
 * @returns {Promise<void>}
 */
async function load(){const [status,q,p,sources,pending,sp]=await Promise.all([api.adminStatus().catch(()=>({})),api.getQueue().catch(()=>({})),api.adminSourceTranscodeProgress().catch(()=>({})),api.adminSourceLibrary({page:0,size:1}).catch(()=>({})),api.adminSourceLibrary({status:'pending',page:0,size:1}).catch(()=>({})),api.adminScanProgress().catch(()=>({}))]);d.value=status;queue.value=q;progress.value=p;libraryMode.value=sources.libraryMode||libraryMode.value;sourceTotal.value=(libraryMode.value==='EXTERNAL_READ_ONLY'||sources.libraryMode==='EXTERNAL_READ_ONLY')?((status.externalIndexedFiles!=null&&status.externalIndexedFiles>0)?status.externalIndexedFiles:(sp.total||0)):(sources.total||0);pendingCount.value=pending.total||0;scanProgress.value=sp;if(sp.running){scanning.value=true;startPolling()}else if(sp.finishedAt){scanResult.value=sp}}

/**
 * 启动扫描进度轮询（每秒一次）。
 *
 * Start polling scan progress (once per second).
 */
function startPolling(){if(!scanTimer)scanTimer=setInterval(pollScan,1000)}

/**
 * 停止扫描进度轮询并清除定时器。
 *
 * Stop polling scan progress and clear the timer.
 */
function stopPolling(){if(scanTimer){clearInterval(scanTimer);scanTimer=null}}

/**
 * 轮询扫描进度；检测到扫描结束时自动停止轮询并刷新仪表盘数据。
 *
 * Poll scan progress; when scan completion is detected, stop polling
 * and refresh dashboard data automatically.
 * @returns {Promise<void>}
 */
async function pollScan(){const previous=scanning.value;const value=await api.adminScanProgress().catch(()=>scanProgress.value);scanProgress.value=value;scanning.value=!!value.running;if(previous&&!value.running){scanResult.value=value;stopPolling();await load()}}

/**
 * 触发源路径扫描，启动后自动轮询进度。
 * 若已有扫描在运行则直接返回。
 *
 * Trigger a source path scan and start polling progress automatically.
 * No-op if a scan is already running.
 * @returns {Promise<void>}
 */
async function scan(){if(scanning.value)return;try{scanProgress.value=await api.adminStartScan();scanning.value=true;scanResult.value=null;startPolling()}catch(e){await alertDialog(e.message||'扫描失败')}}

async function downloadDiag(){
  if(downloadingDiag.value)return
  downloadingDiag.value=true
  try{
    const blob=await api.adminDownloadDiagnostics()
    const url=URL.createObjectURL(blob)
    const a=document.createElement('a')
    a.href=url
    a.download='home-ktv-diagnostics.zip'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }catch(err){
    await alertDialog(err.message||'下载诊断包失败')
  }finally{
    downloadingDiag.value=false
  }
}

/**
 * 将 ISO 时间字符串格式化为中文本地时间。
 *
 * Format an ISO time string as Chinese locale date/time.
 * @param {string|number|Date} value - 时间值 / the time value to format
 * @returns {string} 格式化后的本地时间 / formatted local time string
 */
function formatTime(value){return new Date(value).toLocaleString('zh-CN',{hour12:false})}
/** 挂载时加载仪表盘数据。 / Load dashboard data on mount. */
onMounted(load)
/** 卸载时停止轮询。 / Stop polling on unmount. */
onUnmounted(stopPolling)
</script>
<style scoped>
.page-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-head h1{font-size:22px}.page-head p{color:#64748b;font-size:13px;margin-top:6px}.header-actions{display:flex;align-items:center;gap:10px}.primary,.secondary{height:36px;padding:0 15px;border-radius:6px;font-size:13px}.primary{background:#2563eb;color:#fff}.secondary{display:inline-flex;align-items:center;gap:6px;background:#fff;border:1px solid #cbd5e1;color:#334155}.primary:disabled,.secondary:disabled{opacity:.5}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:14px}.stats article{background:#fff;border:1px solid #e2e8f0;border-radius:8px;padding:16px}.stats span,.stats small{display:block;color:#64748b;font-size:12px}.stats strong{display:block;font-size:26px;margin:8px 0 6px}.stats small{color:#94a3b8}.scan-progress{padding:14px 16px;background:#eff6ff;border:1px solid #bfdbfe;border-radius:8px;margin-bottom:14px;color:#1e40af}.scan-progress.complete{background:#f0fdf4;border-color:#bbf7d0;color:#166534}.scan-progress.failed{background:#fef2f2;border-color:#fecaca;color:#991b1b}.scan-progress.failed .track{background:#fee2e2}.scan-progress.failed .track i{background:#dc2626}.error-msg{color:#b91c1c;font-weight:600}.progress-head{display:flex;align-items:center;justify-content:space-between;gap:16px}.progress-head div{min-width:0}.progress-head strong,.progress-head span{display:block}.progress-head span{margin-top:4px;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.progress-head b{font-size:18px}.track{height:7px;margin:12px 0 9px;background:#dbeafe;border-radius:4px;overflow:hidden}.complete .track{background:#dcfce7}.track i{display:block;height:100%;background:#2563eb;transition:width .25s}.complete .track i{background:#16a34a}.progress-meta{display:flex;flex-wrap:wrap;gap:8px 18px;font-size:12px}.progress-meta .failed{color:#b91c1c;font-weight:700}.panel{background:#fff;border:1px solid #e2e8f0;border-radius:8px}.panel-head{display:flex;justify-content:space-between;padding:14px 16px;border-bottom:1px solid #e2e8f0}.text-btn,.link{color:#2563eb;font-size:12px}.link:disabled{color:#94a3b8}table{width:100%;border-collapse:collapse;font-size:12px}th{padding:11px 14px;text-align:left;background:#f8fafc;color:#64748b}td{padding:13px 14px;border-top:1px solid #eef2f7;color:#475569}td strong,td small{display:block}td small{color:#94a3b8;margin-top:4px}.status{display:inline-flex;padding:3px 8px;border-radius:999px;font-weight:600}.green{background:#dcfce7;color:#166534}.blue{background:#dbeafe;color:#1d4ed8}.neutral{background:#f1f5f9;color:#475569}@media(max-width:900px){.stats{grid-template-columns:1fr 1fr}.panel{overflow:auto}table{min-width:760px}}
</style>
