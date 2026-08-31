<template>
  <AdminLayout active="artists">
    <header class="page-head">
      <div class="title-block"><div class="title-mark"><UsersRound :size="19" /></div><div><h1>歌手库</h1><p>按歌手聚合歌曲，AI 提供类型建议，确认后再批量写回。</p></div></div>
      <div class="head-actions"><button class="secondary action-button" :disabled="loading || refreshing" title="刷新歌手列表" @click="load"><RefreshCw :size="15" :class="{spin: loading || refreshing}" />刷新</button><button class="primary action-button" :disabled="loading || refreshing || !artists.length || batchAnalyzing" :title="selectedKeys.size ? '批量分析已选择的歌手' : '批量分析当前筛选中的待复核歌手'" @click="openBatch"><Sparkles :size="15" />批量 AI 分析<span v-if="selectedKeys.size">({{ selectedKeys.size }})</span></button></div>
    </header>

    <section class="stats-row"><article><span>歌手总数</span><strong>{{ total }}</strong><small>当前筛选结果</small></article><article><span>待复核</span><strong>{{ pendingCount }}</strong><small>当前页类型尚未确认</small></article><article><span>已选择</span><strong>{{ selectedKeys.size }}</strong><small>可批量分析</small></article></section>

    <section class="filter-panel">
      <label class="keyword-field"><span>歌手名称</span><input v-model.trim="filters.keyword" placeholder="搜索歌手" @keyup.enter="load({ resetPage: true })" /></label>
      <label><span>歌手类型</span><span class="select-control"><select v-model="filters.gender" @change="load({ resetPage: true })"><option value="">全部</option><option value="男歌手">男歌手</option><option value="女歌手">女歌手</option><option value="组合">组合</option><option value="未知">待复核</option></select><ChevronDown :size="15" /></span></label>
      <label><span>复核状态</span><span class="select-control"><select v-model="filters.reviewed" @change="load({ resetPage: true })"><option value="">全部</option><option value="true">已复核</option><option value="false">待复核</option></select><ChevronDown :size="15" /></span></label>
      <div class="filter-actions"><button class="secondary" @click="reset">重置</button><button class="primary" @click="load({ resetPage: true })">查询</button></div>
    </section>

    <section class="table-panel">
      <div v-if="loadError" class="load-error" role="alert"><span>{{ loadError }}</span><button class="secondary" @click="load">重试</button></div>
      <div class="table-scroll"><table><thead><tr><th class="check-cell"><input type="checkbox" aria-label="全选当前歌手" :checked="allSelected" @change="toggleAll($event.target.checked)" /></th><th>歌手</th><th>类型</th><th>歌曲数</th><th>代表歌曲</th><th class="action-cell">操作</th></tr></thead>
        <tbody><tr v-for="artist in artists" :key="artist.artistKey || artist.name"><td class="check-cell"><input type="checkbox" :aria-label="`选择 ${artist.name}`" :checked="selectedKeys.has(artist.artistKey || artist.name)" @change="toggleArtist(artist, $event.target.checked)" /></td><td><div class="artist-cell"><div class="artist-avatar"><img v-if="artist.avatarUrl" :src="artist.avatarUrl" :alt="`${artist.name}头像`" /><span v-else>{{ (artist.name || '?').slice(0, 1) }}</span></div><div><strong>{{ artist.name }}</strong><small>{{ artist.reviewed ? '已人工确认' : '待复核' }}</small></div></div></td><td><span class="status" :class="artist.gender === '未知' ? 'amber' : 'blue'">{{ artist.gender }}</span><small v-if="artist.artistKind !== 'PERSON'" class="kind-note">{{ artist.artistKind === 'VARIOUS' ? '群星' : '未归属' }}</small></td><td><strong class="count">{{ artist.songCount }}</strong><small>首歌曲</small></td><td><span class="samples">{{ (artist.songs || []).map(song => song.title).join('、') || '暂无代表歌曲' }}</span></td><td class="action-cell"><button class="link" :disabled="artist.artistKind === 'VARIOUS' || artist.artistKind === 'UNATTRIBUTED'" @click="openReview(artist)"><Sparkles :size="14" />{{ artist.artistKind === 'VARIOUS' || artist.artistKind === 'UNATTRIBUTED' ? '无需分析' : (artist.reviewed ? '重新复核' : '分析 / 复核') }}</button></td></tr><tr v-if="!artists.length && !loading"><td colspan="6" class="empty">暂无歌手数据</td></tr></tbody>
      </table></div>
      <div class="pagination"><button class="secondary" :disabled="loading || refreshing || page <= 0" @click="load({ page: page - 1 })">上一页</button><span>第 {{ total ? page + 1 : 0 }} / {{ pageCount }} 页，共 {{ total }} 位</span><button class="secondary" :disabled="loading || refreshing || page + 1 >= pageCount" @click="load({ page: page + 1 })">下一页</button></div>
    </section>

    <div v-if="batchOpen" class="mask" @click.self="closeBatch"><section class="modal batch-modal" role="dialog" aria-modal="true" aria-label="批量 AI 分析"><header class="modal-head"><div><div class="modal-kicker"><Sparkles :size="14" />歌手分析</div><h2>批量 AI 分析</h2><p>{{ batchAnalyzing ? `正在分析 ${batchProgress} / ${batchTotal} 位歌手` : '分析结果仅作为建议，勾选后才会写入歌曲。' }}</p></div><button class="icon-button" title="关闭" :disabled="batchAnalyzing || batchSaving" @click="closeBatch"><X :size="17" /></button></header><div v-if="batchAnalyzing" class="batch-loading"><RefreshCw :size="20" class="spin" /><strong>正在分析歌手代表歌曲…</strong><span>请保持页面打开</span></div><template v-else><div class="batch-summary"><span>已分析 {{ batchRows.length }} 位</span><span>建议可用 {{ usableBatchCount }} 位</span><span>待写入 {{ batchApplyCount }} 位</span></div><div class="batch-table-wrap"><table class="batch-table"><thead><tr><th class="check-cell">写入</th><th>歌手</th><th>AI 建议</th><th>置信度</th><th>判断理由</th></tr></thead><tbody><tr v-for="row in batchRows" :key="row.artist"><td class="check-cell"><input type="checkbox" :checked="row.apply" :disabled="row.gender === '未知'" :aria-label="`写入 ${row.artist}`" @change="row.apply = $event.target.checked" /></td><td><strong>{{ row.artist }}</strong><small>{{ row.source === 'AI' ? 'AI 返回' : '需人工复核' }}</small></td><td><select v-model="row.gender"><option value="未知">未知</option><option value="男歌手">男歌手</option><option value="女歌手">女歌手</option><option value="组合">组合</option></select></td><td><span class="confidence" :class="confidenceClass(row.confidence)">{{ Math.round((row.confidence || 0) * 100) }}%</span></td><td><span class="reason-cell">{{ row.reason || '请人工确认' }}</span></td></tr></tbody></table></div></template><footer class="modal-actions"><button class="secondary" :disabled="batchAnalyzing || batchSaving" @click="closeBatch">取消</button><button v-if="!batchAnalyzing" class="primary action-button" :disabled="!batchApplyCount || batchSaving" @click="applyBatch"><Check :size="15" />{{ batchSaving ? `写入中 ${batchSaveProgress}/${batchApplyCount}` : `写入已选择 (${batchApplyCount})` }}</button></footer></section></div>

    <div v-if="reviewOpen" class="mask" @click.self="closeReview"><section class="modal review-modal" role="dialog" aria-modal="true" aria-label="歌手复核"><header class="modal-head"><div><div class="modal-kicker"><UsersRound :size="14" />单个复核</div><h2>{{ selected?.name }}</h2><p>{{ selected?.songCount }} 首歌曲 · 代表歌曲用于判断参考</p></div><button class="icon-button" title="关闭" @click="closeReview"><X :size="17" /></button></header><div class="modal-body"><div class="review-summary"><div><span>AI 建议</span><strong>{{ suggestion?.gender || selected?.gender || '未知' }}</strong></div><div><span>置信度</span><strong>{{ suggestion ? `${Math.round((suggestion.confidence || 0) * 100)}%` : '未分析' }}</strong></div></div><p class="reason">{{ suggestion?.reason || '点击“AI 分析”获取建议；没有 API Key 时仍可手动选择。' }}</p><div class="sample-head"><strong>代表歌曲</strong><small>最多展示 5 首</small></div><div class="sample-list"><div v-for="song in (suggestion?.songs || selected?.songs || [])" :key="song.id" class="sample"><div class="cover"><img v-if="song.coverUrl" :src="song.coverUrl" alt="" /><span v-else>♪</span></div><div><strong>{{ song.title }}</strong><small>{{ song.artist }} · {{ song.language || '未知语种' }}</small></div></div></div><label class="gender-editor"><span>确认歌手类型</span><span class="select-control"><select v-model="reviewGender"><option value="未知">未知</option><option value="男歌手">男歌手</option><option value="女歌手">女歌手</option><option value="组合">组合</option></select><ChevronDown :size="15" /></span></label></div><footer class="modal-actions"><button class="secondary" @click="closeReview">取消</button><button class="secondary action-button" :disabled="analyzing" @click="analyze"><Sparkles :size="14" />{{ analyzing ? '分析中…' : 'AI 分析' }}</button><button class="primary action-button" :disabled="saving" @click="apply"><Check :size="14" />{{ saving ? '保存中…' : '确认并写入' }}</button></footer></section></div>
  </AdminLayout>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Check, ChevronDown, RefreshCw, Sparkles, UsersRound, X } from 'lucide-vue-next'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog } from '../../composables/useDialog'
import { resolveAiConfiguration } from './artistAiState'

const artists = ref([]), loading = ref(false), refreshing = ref(false), selectedKeys = ref(new Set())
const router = useRouter()
const reviewOpen = ref(false), selected = ref(null), suggestion = ref(null), analyzing = ref(false), saving = ref(false), reviewGender = ref('未知')
const batchOpen = ref(false), batchAnalyzing = ref(false), batchSaving = ref(false), batchRows = ref([]), batchProgress = ref(0), batchTotal = ref(0), batchSaveProgress = ref(0)
const filters = reactive({ keyword: '', gender: '', reviewed: '' })
const page = ref(0), pageSize = 50, total = ref(0), loadError = ref('')
const allSelected = computed(() => artists.value.length > 0 && artists.value.every(item => selectedKeys.value.has(item.artistKey || item.name)))
const pendingCount = computed(() => artists.value.filter(item => !item.reviewed).length)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const usableBatchCount = computed(() => batchRows.value.filter(row => row.gender !== '未知').length)
const batchApplyCount = computed(() => batchRows.value.filter(row => row.apply && row.gender !== '未知').length)

onMounted(() => load({ resetPage: true }))
let requestSerial = 0
let activeRequestKey = ''
let activeRequest = null
function load(options = {}) {
  if (options.resetPage) page.value = 0
  if (Number.isInteger(options.page) && options.page >= 0) page.value = options.page
  const params = { keyword: filters.keyword, gender: filters.gender, reviewed: filters.reviewed, page: page.value, size: pageSize }
  const requestKey = JSON.stringify(params)
  if (activeRequest && activeRequestKey === requestKey) return activeRequest
  const sequence = ++requestSerial
  loading.value = artists.value.length === 0
  refreshing.value = artists.value.length > 0
  loadError.value = ''
  activeRequestKey = requestKey
  const request = (async () => {
    try {
      const result = await api.adminArtistPage(params)
      if (sequence !== requestSerial) return result
      const nextArtists = Array.isArray(result?.items) ? result.items : []
      artists.value = nextArtists
      total.value = Number(result?.total) || 0
      page.value = Number(result?.page) >= 0 ? Number(result.page) : page.value
      const keys = new Set(nextArtists.map(item => item.artistKey || item.name))
      selectedKeys.value = new Set([...selectedKeys.value].filter(key => keys.has(key)))
      return result
    } catch (e) {
      if (sequence === requestSerial) loadError.value = e.message || '歌手列表加载失败'
      return undefined
    } finally {
      if (sequence === requestSerial) {
        loading.value = false
        refreshing.value = false
      }
    }
  })()
  activeRequest = request
  return request.finally(() => {
    if (activeRequest === request) {
      activeRequest = null
      activeRequestKey = ''
    }
  })
}
function reset() { Object.assign(filters, { keyword: '', gender: '', reviewed: '' }); load({ resetPage: true }) }
function toggleArtist(artist, checked) { const key = artist.artistKey || artist.name; const next = new Set(selectedKeys.value); checked ? next.add(key) : next.delete(key); selectedKeys.value = next }
function toggleAll(checked) { selectedKeys.value = checked ? new Set(artists.value.map(item => item.artistKey || item.name)) : new Set() }
function openReview(artist) { selected.value = artist; suggestion.value = null; reviewGender.value = artist.gender || '未知'; reviewOpen.value = true }
function closeReview() { if (!analyzing.value && !saving.value) reviewOpen.value = false }
async function analyze() { if (!selected.value || !await ensureAiConfigured()) return; analyzing.value = true; try { suggestion.value = await api.adminAnalyzeArtist(selected.value.name); reviewGender.value = suggestion.value.gender || '未知' } catch (e) { await alertDialog(e.message || '歌手分析失败') } finally { analyzing.value = false } }
async function apply() { if (!selected.value) return; saving.value = true; try { await api.adminApplyArtistGender(selected.value.name, reviewGender.value); reviewOpen.value = false; await load() } catch (e) { await alertDialog(e.message || '歌手类型保存失败') } finally { saving.value = false } }
async function openBatch() {
  const selectedArtists = artists.value.filter(item => selectedKeys.value.has(item.artistKey || item.name)).map(item => item.name)
  const names = (selectedArtists.length ? selectedArtists : artists.value.filter(item => !item.reviewed && item.artistKind === 'PERSON').map(item => item.name)).slice(0, 500)
  if (!names.length) { await alertDialog('当前筛选结果中没有待复核歌手，请勾选需要重新分析的歌手。'); return }
  const config = await ensureAiConfigured()
  if (!config) return
  const concurrency = Math.max(1, Math.min(Number(config.bulkConcurrency) || 1, 10))
  batchOpen.value = true; batchAnalyzing.value = true; batchRows.value = []; batchProgress.value = 0; batchTotal.value = names.length
  try {
    for (let offset = 0; offset < names.length; offset += concurrency) {
      const chunk = names.slice(offset, offset + concurrency)
      try {
        const analyzed = await api.adminAnalyzeArtists(chunk)
        const byArtist = new Map((analyzed || []).map(row => [row.artist, row]))
        batchRows.value = [...batchRows.value, ...chunk.map(artist => ({ artist, ...(byArtist.get(artist) || { gender:'未知', confidence:0, source:'LOCAL', reason:'服务未返回该歌手的分析结果' }), apply:(byArtist.get(artist)?.gender || '未知') !== '未知' }))]
      } catch (e) {
        batchRows.value = [...batchRows.value, ...chunk.map(artist => ({ artist, gender:'未知', confidence:0, source:'LOCAL', reason:e.message || '批量分析失败', apply:false }))]
      }
      batchProgress.value += chunk.length
    }
  } finally { batchAnalyzing.value = false }
}
async function ensureAiConfigured() {
  return resolveAiConfiguration(
    () => api.adminAiConfig(),
    {
      onError: message => alertDialog(message),
      onRedirect: () => router.push({ name:'admin-settings', query:{ section:'ai' } })
    }
  )
}
function closeBatch() { if (!batchAnalyzing.value && !batchSaving.value) batchOpen.value = false }
async function applyBatch() { const rows = batchRows.value.filter(row => row.apply && row.gender !== '未知'); if (!rows.length) return; batchSaving.value = true; batchSaveProgress.value = 0; try { for (const row of rows) { await api.adminApplyArtistGender(row.artist, row.gender); batchSaveProgress.value++ } batchOpen.value = false; selectedKeys.value = new Set(); await load() } catch (e) { await alertDialog(e.message || '批量写入失败') } finally { batchSaving.value = false } }
function confidenceClass(value) { return value >= 0.8 ? 'high' : value >= 0.5 ? 'medium' : 'low' }
</script>

<style scoped>
.page-head{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:18px}.title-block,.head-actions,.action-button,.toolbar>div,.modal-kicker{display:flex;align-items:center}.title-block{gap:11px}.title-mark{display:grid;width:38px;height:38px;place-items:center;border:1px solid #bfdbfe;border-radius:8px;background:#eff6ff;color:#2563eb}.page-head h1{font-size:22px;line-height:1.2}.page-head p{margin-top:6px;color:#64748b;font-size:12px}.head-actions{gap:8px}.action-button{justify-content:center;gap:6px}.stats-row{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:16px}.stats-row article{padding:12px 14px;border:1px solid #e2e8f0;border-radius:8px;background:#fff}.stats-row span,.stats-row small{display:block;color:#64748b;font-size:11px}.stats-row strong{display:block;margin:5px 0 2px;color:#172033;font-size:22px;line-height:1}.stats-row small{color:#94a3b8;font-size:10px}.filter-panel{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap;padding:14px 16px;margin-bottom:16px;border:1px solid #e2e8f0;border-radius:8px;background:#fff}.filter-panel label{display:flex;flex:0 0 150px;flex-direction:column;gap:6px;color:#64748b;font-size:11px}.filter-panel .keyword-field{flex-basis:250px}.filter-panel input,.filter-panel select,.gender-editor select,.batch-table select{height:35px;width:100%;padding:0 10px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#172033;font:inherit;font-size:12px}.filter-panel select,.gender-editor select{appearance:none;padding-right:30px}.select-control{position:relative;display:block}.select-control svg{position:absolute;right:9px;top:50%;color:#64748b;pointer-events:none;transform:translateY(-50%)}.filter-actions{display:flex;gap:8px}.table-panel{overflow:hidden;border:1px solid #e2e8f0;border-radius:8px;background:#fff}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:13px 16px;border-bottom:1px solid #e2e8f0}.toolbar>div{gap:10px}.toolbar strong{color:#172033;font-size:13px}.toolbar span,.toolbar small{color:#94a3b8;font-size:11px}.table-scroll,.batch-table-wrap{overflow:auto}table{width:100%;min-width:980px;border-collapse:separate;border-spacing:0}th,td{padding:11px 12px;border-bottom:1px solid #eef2f7;text-align:left;white-space:nowrap}th{background:#f8fafc;color:#64748b;font-size:11px}td{color:#334155;font-size:12px}td strong,td small{display:block}td small{margin-top:4px;color:#94a3b8;font-size:10px}.check-cell{width:42px;text-align:center}.check-cell input{width:15px;height:15px;accent-color:#2563eb}.count{font-size:14px}.samples{display:block;max-width:430px;overflow:hidden;color:#64748b;text-overflow:ellipsis}.action-cell{position:sticky;right:0;z-index:2;width:150px;min-width:150px;background:#fff;border-left:1px solid #e2e8f0;box-shadow:-10px 0 14px -14px rgba(15,23,42,.55)}th.action-cell{z-index:3;background:#f8fafc}.artist-cell{display:flex;align-items:center;gap:8px}.artist-avatar{display:grid;width:32px;height:32px;flex:none;place-items:center;overflow:hidden;border-radius:50%;background:#eff6ff;color:#2563eb;font-size:13px;font-weight:700}.artist-avatar img{width:100%;height:100%;object-fit:cover}.kind-note{color:#a16207!important}.load-error{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 16px;border-bottom:1px solid #fed7aa;background:#fff7ed;color:#9a3412;font-size:11px}.pagination{display:flex;align-items:center;justify-content:center;gap:12px;padding:12px 16px;border-top:1px solid #e2e8f0;background:#f8fafc;color:#64748b;font-size:11px}.status,.confidence{display:inline-flex;padding:3px 8px;border-radius:4px;font-size:10px;font-weight:600}.status.blue{background:#dbeafe;color:#1d4ed8}.status.amber{background:#fef3c7;color:#a16207}.confidence.high{background:#dcfce7;color:#15803d}.confidence.medium{background:#fef3c7;color:#a16207}.confidence.low{background:#f1f5f9;color:#64748b}.link,.primary,.secondary{display:inline-flex;align-items:center;justify-content:center;min-height:34px;padding:0 11px;border-radius:6px;font-size:11px;font-weight:600}.link{gap:5px;border:1px solid #dbe3ee;background:#fff;color:#2563eb}.primary{border:1px solid #2563eb;background:#2563eb;color:#fff}.secondary{border:1px solid #cbd5e1;background:#fff;color:#475569}.empty{text-align:center;color:#94a3b8;padding:45px}.mask{position:fixed;inset:0;z-index:100;display:grid;place-items:center;padding:20px;background:rgba(15,23,42,.48)}.modal{width:min(620px,calc(100vw - 24px));max-height:calc(100vh - 36px);display:flex;flex-direction:column;overflow:hidden;border-radius:8px;background:#fff;box-shadow:0 20px 55px rgba(15,23,42,.22)}.batch-modal{width:min(860px,calc(100vw - 24px))}.modal-head{display:flex;align-items:flex-start;justify-content:space-between;padding:18px 20px;border-bottom:1px solid #e2e8f0}.modal-head h2{margin-top:5px;font-size:17px}.modal-head p{margin-top:5px;color:#64748b;font-size:11px}.modal-kicker{gap:5px;color:#2563eb;font-size:10px;font-weight:700}.icon-button{display:grid;width:32px;height:32px;flex:none;place-items:center;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#475569}.modal-body{overflow:auto;padding:18px 20px}.batch-summary{display:flex;gap:18px;padding:13px 20px;border-bottom:1px solid #e2e8f0;color:#64748b;font-size:11px}.batch-table{min-width:760px}.batch-table td{vertical-align:middle}.batch-table select{width:100px;height:31px;padding:0 7px;font-size:11px}.reason-cell{display:block;max-width:380px;overflow:hidden;color:#64748b;text-overflow:ellipsis;white-space:nowrap}.batch-loading{display:grid;place-items:center;gap:8px;min-height:220px;color:#2563eb}.batch-loading strong{color:#172033;font-size:14px}.batch-loading span{color:#94a3b8;font-size:11px}.review-summary{display:grid;grid-template-columns:1fr 1fr;gap:10px}.review-summary div{padding:11px 12px;border:1px solid #e2e8f0;border-radius:6px;background:#f8fafc}.review-summary span,.review-summary strong{display:block}.review-summary span{color:#94a3b8;font-size:10px}.review-summary strong{margin-top:4px;color:#1d4ed8;font-size:17px}.reason{margin:12px 0;color:#64748b;font-size:11px;line-height:1.5}.sample-head{display:flex;justify-content:space-between;padding:12px 0 8px;border-top:1px solid #e2e8f0}.sample-head small{color:#94a3b8;font-size:10px}.sample-list{display:grid;grid-template-columns:1fr 1fr;gap:8px}.sample{display:flex;align-items:center;gap:8px;min-width:0;padding:8px;border:1px solid #eef2f7;border-radius:6px}.sample .cover{display:grid;width:36px;height:36px;flex:none;place-items:center;overflow:hidden;border-radius:5px;background:#eff6ff;color:#2563eb}.sample .cover img{width:100%;height:100%;object-fit:cover}.sample>div:last-child{min-width:0}.sample strong,.sample small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.sample strong{font-size:11px}.sample small{margin-top:3px;color:#94a3b8;font-size:9px}.gender-editor{display:flex;align-items:center;gap:12px;margin-top:16px;color:#475569;font-size:11px}.gender-editor .select-control{width:140px}.modal-actions{display:flex;justify-content:flex-end;gap:8px;padding:13px 20px;border-top:1px solid #e2e8f0;background:#f8fafc}.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:760px){.page-head{align-items:flex-start;flex-direction:column}.head-actions{width:100%}.head-actions>*{flex:1}.stats-row{grid-template-columns:1fr 1fr}.stats-row article:last-child{grid-column:1/-1}.filter-panel label,.filter-panel .keyword-field{flex:1 1 140px}.filter-actions{width:100%}.filter-actions>*{flex:1}.toolbar{align-items:flex-start;flex-direction:column}.sample-list{grid-template-columns:1fr}.modal-actions{flex-wrap:wrap}.modal-actions>*{flex:1}.batch-summary{gap:10px;flex-wrap:wrap}}
</style>
