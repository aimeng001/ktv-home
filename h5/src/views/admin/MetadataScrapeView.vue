<template>
  <AdminLayout active="ktv">
    <div class="scrape-page">
      <header class="page-head">
        <div class="title-wrap">
          <button class="icon-btn" title="返回 KTV 曲库" @click="router.push({name:'admin-ktv-library'})"><ArrowLeft :size="18" /></button>
          <div><h1>元数据刮削</h1><p>从已启用平台为 KTV 曲库补全歌名、歌手、专辑、发行时间与封面</p></div>
        </div>
        <button class="secondary" @click="router.push({name:'admin-settings',query:{section:'metadata'}})"><Settings2 :size="15" />平台与限速设置</button>
      </header>
      <div v-if="poll.status === 'stale'" class="stale-alert" role="alert"><span>刮削进度连接中断，当前显示最后一次成功数据{{ poll.lastSuccessAt ? `（${formatTime(poll.lastSuccessAt)}）` : '' }}。</span><button class="text-btn" @click="refreshTask">立即重试</button></div>

      <section class="launch-band">
        <div class="threshold-control">
          <span>自动写入阈值</span>
          <input v-model.number="threshold" type="range" min="0.5" max="1" step="0.01" />
          <strong>{{ formatPercent(threshold) }}</strong>
          <small>低于阈值的建议保留在人工审核中</small>
          <details class="score-rule">
            <summary><Info :size="13" />置信度计算规则</summary>
            <div class="score-formula"><span><b>55%</b> 歌名</span><span><b>35%</b> 歌手</span><span><b>10%</b> 时长</span></div>
            <p>歌名和歌手会统一全半角、大小写并去除空格与标点；歌名同时忽略 MV、KTV、原唱及末尾语种、曲风标记。文本按完全一致、包含关系或编辑距离计算相似度。</p>
            <p>时长完全一致得满分，误差在 30 秒内线性下降，30 秒及以上得 0 分；任一侧缺少时长时按 50% 计。总分达到当前阈值才尝试自动写入，写入失败仍会转入人工审核。</p>
          </details>
        </div>
        <div class="launch-actions">
          <button v-if="selectedIds.size" class="secondary" :disabled="starting || activeTask" @click="startSelected"><Play :size="15" />刮削已选（{{ selectedIds.size }}）</button>
          <button class="primary" :disabled="starting || activeTask" @click="startAll"><Layers3 :size="15" />{{ starting ? '正在创建…' : '刮削全部歌曲' }}</button>
        </div>
      </section>

      <section class="task-section">
        <div class="section-title">
          <div><h2>刮削进度</h2><p>{{ task ? taskTimeText : '尚未创建刮削任务' }}</p></div>
          <div v-if="task" class="task-actions">
            <button v-if="task.status==='RUNNING'" class="secondary" :disabled="taskAction" @click="pauseTask"><Pause :size="15" />暂停</button>
            <button v-if="task.status==='PAUSED'" class="primary" :disabled="taskAction" @click="resumeTask"><Play :size="15" />继续</button>
            <span class="batch-status" :class="task.status.toLowerCase()">{{ batchStatusText(task.status) }}</span>
          </div>
        </div>

        <template v-if="task">
          <div class="progress-track"><span :style="{width:progressPercent+'%'}"></span></div>
          <div class="progress-copy"><strong>{{ task.completed }} / {{ task.total }}</strong><span>完成 {{ progressPercent }}%</span><span>本批次阈值 {{ formatPercent(task.autoApplyThreshold) }}</span><span v-if="task.skippedExisting">已跳过 {{ task.skippedExisting }} 首历史已刮削歌曲</span></div>
          <div v-if="taskThresholdDiffers" class="threshold-warning"><AlertTriangle :size="15" /><span>本批次使用 {{ formatPercent(task.autoApplyThreshold) }}，与系统当前默认 {{ formatPercent(threshold) }} 不同。新建任务将使用系统默认阈值。</span></div>
          <div class="metrics">
            <button :class="{active:statusFilter===''}" @click="setStatus('')"><strong>{{ task.total }}</strong><span>全部</span></button>
            <button :class="{active:statusFilter==='AUTO_APPLIED'}" @click="setStatus('AUTO_APPLIED')"><strong>{{ task.autoApplied }}</strong><span>自动写入</span></button>
            <button :class="{active:statusFilter==='REVIEW'}" @click="setStatus('REVIEW')"><strong>{{ task.review }}</strong><span>待审核</span></button>
            <button :class="{active:statusFilter==='FAILED'}" @click="setStatus('FAILED')"><strong>{{ task.failed }}</strong><span>失败</span></button>
            <button :class="{active:statusFilter==='PROCESSING'}" @click="setStatus('PROCESSING')"><strong>{{ task.processing }}</strong><span>处理中</span></button>
            <button :class="{active:statusFilter==='PENDING'}" @click="setStatus('PENDING')"><strong>{{ task.pending }}</strong><span>等待中</span></button>
          </div>

          <div class="result-table">
            <table>
              <thead><tr><th>歌曲</th><th>刮削建议</th><th>来源</th><th>置信度</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="item in task.items" :key="item.id">
                  <td><strong>{{ item.title }}</strong><small>{{ item.artist || '未知歌手' }}</small></td>
                  <td><template v-if="item.result?.track"><strong>{{ item.result.track.title }}</strong><small>{{ (item.result.track.artists||[]).join(' / ') || '未知歌手' }}<template v-if="item.result.track.album"> · {{ item.result.track.album }}</template></small></template><span v-else class="muted">{{ item.error || '等待刮削' }}</span></td>
                  <td><span v-if="item.provider" class="provider" :class="item.provider.toLowerCase()">{{ providerText(item.provider) }}</span><span v-else>—</span></td>
                  <td><strong v-if="item.score!=null" class="score" :class="scoreClass(item.score)">{{ formatPercent(item.score) }}</strong><span v-else>—</span></td>
                  <td><span class="item-status" :class="item.status.toLowerCase()">{{ itemStatusText(item.status) }}</span><small v-if="item.error" class="error-text">{{ item.error }}</small></td>
                  <td class="row-actions"><button v-if="['REVIEW','FAILED'].includes(item.status)" class="table-action review-action" @click="openReview(item)"><UserRoundCheck :size="14" />人工审核</button><button v-if="['REVIEW','FAILED'].includes(item.status)" class="table-action retry-action" title="重试刮削" aria-label="重试刮削" :disabled="itemBusy===item.id || activeTask" @click="retryItem(item)"><RotateCcw :size="14" /></button></td>
                </tr>
                <tr v-if="!task.items?.length"><td colspan="6" class="inline-empty">当前筛选下没有记录</td></tr>
              </tbody>
            </table>
          </div>
          <div class="pager"><span>第 {{ task.page+1 }} / {{ task.totalPages }} 页</span><div><button class="secondary" :disabled="task.page===0" @click="changeTaskPage(task.page-1)">上一页</button><button class="secondary" :disabled="task.page>=task.totalPages-1" @click="changeTaskPage(task.page+1)">下一页</button></div></div>
        </template>
        <div v-else class="empty-state"><Tags :size="28" /><strong>还没有刮削记录</strong><span>选择单曲或直接刮削全部歌曲</span></div>
      </section>
    </div>

    <div v-if="reviewing" class="mask" @click.self="closeReview">
      <div class="review-modal">
        <header><div class="review-title"><h2>{{ reviewing.standalone ? '单曲元数据匹配' : '人工审核' }}</h2><p>{{ reviewing.title }} · {{ reviewing.artist }}</p><strong v-if="reviewFileName" class="review-file" :title="reviewSong?.filePath"><FileVideo2 :size="15" />{{ reviewFileName }}</strong></div><div class="review-head-actions"><button class="icon-btn" title="重新搜索平台" :disabled="reviewLoading" @click="loadReviewData(true)"><RotateCcw :size="16" /></button><button class="icon-btn" title="关闭" @click="closeReview"><X :size="17" /></button></div></header>
        <div class="provider-filters"><button :class="{active:reviewProvider===''}" @click="setReviewProvider('')">全部平台</button><button v-for="provider in reviewProviders" :key="provider" :class="[provider.toLowerCase(),{active:reviewProvider===provider}]" @click="setReviewProvider(provider)">{{ providerText(provider) }}</button></div>
        <form class="review-search" @submit.prevent="searchReview"><Search :size="16" /><input v-model.trim="reviewKeyword" placeholder="单独输入歌名、歌手或专辑重新搜索" /><button class="primary" :disabled="reviewLoading">搜索</button></form>
        <div v-if="reviewLoading" class="review-loading">正在搜索已启用平台…</div>
        <div v-else class="review-layout">
          <aside class="review-candidates">
            <button class="manual-candidate" :class="{active:reviewManual}" @click="selectManualReview"><span class="candidate-top"><i class="provider manual">人工</i></span><strong>人工填写</strong><span>不依赖平台返回结果</span><small>填写后作为可信人工值写入并锁定</small></button>
            <button v-for="candidate in filteredReviewMatches" :key="candidate.track.provider+candidate.track.externalId" :class="{active:reviewSelected===candidate}" @click="selectReviewMatch(candidate)">
              <span class="candidate-top"><i class="provider" :class="candidate.track.provider.toLowerCase()">{{ providerText(candidate.track.provider) }}</i><b>{{ formatPercent(candidate.score) }}</b></span>
              <span class="candidate-body"><span class="candidate-cover"><img v-if="candidate.track.coverUrl" :src="candidate.track.coverUrl" alt="" referrerpolicy="no-referrer" /><Music2 v-else :size="17" /></span><span class="candidate-copy"><strong>{{ candidate.track.title }}</strong><span>{{ (candidate.track.artists||[]).join(' / ') || '未知歌手' }}</span><small>{{ candidate.track.album || '无专辑' }}</small></span></span>
            </button>
            <div v-if="!filteredReviewMatches.length" class="inline-empty">该平台没有匹配候选</div>
          </aside>
          <main v-if="reviewSelected || reviewManual" class="review-editor">
            <div class="review-score"><span>当前候选：{{ reviewManual ? '人工填写' : providerText(reviewSelected.track.provider) }}</span><strong v-if="!reviewManual">{{ formatPercent(reviewSelected.score) }}</strong></div>
            <div class="cover-compare">
              <div><span>当前封面</span><figure><img v-if="reviewCurrentCover" :src="reviewCurrentCover" alt="当前歌曲封面" /><Music2 v-else :size="24" /></figure></div>
              <ArrowRight :size="18" />
              <div><span>候选封面</span><figure><img v-if="reviewCandidateCover" :src="reviewCandidateCover" alt="候选歌曲封面" referrerpolicy="no-referrer" /><Music2 v-else :size="24" /></figure></div>
            </div>
            <div class="compare-head"><span>应用</span><span>字段</span><span>当前值</span><span>审核后写入值</span></div>
            <label v-for="field in reviewFields" :key="field.key" class="compare-row">
              <input type="checkbox" :checked="reviewApplyFields.includes(field.key)" :disabled="field.key==='cover' && !reviewExternal(field.key)" @change="toggleReviewField(field.key)" />
              <span>{{ field.label }}</span><span>{{ reviewCurrent(field.key) || '—' }}</span>
              <input v-if="field.key!=='cover'" v-model="reviewEdits[field.key]" class="review-input" placeholder="可手动填写" @input="ensureReviewField(field.key)" />
              <strong v-else>{{ reviewExternal(field.key) || '—' }}</strong>
            </label>
            <p class="edit-note">修改后的字段会作为人工值写入并锁定；未修改字段仍保留平台来源。</p>
            <section class="library-edit">
              <div class="library-edit-head"><strong>曲库补充信息</strong><span>语种和歌词在此统一维护</span></div>
              <label><span>语种</span><span class="review-select"><select v-model="reviewEdits.language"><option v-for="language in languages" :key="language" :value="language">{{ language }}</option></select><ChevronDown :size="15" aria-hidden="true" /></span></label>
              <label class="lyric-field"><span>歌词</span><textarea v-model="reviewEdits.lyricText" rows="5" :disabled="reviewLyricLoading" :placeholder="reviewLyricLoading ? '正在读取歌词…' : '可粘贴 LRC 或纯文本歌词'"></textarea></label>
            </section>
          </main>
          <div v-else class="review-placeholder">选择左侧候选后进行字段筛选和编辑</div>
        </div>
        <footer><button class="secondary" @click="closeReview">取消</button><button class="primary" :disabled="(!reviewSelected && !reviewManual) || !reviewCanApply || applying" @click="applyReview"><Check :size="15" />{{ applying ? '正在写入…' : '确认写入' }}</button></footer>
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, ArrowLeft, ArrowRight, Check, ChevronDown, FileVideo2, Info, Layers3, Music2, Pause, Play, RotateCcw, Search, Settings2, Tags, UserRoundCheck, X } from 'lucide-vue-next'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog, confirmDialog } from '../../composables/useDialog'
import { pollingDelay } from './loadState'

const route=useRoute(),router=useRouter()
const task=ref(null),threshold=ref(.95),starting=ref(false),taskAction=ref(false),itemBusy=ref(null),applying=ref(false)
const taskPage=ref(0),statusFilter=ref('')
const selectedIds=ref(new Set()),reviewing=ref(null),reviewApplyFields=ref([])
const reviewMatches=ref([]),reviewSelected=ref(null),reviewProvider=ref(''),reviewLoading=ref(false),reviewSong=ref(null),reviewLyricLoading=ref(false)
const reviewKeyword=ref('')
const reviewManual=ref(false)
const reviewEdits=reactive({title:'',artist:'',album:'',releaseDate:'',aliases:'',language:'未知',lyricText:''})
const reviewOriginal=reactive({language:'未知',lyricText:''})
const reviewFields=[{key:'title',label:'歌名'},{key:'artist',label:'歌手'},{key:'album',label:'专辑'},{key:'releaseDate',label:'发行时间'},{key:'aliases',label:'别名'},{key:'cover',label:'封面'}]
const languages=['国语','粤语','闽南语','英语','日语','韩语','纯音乐','其他','未知']
const poll=reactive({status:'idle',lastSuccessAt:null,failureCount:0})
let timer=null,refreshing=false,disposed=false
const activeTask=computed(()=>task.value&&['RUNNING','PAUSED'].includes(task.value.status))
const progressPercent=computed(()=>task.value?.total?Math.round(task.value.completed/task.value.total*100):0)
const taskThresholdDiffers=computed(()=>task.value&&Math.abs(task.value.autoApplyThreshold-threshold.value)>.000001)
const taskTimeText=computed(()=>`${modeText(task.value.mode)} · ${formatTime(task.value.createdAt)}`)
const reviewProviders=computed(()=>['NETEASE','QQ','KUGOU'].filter(provider=>reviewMatches.value.some(item=>item.track.provider===provider)))
const filteredReviewMatches=computed(()=>reviewProvider.value?reviewMatches.value.filter(item=>item.track.provider===reviewProvider.value):reviewMatches.value)
const reviewLocalChanged=computed(()=>reviewEdits.language!==reviewOriginal.language||(String(reviewEdits.lyricText||'').trim()&&String(reviewEdits.lyricText||'').trim()!==String(reviewOriginal.lyricText||'').trim()))
const reviewCanApply=computed(()=>reviewApplyFields.value.some(key=>key==='cover'?reviewExternal(key):String(reviewEdits[key]||'').trim())||reviewLocalChanged.value)
const reviewFileName=computed(()=>fileName(reviewSong.value?.filePath))
const reviewCurrentCover=computed(()=>reviewSong.value?.coverPath?`/api/cover/${reviewSong.value.id}`:'')
const reviewCandidateCover=computed(()=>reviewSelected.value?.track?.coverUrl||'')

async function initialize(){
  try{const config=await api.adminMusicSourceConfig();threshold.value=config.autoApplyThreshold??.95}catch{}
  const routeIds=String(route.query.songIds||'').split(',').map(Number).filter(id=>Number.isInteger(id)&&id>0)
  if(routeIds.length){selectedIds.value=new Set(routeIds);if(routeIds.length===1&&route.query.review==='1'){try{await openStandaloneReview(await api.adminSong(routeIds[0]))}catch{}}}
  try{const latest=await api.adminLatestMetadataScrape();if(latest.exists)task.value=latest}catch(e){await alertDialog(e.message||'刮削任务加载失败')}
  if(activeTask.value)schedulePoll(1500)
}
async function startSelected(){if(!selectedIds.value.size)return;await startTask(false,[...selectedIds.value])}
async function startAll(){if(!await confirmDialog(`将按 ${formatPercent(threshold.value)} 自动写入阈值刮削全部 KTV 歌曲，低置信结果会进入人工审核。`,{title:'刮削全部歌曲'}))return;await startTask(true,[])}
async function startTask(all,ids){starting.value=true;try{task.value=await api.adminStartMetadataScrape({all,songIds:ids,autoApplyThreshold:threshold.value});taskPage.value=0;statusFilter.value='';poll.status='idle';poll.failureCount=0;schedulePoll(1500)}catch(e){if(e.code==='MUSIC_SOURCES_NOT_CONFIGURED'){await alertDialog('请先在系统设置中启用至少一个元数据平台。');await router.push({name:'admin-settings',query:{section:'metadata'}})}else await alertDialog(e.message||'刮削任务创建失败')}finally{starting.value=false}}
async function refreshTask(){
  if(refreshing||!task.value?.batchId)return
  refreshing=true
  try{
    task.value=await api.adminMetadataScrape(task.value.batchId,{status:statusFilter.value,page:taskPage.value,size:20})
    poll.status='ready';poll.lastSuccessAt=Date.now();poll.failureCount=0
  }catch{
    poll.status='stale';poll.failureCount+=1
  }finally{
    refreshing=false
    if(activeTask.value)schedulePoll(pollingDelay(poll.status,poll.failureCount))
  }
}
async function pauseTask(){taskAction.value=true;try{await api.adminPauseMetadataScrape(task.value.batchId);await refreshTask()}catch(e){await alertDialog(e.message||'暂停失败')}finally{taskAction.value=false}}
async function resumeTask(){taskAction.value=true;try{await api.adminResumeMetadataScrape(task.value.batchId);await refreshTask()}catch(e){await alertDialog(e.message||'继续任务失败')}finally{taskAction.value=false}}
async function retryItem(item){itemBusy.value=item.id;try{await api.adminRetryMetadataScrapeItem(task.value.batchId,item.id);await refreshTask()}catch(e){await alertDialog(e.message||'重试失败')}finally{itemBusy.value=null}}
function setStatus(value){statusFilter.value=value;taskPage.value=0;refreshTask()}
function changeTaskPage(value){taskPage.value=value;refreshTask()}
async function openReview(item){reviewKeyword.value=[item.title,item.artist].filter(Boolean).join(' ');reviewing.value={...item,standalone:false};await loadReviewData(false)}
async function openStandaloneReview(song){reviewKeyword.value=[song.title,song.artist].filter(Boolean).join(' ');reviewing.value={songId:song.id,title:song.title,artist:song.artist,standalone:true};await loadReviewData(true)}
function closeReview(){if(applying.value)return;reviewing.value=null;reviewMatches.value=[];reviewSelected.value=null;reviewManual.value=false;reviewSong.value=null;reviewProvider.value='';reviewKeyword.value='';reviewLyricLoading.value=false}
async function searchReview(){if(reviewKeyword.value.length<2){await alertDialog('请输入至少 2 个字符的搜索关键词');return}await loadReviewData(true)}
async function loadReviewData(refresh){
  if(!reviewing.value)return
  reviewLoading.value=true
  try{
    const scopedProvider=reviewProvider.value
    const [song,result]=await Promise.all([api.adminSong(reviewing.value.songId),api.adminSongExternalMatches(reviewing.value.songId,refresh,reviewKeyword.value,scopedProvider?[scopedProvider]:[])])
    reviewSong.value=song;reviewMatches.value=result.matches||[];reviewProvider.value=scopedProvider
    reviewOriginal.language=song.language||'未知';reviewEdits.language=reviewOriginal.language
    reviewOriginal.lyricText='';reviewEdits.lyricText=''
    if(song.lyricType&&song.lyricType!=='none'){
      reviewLyricLoading.value=true
      try{const lyric=await api.lyricText(song.id);reviewOriginal.lyricText=lyric||'';reviewEdits.lyricText=lyric||''}catch{}
      finally{reviewLyricLoading.value=false}
    }
    const stored=reviewMatches.value.find(item=>item.track.provider===reviewing.value.provider&&item.track.externalId===reviewing.value.externalId)
    const candidate=stored||reviewMatches.value[0]
    candidate?selectReviewMatch(candidate):selectManualReview()
  }catch(e){await alertDialog(e.message||'平台元数据搜索失败')}
  finally{reviewLoading.value=false}
}
function selectReviewMatch(candidate){
  reviewManual.value=false
  reviewSelected.value=candidate
  const track=candidate?.track
  Object.assign(reviewEdits,{title:track?.title||'',artist:(track?.artists||[]).join(' / '),album:track?.album||'',releaseDate:track?.releaseDate||'',aliases:(track?.aliases||[]).join(', ')})
  reviewApplyFields.value=reviewFields.map(field=>field.key).filter(key=>reviewExternal(key))
}
function selectManualReview(){reviewManual.value=true;reviewSelected.value=null;reviewApplyFields.value=[];Object.assign(reviewEdits,{title:'',artist:'',album:'',releaseDate:'',aliases:''})}
function setReviewProvider(provider){reviewProvider.value=provider;const candidates=provider?reviewMatches.value.filter(item=>item.track.provider===provider):reviewMatches.value;if(candidates.length&&!candidates.includes(reviewSelected.value))selectReviewMatch(candidates[0])}
function reviewTrack(){return reviewSelected.value?.track}
function reviewCurrent(key){const song=reviewSong.value;return{title:song?.title,artist:song?.artist,album:song?.album,releaseDate:song?.releaseDate,aliases:(song?.aliases||[]).join(', '),cover:song?.coverPath?'已配置':''}[key]||''}
function reviewExternal(key){const track=reviewTrack();if(!track)return '';return{title:track.title,artist:(track.artists||[]).join(' / '),album:track.album,releaseDate:track.releaseDate,aliases:(track.aliases||[]).join(', '),cover:track.coverUrl?'可用':''}[key]||''}
function toggleReviewField(key){reviewApplyFields.value=reviewApplyFields.value.includes(key)?reviewApplyFields.value.filter(value=>value!==key):[...reviewApplyFields.value,key]}
function ensureReviewField(key){if(!reviewApplyFields.value.includes(key))reviewApplyFields.value=[...reviewApplyFields.value,key]}
async function applyReview(){
  const track=reviewTrack();if(!track&&!reviewManual.value)return
  const metadataFields=reviewApplyFields.value.filter(key=>reviewFields.some(field=>field.key===key))
  const overrides={}
  for(const key of metadataFields){if(key==='cover')continue;const edited=String(reviewEdits[key]||'').trim();const suggested=String(reviewExternal(key)||'').trim();if(edited&&edited!==suggested)overrides[key]=edited}
  applying.value=true
  try{
    if(reviewing.value.standalone){
      if(metadataFields.length){
        if(reviewManual.value)await api.adminApplyManualMetadata(reviewing.value.songId,metadataFields,overrides)
        else await api.adminApplyExternalMatch(reviewing.value.songId,track.provider,track.externalId,metadataFields,overrides)
      }
    }else if(metadataFields.length)await api.adminApplyMetadataScrapeItem(task.value.batchId,reviewing.value.id,{fields:metadataFields,overrides,provider:track?.provider||null,externalId:track?.externalId||null})
    if(reviewLocalChanged.value){
      const edit={language:reviewEdits.language}
      if(String(reviewEdits.lyricText||'').trim()!==String(reviewOriginal.lyricText||'').trim())edit.lyricText=reviewEdits.lyricText
      await api.adminEditSong(reviewing.value.songId,edit)
    }
    if(!reviewing.value.standalone&&!metadataFields.length)await api.adminApplyMetadataScrapeItem(task.value.batchId,reviewing.value.id,{fields:[],overrides:{},completeOnly:true})
    applying.value=false;closeReview();await refreshTask()
  }catch(e){await alertDialog(e.message||'元数据写入失败')}
  finally{applying.value=false}
}
function providerText(value){return{NETEASE:'网易云',QQ:'QQ 音乐',KUGOU:'酷狗'}[value]||value||'—'}
function modeText(value){return{ALL:'全部歌曲',SELECTED:'已选歌曲',SINGLE:'单曲'}[value]||value}
function batchStatusText(value){return{RUNNING:'刮削中',PAUSED:'已暂停',COMPLETED:'已完成'}[value]||value}
function itemStatusText(value){return{PENDING:'等待中',PROCESSING:'刮削中',AUTO_APPLIED:'已自动写入',REVIEW:'待人工审核',MANUAL_APPLIED:'已人工写入',FAILED:'失败'}[value]||value}
function scoreClass(value){return value>=task.value.autoApplyThreshold?'high':value>=.8?'medium':'low'}
function formatPercent(value){const percent=Number(value||0)*100;return `${Number.isInteger(percent)?percent.toFixed(0):percent.toFixed(1)}%`}
function formatTime(value){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):''}
function fileName(path){return String(path||'').split(/[\\/]/).filter(Boolean).pop()||''}
function schedulePoll(delay){if(disposed||!task.value?.batchId)return;if(timer)window.clearTimeout(timer);timer=window.setTimeout(()=>{timer=null;refreshTask()},delay)}
function clearPoll(){disposed=true;if(timer){window.clearTimeout(timer);timer=null}}
onMounted(initialize)
onBeforeUnmount(clearPoll)
</script>

<style scoped>
.stale-alert{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px;padding:10px 12px;border:1px solid #fde68a;border-radius:6px;background:#fffbeb;color:#92400e;font-size:11px}.stale-alert .text-btn{color:#92400e;font-weight:700;white-space:nowrap}
.scrape-page{width:100%;max-width:1220px;margin:0 auto;color:#172033}.page-head,.title-wrap,.launch-band,.launch-actions,.section-title,.task-actions,.progress-copy,.pager,.pager>div,.review-modal header,.review-modal footer{display:flex;align-items:center}.page-head{justify-content:space-between;gap:20px;margin-bottom:18px}.title-wrap{gap:12px}.page-head h1{margin:0;font-size:22px;letter-spacing:0}.page-head p,.section-title p{margin:6px 0 0;color:#64748b;font-size:11px}.icon-btn{display:grid;width:34px;height:34px;place-items:center;border:1px solid #dbe3ee;border-radius:6px;background:#fff;color:#475569}.primary,.secondary{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:34px;padding:0 13px;border-radius:6px;font-size:11px;font-weight:600}.primary{border:1px solid #2563eb;background:#2563eb;color:#fff}.secondary{border:1px solid #cbd5e1;background:#fff;color:#334155}.primary:disabled,.secondary:disabled,.text-btn:disabled{cursor:not-allowed;opacity:.48}
.launch-band{justify-content:space-between;gap:24px;padding:16px 18px;border:1px solid #dbe3ee;border-left:3px solid #2563eb;background:#fff}.threshold-control{display:grid;grid-template-columns:auto minmax(130px,240px) 44px;align-items:center;gap:9px;min-width:0}.threshold-control>span{font-size:12px;font-weight:700}.threshold-control input{width:100%;accent-color:#2563eb}.threshold-control>strong{color:#2563eb;font-size:13px}.threshold-control>small{grid-column:1/-1;color:#64748b;font-size:10px}.launch-actions{gap:8px;flex:none}.score-rule{grid-column:1/-1;width:min(620px,calc(100vw - 80px));margin-top:2px;color:#475569;font-size:10px}.score-rule summary{display:inline-flex;align-items:center;gap:5px;color:#2563eb;font-weight:600;cursor:pointer;list-style:none}.score-rule summary::-webkit-details-marker{display:none}.score-rule[open]{padding:10px 12px;border:1px solid #dbeafe;border-radius:6px;background:#f8fbff}.score-rule[open] summary{margin-bottom:9px}.score-formula{display:flex;gap:6px;flex-wrap:wrap}.score-formula span{padding:4px 7px;border:1px solid #dbe3ee;border-radius:4px;background:#fff}.score-formula b{color:#1d4ed8}.score-rule p{margin:7px 0 0;line-height:1.55}
.song-picker,.task-section{margin-top:16px;border:1px solid #dbe3ee;background:#fff}.song-picker{padding:17px 18px}.section-title{justify-content:space-between;gap:16px}.section-title h2{margin:0;font-size:15px;letter-spacing:0}.selected-count{padding:4px 8px;border-radius:4px;background:#eff6ff;color:#1d4ed8;font-size:10px;font-weight:700}.song-search{display:flex;align-items:center;gap:8px;margin-top:14px;padding:5px 5px 5px 10px;border:1px solid #cbd5e1;border-radius:6px}.song-search svg{color:#94a3b8}.song-search input{flex:1;min-width:0;border:0;outline:0;font-size:12px}.selected-summary{display:flex;align-items:center;flex-wrap:wrap;gap:6px;margin-top:10px}.selected-summary>span{display:inline-flex;align-items:center;gap:5px;padding:4px 7px;border-radius:4px;background:#f1f5f9;color:#475569;font-size:10px}.selected-summary span button{display:grid;padding:0;color:#64748b}.text-btn{display:inline-flex;align-items:center;gap:4px;color:#2563eb;font-size:10px}.song-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1px;margin-top:12px;background:#e2e8f0;border:1px solid #e2e8f0}.song-option{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;min-height:58px;padding-right:10px;background:#fff}.song-option.selected{background:#f8fbff}.song-option label{display:grid;grid-template-columns:18px minmax(0,1fr) auto;align-items:center;gap:9px;min-width:0;padding:8px 10px}.song-list input{width:14px;height:14px;accent-color:#2563eb}.song-list strong,.song-list small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.song-list strong{font-size:11px}.song-list small{margin-top:4px;color:#64748b;font-size:9px}.song-list em{color:#64748b;font-size:9px;font-style:normal}.song-option>.text-btn{padding:5px;white-space:nowrap}
.task-section{overflow:hidden}.task-section>.section-title{padding:16px 18px;border-bottom:1px solid #e2e8f0}.task-actions{gap:8px}.batch-status{padding:5px 8px;border-radius:4px;background:#f1f5f9;color:#475569;font-size:10px;font-weight:700}.batch-status.running{background:#eff6ff;color:#1d4ed8}.batch-status.paused{background:#fef3c7;color:#a16207}.batch-status.completed{background:#dcfce7;color:#15803d}.progress-track{height:6px;margin:18px 18px 0;overflow:hidden;border-radius:3px;background:#e2e8f0}.progress-track span{display:block;height:100%;border-radius:inherit;background:#2563eb;transition:width .35s ease}.progress-copy{gap:16px;padding:8px 18px;color:#64748b;font-size:10px}.progress-copy strong{color:#172033;font-size:12px}.metrics{display:grid;grid-template-columns:repeat(6,1fr);border-top:1px solid #eef2f7;border-bottom:1px solid #e2e8f0}.metrics button{min-height:60px;border-right:1px solid #eef2f7;background:#fafbfc}.metrics button:last-child{border-right:0}.metrics button.active{background:#eff6ff;box-shadow:inset 0 -2px #2563eb}.metrics strong,.metrics span{display:block}.metrics strong{font-size:17px}.metrics span{margin-top:3px;color:#64748b;font-size:9px}
.threshold-warning{display:flex;align-items:center;gap:8px;margin:0 18px 14px;padding:9px 11px;border:1px solid #fde68a;border-radius:5px;background:#fffbeb;color:#92400e;font-size:10px;line-height:1.5}.threshold-warning svg{flex:none}
.result-table{overflow-x:auto}.result-table table{width:100%;border-collapse:collapse;table-layout:fixed}.result-table th{padding:9px 12px;background:#f8fafc;color:#64748b;text-align:left;font-size:9px;font-weight:600}.result-table th:nth-child(1){width:17%}.result-table th:nth-child(2){width:25%}.result-table th:nth-child(3){width:10%}.result-table th:nth-child(4){width:9%}.result-table th:nth-child(5){width:21%}.result-table th:nth-child(6){width:18%}.result-table td{padding:11px 12px;border-top:1px solid #eef2f7;vertical-align:middle;font-size:10px}.result-table td>strong,.result-table td>small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.result-table td>strong{font-size:11px}.result-table td>small{margin-top:4px;color:#64748b}.muted{color:#94a3b8}.provider,.item-status{display:inline-block;padding:3px 6px;border-radius:4px;background:#f1f5f9;color:#475569;font-size:9px;font-weight:700}.provider.netease{background:#fff1f2;color:#b91c1c}.provider.qq{background:#f0fdf4;color:#15803d}.provider.kugou{background:#eff6ff;color:#1d4ed8}.provider.manual{background:#f1f5f9;color:#334155}.score.high{color:#15803d}.score.medium{color:#a16207}.score.low{color:#b91c1c}.item-status.auto_applied,.item-status.manual_applied{background:#dcfce7;color:#15803d}.item-status.review{background:#fef3c7;color:#a16207}.item-status.failed{background:#fee2e2;color:#b91c1c}.item-status.processing{background:#dbeafe;color:#1d4ed8}.error-text{display:block;white-space:normal!important;color:#b45309!important;line-height:1.4}.row-actions{white-space:normal}.row-actions button{margin-right:9px}.pager{justify-content:space-between;padding:10px 14px;border-top:1px solid #e2e8f0;color:#64748b;font-size:10px}.pager>div{gap:6px}.empty-state,.inline-empty{color:#94a3b8;text-align:center}.empty-state{display:flex;min-height:200px;align-items:center;justify-content:center;flex-direction:column;gap:7px}.empty-state strong{color:#475569;font-size:12px}.empty-state span{font-size:10px}.inline-empty{padding:20px;font-size:10px}
.mask{position:fixed;inset:0;z-index:100;display:grid;place-items:center;padding:20px;background:rgba(15,23,42,.45)}.review-modal{width:min(1080px,100%);max-height:calc(100vh - 40px);overflow:auto;border-radius:7px;background:#fff;box-shadow:0 20px 60px rgba(15,23,42,.2)}.review-modal header{justify-content:space-between;padding:16px 18px;border-bottom:1px solid #e2e8f0}.review-modal h2{margin:0;font-size:15px}.review-modal header p{margin:5px 0 0;color:#64748b;font-size:10px}.review-head-actions{display:flex;gap:7px}.provider-filters{display:flex;gap:6px;padding:10px 14px;border-bottom:1px solid #e2e8f0;background:#f8fafc}.provider-filters button{min-height:28px;padding:0 9px;border:1px solid #cbd5e1;border-radius:5px;background:#fff;color:#475569;font-size:9px;font-weight:700}.provider-filters button.active{border-color:#93c5fd;background:#eff6ff;color:#1d4ed8}.review-loading{padding:70px;color:#64748b;text-align:center;font-size:11px}.review-layout{display:grid;grid-template-columns:290px minmax(0,1fr);min-height:430px}.review-candidates{max-height:520px;overflow:auto;border-right:1px solid #e2e8f0;background:#f8fafc}.review-candidates>button{display:block;width:100%;padding:10px 12px;border-bottom:1px solid #e2e8f0;text-align:left}.review-candidates>button.active{background:#fff;box-shadow:inset 3px 0 #2563eb}.candidate-top{display:flex!important;align-items:center;justify-content:space-between}.candidate-top b{color:#475569;font-size:10px}.review-candidates strong,.review-candidates>button>span:not(.candidate-top),.review-candidates small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.review-candidates strong{margin-top:7px;color:#172033;font-size:11px}.review-candidates>button>span:not(.candidate-top){margin-top:4px;color:#475569;font-size:10px}.review-candidates small{margin-top:4px;color:#94a3b8;font-size:9px}.review-editor{min-width:0}.review-score{display:flex;justify-content:space-between;padding:12px 18px;background:#f8fafc;color:#64748b;font-size:10px}.review-score strong{color:#172033}.compare-head,.compare-row{display:grid;grid-template-columns:42px 90px minmax(0,1fr) minmax(0,1fr);align-items:center;gap:10px;padding:0 18px}.compare-head{min-height:34px;color:#94a3b8;font-size:9px}.compare-row{min-height:52px;border-top:1px solid #eef2f7;color:#475569;font-size:10px}.compare-row>input:first-child{width:14px;height:14px;accent-color:#2563eb}.compare-row>span,.compare-row>strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.compare-row>strong{color:#172033}.review-input{width:100%;min-width:0;height:32px;padding:0 9px;border:1px solid #cbd5e1;border-radius:5px;background:#fff;color:#172033;font-size:10px}.review-input:disabled{background:#f1f5f9;color:#94a3b8}.edit-note{margin:12px 18px;color:#64748b;font-size:9px}.review-placeholder{display:grid;place-items:center;color:#94a3b8;font-size:10px}.review-modal footer{justify-content:flex-end;gap:8px;padding:12px 18px;border-top:1px solid #e2e8f0}
@media(max-width:760px){.page-head,.launch-band{align-items:flex-start;flex-direction:column}.page-head>.secondary,.launch-actions{width:100%}.launch-actions button{flex:1}.threshold-control{width:100%;grid-template-columns:auto minmax(80px,1fr) 40px}.score-rule{width:100%}.song-list{grid-template-columns:1fr}.song-option{grid-template-columns:minmax(0,1fr)}.song-option>.text-btn{padding:0 10px 9px 37px;justify-self:start}.metrics{grid-template-columns:repeat(3,1fr)}.metrics button:nth-child(3){border-right:0}.result-table table{min-width:780px}.task-section>.section-title{align-items:flex-start;flex-direction:column}.review-layout{grid-template-columns:1fr}.review-candidates{max-height:210px;border-right:0;border-bottom:1px solid #e2e8f0}.compare-head,.compare-row{grid-template-columns:32px 68px minmax(90px,1fr) minmax(110px,1fr);padding:0 12px}.review-modal{max-height:calc(100vh - 20px)}}
.table-action{display:inline-flex;align-items:center;justify-content:center;gap:5px;height:30px;padding:0 10px;border:1px solid #dbe3ee;border-radius:6px;background:#fff;font-size:11px;font-weight:600;white-space:nowrap;transition:border-color .15s ease,background .15s ease,color .15s ease}.table-action:disabled{cursor:not-allowed;opacity:.45}.review-action{border-color:#bfdbfe;background:#eff6ff;color:#1d4ed8}.review-action:hover:not(:disabled){border-color:#93c5fd;background:#dbeafe}.retry-action{width:30px;padding:0;background:#f8fafc;color:#64748b}.retry-action:hover:not(:disabled){border-color:#cbd5e1;background:#eef2f7;color:#334155}.review-title{min-width:0}.review-file{display:flex;align-items:center;gap:6px;max-width:620px;margin-top:9px;padding:7px 9px;border-left:3px solid #2563eb;background:#eff6ff;color:#1e3a8a;font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.review-file svg{flex:none}.review-search{display:flex;align-items:center;gap:8px;padding:10px 14px;border-bottom:1px solid #e2e8f0;background:#fff}.review-search>svg{flex:none;color:#94a3b8}.review-search input{flex:1;min-width:0;height:34px;padding:0 10px;border:1px solid #cbd5e1;border-radius:5px;color:#172033;font-size:11px;outline:0}.review-search input:focus{border-color:#60a5fa;box-shadow:0 0 0 3px rgba(37,99,235,.1)}.review-search button{min-width:70px}.review-candidates{max-height:620px}.candidate-body{display:flex!important;align-items:center;gap:9px;margin-top:8px!important}.candidate-cover{display:grid!important;width:42px;height:42px;place-items:center;flex:none;overflow:hidden;border:1px solid #e2e8f0;border-radius:5px;background:#fff;color:#94a3b8}.candidate-cover img{width:100%;height:100%;object-fit:cover}.candidate-copy{min-width:0}.candidate-copy strong,.candidate-copy>span,.candidate-copy small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.candidate-copy strong{margin:0;color:#172033;font-size:11px}.candidate-copy>span{margin-top:4px;color:#475569;font-size:10px}.candidate-copy small{margin-top:4px;color:#94a3b8;font-size:9px}.review-editor{max-height:620px;overflow:auto}.cover-compare{display:flex;align-items:center;gap:14px;padding:14px 18px;border-bottom:1px solid #eef2f7}.cover-compare>div{display:flex;align-items:center;gap:9px}.cover-compare>svg{color:#94a3b8}.cover-compare span{color:#64748b;font-size:9px}.cover-compare figure{display:grid;width:64px;height:64px;margin:0;place-items:center;overflow:hidden;border:1px solid #dbe3ee;border-radius:6px;background:#f8fafc;color:#94a3b8}.cover-compare img{width:100%;height:100%;object-fit:cover}.library-edit{padding:14px 18px;border-top:1px solid #e2e8f0;background:#fafbfc}.library-edit-head{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin-bottom:12px}.library-edit-head strong{font-size:11px}.library-edit-head span{color:#94a3b8;font-size:9px}.library-edit label{display:grid;grid-template-columns:90px minmax(0,1fr);align-items:center;gap:10px;margin-top:10px;color:#475569;font-size:10px}.review-select{position:relative;display:block}.review-select select,.library-edit textarea{width:100%;border:1px solid #cbd5e1;border-radius:5px;background:#fff;color:#172033;font-size:10px}.review-select select{height:34px;padding:0 32px 0 9px;appearance:none}.review-select svg{position:absolute;right:9px;top:50%;color:#64748b;pointer-events:none;transform:translateY(-50%)}.library-edit textarea{padding:8px 9px;line-height:1.55;resize:vertical}.lyric-field{align-items:start!important}.lyric-field>span:first-child{padding-top:8px}
.result-table .row-actions{display:flex;align-items:center;gap:6px}.result-table .row-actions .table-action{margin-right:0}
</style>
