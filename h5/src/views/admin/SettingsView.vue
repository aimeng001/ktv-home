<template>
  <AdminLayout active="settings">
    <div class="settings-page">
      <header class="page-head">
        <div><h1>系统设置</h1><p>按类别管理服务、AI 模型、入库转码与 TV 展示</p></div>
        <span class="page-context">{{ categories.length }} 个配置分类</span>
      </header>
      <div class="settings-shell">
        <aside class="settings-nav">
          <div class="search-wrap"><Search :size="16" /><input v-model="search" class="search" placeholder="搜索设置" aria-label="搜索设置" /></div>
          <div v-if="search" class="search-results">
            <button v-for="item in searchResults" :key="item.key" @click="jump(item)"><span><strong>{{ item.label }}</strong><small>{{ item.category }}</small></span><ChevronRight :size="14" /></button>
            <span v-if="!searchResults.length" class="empty">没有匹配设置</span>
          </div>
          <nav v-else class="category-nav">
            <button v-for="item in categories" :key="item.key" class="category-button" :class="{active: section === item.key}" @click="selectSection(item.key)">
              <span class="category-icon"><component :is="item.icon" :size="17" /></span>
              <span class="category-copy"><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
              <ChevronRight :size="15" class="chevron" />
            </button>
          </nav>
        </aside>

        <main class="settings-content">
        <section v-show="section === 'basic'" class="section" id="section-basic">
          <SectionHead title="基础配置" description="扫描路径和局域网访问相关选项"><SlidersHorizontal :size="19" /></SectionHead>
          <div class="setting-group">
            <div class="group-head"><strong>曲库路径</strong><span>管理源文件监听和正式曲库目录</span></div>
            <SettingRow id="library_watch_enabled" label="源目录自动扫描" hint="关闭后只在仪表盘手动扫描"><Toggle v-model="form.library_watch_enabled" /></SettingRow>
            <SettingRow label="扫描源目录"><span class="readonly path-value">/source-music</span></SettingRow>
            <SettingRow label="KTV 曲库目录"><span class="readonly path-value">/music</span></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>外部曲库音频默认</strong><span>只用于外部只读曲库中新建索引的文件；单曲设置优先</span></div>
            <SettingRow id="external_default_audio_layout" label="默认 AudioLayout" hint="DUAL_CHANNEL 默认 Original = LEFT、Accompaniment = RIGHT"><select v-model="form.external_default_audio_layout" class="input"><option value="NORMAL_STEREO">普通立体声</option><option value="DUAL_TRACK">双独立音轨</option><option value="DUAL_CHANNEL">单音轨左右声道</option></select></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>局域网访问</strong><span>控制电视端展示的手机点歌地址</span></div>
            <SettingRow id="display_address" label="二维码展示地址" hint="留空时使用当前访问地址"><input v-model="form.display_address" class="input" placeholder="192.168.1.10:8080" /></SettingRow>
          </div>
        </section>

        <section v-show="section === 'ai'" class="section ai-section" id="section-ai">
          <SectionHead title="AI 模型" description="支持任意 OpenAI-compatible 服务，未配置时继续使用本地解析"><Bot :size="19" /><template #aside><span class="source-pill" :class="{ok: ai.apiKeyConfigured && ai.enabled}"><i></i>{{ ai.enabled && ai.apiKeyConfigured ? '服务可用' : '未配置' }}</span></template></SectionHead>
          <div class="ai-status"><div><span>配置来源</span><strong>{{ sourceLabel(ai.sources?.bulk_model) }}</strong></div><div><span>最近测试</span><strong>{{ ai.lastTestAt ? formatTime(ai.lastTestAt) : '尚未测试' }}</strong></div><div><span>JSON 模式</span><strong>{{ ai.jsonMode || 'AUTO' }}</strong></div></div>
          <div class="setting-group">
            <div class="group-head"><strong>服务连接</strong><span>兼容 Chat Completions 的服务地址与鉴权</span></div>
            <SettingRow id="ai_enabled" label="启用 AI" hint="AI 故障不会中断扫描、转码和入库"><Toggle v-model="aiForm.enabled" /></SettingRow>
            <SettingRow label="服务预设" hint="仅填充地址，所有字段仍可修改"><div class="presets"><button v-for="preset in presets" :key="preset.name" class="preset" @click="applyPreset(preset)">{{ preset.name }}</button></div></SettingRow>
            <SettingRow id="ai_base_url" label="API Base URL" hint="填写完整 API 前缀"><input v-model="aiForm.baseUrl" class="input wide" placeholder="https://api.example.com/v1" /></SettingRow>
            <SettingRow id="ai_api_key" label="API Key" hint="留空保留已配置的 Key"><div class="key-control"><input v-model="aiForm.apiKey" class="input wide" type="password" placeholder="输入新的 API Key" /><div class="key-meta" v-if="ai.apiKeyConfigured"><span class="key-tail">已配置 · ****{{ ai.apiKeySuffix }}</span><button class="text-btn danger" @click="clearKey = !clearKey">{{ clearKey ? '取消清除' : '清除 Key' }}</button></div></div></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>任务模型</strong><span>分别承担批量分析和歧义复核</span></div>
            <SettingRow id="ai_bulk_model" label="批量模型 ID" hint="可填写任意兼容服务的模型名"><div class="model-control"><input v-model="aiForm.bulkModel" class="input wide" placeholder="例如 deepseek-chat / gpt-4o-mini" /><button class="btn ghost small" @click="loadModels('bulk')" :disabled="testing">获取模型</button></div></SettingRow>
            <SettingRow id="ai_reasoning_model" label="增强模型 ID" hint="留空时使用批量模型"><div class="model-control"><input v-model="aiForm.reasoningModel" class="input wide" placeholder="可选" /><button class="btn ghost small" @click="loadModels('reasoning')" :disabled="testing">获取模型</button></div></SettingRow>
            <div v-if="models.length" class="model-list"><span class="model-list-label">{{ modelTarget === 'reasoning' ? '增强模型候选' : '批量模型候选' }}</span><button v-for="model in models" :key="model" type="button" @click="chooseModel(model)">{{ model }}</button></div>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>高级设置</strong><span>输出格式、超时、阈值和任务并发</span></div>
            <SettingRow id="ai_json_mode" label="JSON 模式"><select v-model="aiForm.jsonMode" class="input"><option value="AUTO">自动检测</option><option value="FORCE">强制 response_format</option><option value="PROMPT_ONLY">仅提示词约束</option></select></SettingRow>
            <SettingRow id="ai_timeout" label="请求超时"><div class="unit-input"><input v-model.number="aiForm.timeoutSeconds" class="input short" type="number" min="5" max="600" /><span>秒</span></div></SettingRow>
            <SettingRow id="ai_threshold" label="自动应用阈值" hint="低于阈值的结果进入人工审核"><div class="thresholds"><label><span>身份</span><input v-model.number="aiForm.identityThreshold" class="input short" type="number" min="0" max="1" step="0.01" /></label><label><span>分类</span><input v-model.number="aiForm.classificationThreshold" class="input short" type="number" min="0" max="1" step="0.01" /></label></div></SettingRow>
            <SettingRow label="任务并发数"><div class="thresholds"><label><span>批量</span><input v-model.number="aiForm.bulkConcurrency" class="input short" type="number" min="1" max="10" /></label><label><span>增强</span><input v-model.number="aiForm.reasoningConcurrency" class="input short" type="number" min="1" max="5" /></label></div></SettingRow>
          </div>
          <div class="section-actions"><button class="btn" @click="testAi" :disabled="testing"><TestTube2 :size="15" />{{ testing ? '测试中…' : '测试连接与模型' }}</button><span v-if="testMessage" class="test-message">{{ testMessage }}</span></div>
        </section>

        <section v-show="section === 'metadata'" class="section" id="section-metadata">
          <SectionHead title="音乐元数据" description="为 KTV 曲库刮削歌名、歌手、专辑、发行时间和封面"><Music2 :size="19" /><template #aside><span class="source-pill" :class="{ok: musicForm.enabled && musicForm.providers.length}"><i></i>{{ musicForm.enabled && musicForm.providers.length ? '已启用' : '默认关闭' }}</span></template></SectionHead>
          <div class="setting-group">
            <div class="group-head"><strong>刮削来源</strong><span>仅用于补全 KTV 曲库，不提供在线搜歌或播放</span></div>
            <SettingRow id="music_sources_enabled" label="启用元数据刮削"><Toggle v-model="musicForm.enabled" /></SettingRow>
            <SettingRow id="music_sources_providers" label="刮削平台" hint="批量任务会从已选平台匹配最可靠的候选"><Checks v-model="musicForm.providers" :options="musicProviderOptions" /></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>请求与缓存</strong><span>限制单次请求规模，减少上游压力</span></div>
            <SettingRow id="music_sources_limit" label="每个平台搜索数量"><div class="unit-input"><input v-model.number="musicForm.resultLimit" class="input short" type="number" min="1" max="50" /><span>条</span></div></SettingRow>
            <SettingRow id="music_sources_timeout" label="请求超时"><div class="unit-input"><input v-model.number="musicForm.timeoutSeconds" class="input short" type="number" min="2" max="30" /><span>秒</span></div></SettingRow>
            <SettingRow id="music_sources_cache" label="搜索缓存"><div class="unit-input"><input v-model.number="musicForm.searchCacheHours" class="input short" type="number" min="1" max="168" /><span>小时</span></div></SettingRow>
            <SettingRow id="music_sources_threshold" label="自动写入阈值" hint="达到该置信度的结果自动写入，低于阈值进入人工审核"><div class="unit-input"><input v-model.number="musicForm.autoApplyThreshold" class="input short" type="number" min="0.5" max="1" step="0.01" /><span>{{ Math.round(musicForm.autoApplyThreshold * 100) }}%</span></div></SettingRow>
            <SettingRow id="music_sources_concurrency" label="单平台并发上限" hint="批量刮削时，每个平台同时进行的请求数"><div class="unit-input"><input v-model.number="musicForm.concurrencyLimit" class="input short" type="number" min="1" max="4" /><span>个</span></div></SettingRow>
            <SettingRow id="music_sources_interval" label="同平台请求间隔" hint="即使提高并发，同一平台的请求仍按此间隔发出"><div class="unit-input"><input v-model.number="musicForm.requestIntervalMs" class="input short" type="number" min="500" max="30000" step="100" /><span>毫秒</span></div></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>平台状态</strong><span>平台故障不会影响其它来源和本地曲库</span></div>
            <div v-for="item in musicStatus" :key="item.provider" class="provider-health">
              <span class="health-dot" :class="{ok:item.healthy}"></span>
              <div><strong>{{ item.displayName }}</strong><small v-if="item.lastSuccessAt">最近成功 {{ formatTime(item.lastSuccessAt) }}</small><small v-else-if="item.lastError">{{ item.lastError }}</small><small v-else>尚未测试</small></div>
              <button class="btn ghost small" :disabled="testingMusic" @click="testMusic([item.provider])">测试连接</button>
            </div>
          </div>
          <div class="section-actions"><button class="btn" :disabled="testingMusic || !musicForm.providers.length" @click="testMusic(musicForm.providers)"><TestTube2 :size="15" />{{ testingMusic ? '测试中…' : '测试已选平台' }}</button><span v-if="musicTestMessage" class="test-message">{{ musicTestMessage }}</span></div>
        </section>

        <section v-show="section === 'transcode'" class="section" id="section-transcode">
          <SectionHead title="入库与转码" description="管理直拷规则、转码输出与源文件清理"><Database :size="19" /></SectionHead>
          <div v-if="libraryMode !== 'MANAGED'" class="readonly-notice">{{ libraryMode === 'EXTERNAL_READ_ONLY' ? '当前使用外部只读曲库；入库、删除和转码设置不适用于该模式。' : '曲库模式尚未确认；为保护源文件，入库、删除和转码设置暂不可用。' }}</div>
          <template v-else>
          <div class="setting-group warning-group">
            <div class="group-head"><strong>源文件清理</strong><span>仅作用于管理员手动启动的转码任务</span></div>
            <SettingRow id="delete_source_after_transcode" label="转码成功后删除源文件" hint="默认关闭；完成入库与校验后才会逐首删除"><Toggle v-model="form.delete_source_after_transcode" /></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><div><strong>视频直拷条件</strong><span>同时满足容器、视频和音频编码时无需转码</span></div><button class="text-btn" @click="restoreTranscodeDefaults">恢复默认</button></div>
            <SettingRow label="容器白名单"><Checks v-model="form.direct_copy_containers" :options="containerOptions" /></SettingRow>
            <SettingRow label="视频编码白名单"><Checks v-model="form.direct_copy_video_codecs" :options="videoOptions" /></SettingRow>
            <SettingRow label="音频编码白名单"><Checks v-model="form.direct_copy_audio_codecs" :options="audioOptions" /></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>转码输出</strong><span>不满足直拷条件时使用以下输出格式</span></div>
            <SettingRow label="纯音频文件转码"><Toggle v-model="form.transcode_audio_only" /></SettingRow>
            <SettingRow label="输出容器"><select v-model="form.transcode_output_container" class="input"><option value="mkv">MKV</option><option value="mp4">MP4</option></select></SettingRow>
            <SettingRow label="输出视频编码"><select v-model="form.transcode_video_codec" class="input"><option value="h264">H.264</option><option value="hevc">H.265 / HEVC</option></select></SettingRow>
            <SettingRow label="输出音频编码"><select v-model="form.transcode_audio_codec" class="input"><option value="aac">AAC</option><option value="mp3">MP3</option><option value="opus">Opus</option></select></SettingRow>
            <SettingRow label="硬件加速" :hint="hardwareStatusText"><Toggle v-model="form.transcode_hardware_acceleration" /></SettingRow>
          </div>
          </template>
        </section>

        <section v-show="section === 'tv'" class="section" id="section-tv">
          <SectionHead title="TV 显示" description="待机页、视频画面和防烧屏设置"><Tv :size="19" /></SectionHead>
          <div class="setting-group">
            <div class="group-head"><strong>播放画面</strong><span>控制视频比例和播放页附加信息</span></div>
            <SettingRow id="tv_video_scale_mode" label="视频画面模式"><select v-model="form.tv_video_scale_mode" class="input"><option value="zoom">铺满（等比裁切）</option><option value="fit">原画（完整显示）</option><option value="fill">拉伸（充满屏幕）</option></select></SettingRow>
            <SettingRow label="播放页迷你二维码"><Toggle v-model="form.mini_qr" /></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>待机轮播</strong><span>设置无人播放时的歌曲内容和轮播节奏</span></div>
            <SettingRow label="待机热门轮播"><Toggle v-model="form.standby_carousel" /></SettingRow>
            <SettingRow id="standby_source" label="待机内容来源"><select v-model="form.standby_source" class="input"><option value="mixed">热门与新歌混合</option><option value="hot">热门歌曲</option><option value="new">最近入库</option><option value="custom">指定歌曲</option></select></SettingRow>
            <SettingRow v-if="form.standby_source === 'custom'" id="standby_song_ids" label="指定歌曲 ID" hint="用逗号分隔多个歌曲 ID"><input :value="(form.standby_song_ids || []).join(',')" class="input wide" @input="setStandbySongIds($event.target.value)" /></SettingRow>
            <SettingRow label="防烧屏微移"><Toggle v-model="form.anti_burn" /></SettingRow>
            <SettingRow label="轮播间隔"><div class="unit-input"><input v-model.number="form.standby_interval_sec" class="input short" type="number" min="3" max="60" /><span>秒</span></div></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>待机品牌</strong><span>欢迎语和 Logo 会显示在 TV 待机页</span></div>
            <SettingRow label="待机欢迎语"><input v-model="form.standby_welcome" class="input wide" /></SettingRow>
            <SettingRow label="欢迎语副标题"><textarea v-model="form.standby_subtitle" class="input wide" rows="2"></textarea></SettingRow>
            <SettingRow id="standby_logo" label="待机 Logo" :hint="form.standby_logo_path ? '已配置自定义 Logo' : '使用默认 Logo'"><label class="upload-btn">上传图片<input type="file" accept="image/png,image/jpeg,image/webp" @change="uploadStandbyLogo" /></label></SettingRow>
          </div>
        </section>

        <section v-show="section === 'maintenance'" class="section" id="section-maintenance">
          <SectionHead title="数据维护" description="存量曲库修复、心愿和系统信息"><Wrench :size="19" /></SectionHead>
          <div class="setting-group">
            <div class="group-head"><strong>曲库维护</strong><span>修复元数据并处理待办内容</span></div>
            <SettingRow label="存量曲库修复" hint="修复歌名、歌手、语种、演唱形式、年代和主题"><router-link class="btn ghost small" :to="{name:'admin-ktv-library'}">前往 KTV 曲库</router-link></SettingRow>
            <SettingRow label="未处理心愿" hint="来自手机点歌端的缺歌反馈"><span class="metric-value">{{ wishes.length }}<small>条</small></span></SettingRow>
          </div>
          <div class="setting-group">
            <div class="group-head"><strong>系统信息</strong><span>当前部署版本</span></div>
            <SettingRow label="应用版本"><span class="readonly">{{ releaseLabel(releaseInfo) }}</span></SettingRow>
            <SettingRow label="使用范围"><span class="readonly">家庭局域网自用</span></SettingRow>
          </div>
        </section>
        </main>
      </div>
      <div v-if="dirty" class="save-bar"><span><AlertCircle :size="16" />有未保存的修改</span><button class="btn ghost" @click="resetChanges"><RotateCcw :size="14" />放弃修改</button><button class="btn" @click="saveAll"><Save :size="14" />保存设置</button></div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch, nextTick } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  AlertCircle, Bot, ChevronRight, Database, RotateCcw, Save, Search,
  SlidersHorizontal, TestTube2, Tv, Wrench, Music2
} from 'lucide-vue-next'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog, confirmDialog } from '../../composables/useDialog'
import { canonicalizeSettings, releaseLabel, saveDirtySections } from './settingsState'

const route = useRoute(); const router = useRouter()
const categories = [
  { key: 'basic', label: '基础配置', description: '路径与访问', icon: SlidersHorizontal },
  { key: 'ai', label: 'AI 模型', description: '服务与能力', icon: Bot },
  { key: 'metadata', label: '音乐元数据', description: '在线平台与缓存', icon: Music2 },
  { key: 'transcode', label: '入库与转码', description: '格式与源文件', icon: Database },
  { key: 'tv', label: 'TV 显示', description: '待机与播放', icon: Tv },
  { key: 'maintenance', label: '数据维护', description: '修复与清理', icon: Wrench }
]
const search = ref(''); const section = ref(route.query.section && categories.some(x => x.key === route.query.section) ? route.query.section : 'basic')
const form = reactive({ library_watch_enabled:false, display_address:'', external_default_audio_layout:'NORMAL_STEREO', delete_source_after_transcode:false, tv_video_scale_mode:'zoom', standby_carousel:true, standby_source:'mixed', standby_song_ids:[], standby_logo_path:'', anti_burn:true, mini_qr:true, standby_welcome:'今晚开唱', standby_subtitle:'手机点歌，电视欢唱\n一家人的客厅 KTV', standby_interval_sec:8, direct_copy_containers:['mp4','m4v','mkv'], direct_copy_video_codecs:['h264','hevc'], direct_copy_audio_codecs:['aac','mp3'], transcode_audio_only:false, transcode_output_container:'mkv', transcode_video_codec:'h264', transcode_audio_codec:'aac', transcode_hardware_acceleration:false })
const ai = reactive({ enabled:false, apiKeyConfigured:false, apiKeySuffix:null, sources:{}, capabilities:{}, lastTestAt:null })
const aiForm = reactive({ enabled:false, baseUrl:'', apiKey:'', bulkModel:'', reasoningModel:'', timeoutSeconds:60, identityThreshold:.97, classificationThreshold:.92, jsonMode:'AUTO', bulkConcurrency:2, reasoningConcurrency:1 })
const musicForm = reactive({enabled:false,providers:[],resultLimit:20,timeoutSeconds:5,searchCacheHours:6,concurrencyLimit:1,requestIntervalMs:1500,autoApplyThreshold:.95})
const musicStatus = ref([]); const musicProviderOptions=[{value:'NETEASE',label:'网易云音乐'},{value:'QQ',label:'QQ 音乐'},{value:'KUGOU',label:'酷狗音乐'}]
const original = ref(''); const aiOriginal = ref(''); const musicOriginal = ref(''); const dirty = ref(false); const loading = ref(true); const testing = ref(false); const testingMusic=ref(false); const testMessage = ref(''); const musicTestMessage=ref(''); const models = ref([]); const modelTarget = ref('bulk'); const wishes = ref([]); const clearKey = ref(false)
const hardware = reactive({ available:false, reason:'尚未检测' })
const releaseInfo = reactive({ version:'' })
const libraryMode = ref(null)
const presets = [{name:'DeepSeek',baseUrl:'https://api.deepseek.com/v1'},{name:'OpenAI',baseUrl:'https://api.openai.com/v1'},{name:'Ollama',baseUrl:'http://localhost:11434/v1'},{name:'自定义',baseUrl:''}]
const containerOptions=['mp4','m4v','mkv','mov','ts','m2ts','mts','mpg','mpeg','vob','avi','webm','wmv','asf','flv','f4v','3gp','3g2','rm','rmvb'].map(value=>({value,label:value.toUpperCase()}))
const videoOptions=['h264','hevc','mpeg2video','mpeg4','vp8','vp9','av1','vc1','wmv3','wmv2','theora','prores','dnxhd','mjpeg','dvvideo','h263','rawvideo'].map(value=>({value,label:value.toUpperCase()}))
const audioOptions=['aac','mp3','mp2','ac3','eac3','dts','truehd','flac','alac','opus','vorbis','ape','wmav1','wmav2','pcm_s16le','pcm_s24le','pcm_s32le','pcm_f32le'].map(value=>({value,label:value.toUpperCase()}))
const SectionHead = { props:['title','description'], setup(props,{slots}) { return () => h('div',{class:'section-head'},[h('div',{class:'section-heading'},[h('span',{class:'section-icon'},slots.default?.()),h('div',[h('h2',props.title),h('p',props.description)])]),slots.aside?h('div',{class:'section-aside'},slots.aside()):null]) } }
const SettingRow = { props:['id','label','hint'], setup(props,{slots}) { return () => h('div',{id:props.id,class:'setting-row'},[h('div',{class:'setting-label'},[h('strong',props.label),props.hint?h('small',props.hint):null]),h('div',{class:'setting-value'},slots.default?.())]) } }
const Toggle = { props:['modelValue'], emits:['update:modelValue'], setup(props,{emit}) { return () => h('button',{type:'button',class:['switch',props.modelValue?'on':''],onClick:()=>emit('update:modelValue',!props.modelValue),'aria-label':props.modelValue?'已开启':'已关闭'},[h('span')]) } }
const Checks = { props:['modelValue','options'], emits:['update:modelValue'], setup(props,{emit}) { const toggle=item=>emit('update:modelValue',props.modelValue.includes(item.value)?props.modelValue.filter(v=>v!==item.value):[...props.modelValue,item.value]); return () => h('div',{class:'checks'},props.options.map(item=>{const selected=props.modelValue.includes(item.value);return h('button',{type:'button',key:item.value,class:['check-chip',selected?'selected':''],onClick:()=>toggle(item),'aria-pressed':selected},[h('span',{class:'check-mark'},selected?'✓':''),item.label])})) } }
const searchCatalog = {
  basic: [
    { key:'library_watch_enabled', label:'源目录自动扫描', keywords:'监听 扫描 文件' },
    { key:'display_address', label:'二维码展示地址', keywords:'局域网 手机 点歌 IP' },
    { key:'external_default_audio_layout', label:'外部曲库默认 AudioLayout', keywords:'NAS 外部 只读 原唱 伴唱 声道 音轨' }
  ],
  ai: [
    { key:'ai_enabled', label:'启用 AI', keywords:'模型 智能识别' },
    { key:'ai_base_url', label:'API Base URL', keywords:'接口 地址 OpenAI compatible' },
    { key:'ai_api_key', label:'API Key', keywords:'密钥 鉴权 token' },
    { key:'ai_bulk_model', label:'批量模型 ID', keywords:'模型列表 批量' },
    { key:'ai_reasoning_model', label:'增强模型 ID', keywords:'推理 复核 歧义' },
    { key:'ai_json_mode', label:'JSON 模式', keywords:'response format 输出' },
    { key:'ai_timeout', label:'请求超时', keywords:'秒 timeout' },
    { key:'ai_threshold', label:'自动应用阈值', keywords:'置信度 审核 并发' }
  ],
  metadata: [
    { key:'music_sources_enabled', label:'启用元数据刮削', keywords:'KTV 曲库 标签 聚合' },
    { key:'music_sources_providers', label:'刮削平台', keywords:'网易云 QQ 酷狗 多选' },
    { key:'music_sources_limit', label:'每个平台搜索数量', keywords:'结果 条数 limit' },
    { key:'music_sources_timeout', label:'请求超时', keywords:'平台 timeout 秒' },
    { key:'music_sources_cache', label:'搜索缓存', keywords:'小时 缓存 刷新' },
    { key:'music_sources_threshold', label:'自动写入阈值', keywords:'置信度 自动应用 人工审核' },
    { key:'music_sources_concurrency', label:'单平台并发上限', keywords:'限流 并发 速度' },
    { key:'music_sources_interval', label:'同平台请求间隔', keywords:'限速 频率 毫秒' }
  ],
  transcode: [
    { key:'delete_source_after_transcode', label:'转码成功后删除源文件', keywords:'删源 清理 自动删除' },
    { key:'section-transcode', label:'视频直拷与转码输出', keywords:'容器 编码 白名单 硬件加速' }
  ],
  tv: [
    { key:'tv_video_scale_mode', label:'视频画面模式', keywords:'比例 铺满 原画 拉伸' },
    { key:'standby_source', label:'待机内容来源', keywords:'热门 新歌 轮播' },
    { key:'standby_song_ids', label:'指定待机歌曲', keywords:'歌曲 ID' },
    { key:'standby_logo', label:'待机 Logo', keywords:'品牌 上传图片 欢迎语' }
  ],
  maintenance: [{ key:'section-maintenance', label:'存量曲库修复', keywords:'歌名 歌手 语种 心愿 数据维护' }]
}
const searchItems = computed(() => categories.flatMap(c => (searchCatalog[c.key] || []).map(item => ({...item,category:c.label,section:c.key,description:c.description}))))
const searchResults = computed(() => { const q=search.value.trim().toLowerCase(); return q?searchItems.value.filter(x=>(x.label+' '+x.description+' '+x.key+' '+x.keywords).toLowerCase().includes(q)):[] })
const hardwareStatusText = computed(() => hardware.available ? '硬件编码可用' : (hardware.reason || '未检测到硬件编码器'))
function snapshot(v){ return JSON.stringify(v) }
const generalDirty = computed(() => snapshot(form) !== original.value)
const aiDirty = computed(() => snapshot(aiForm) !== aiOriginal.value)
const musicDirty = computed(() => snapshot(musicForm) !== musicOriginal.value)
function selectSection(value){ section.value=value; router.replace({query:{...route.query,section:value}}) }
function jump(item){ selectSection(item.section); nextTick(()=>document.getElementById(item.key)?.scrollIntoView({behavior:'smooth',block:'center'})) }
function sourceLabel(value){ return value==='DATABASE'?'管理后台':value==='ENVIRONMENT'?'环境变量':value==='NONE'?'未配置':'默认值' }
function formatTime(value){ return value ? new Date(value).toLocaleString('zh-CN',{hour12:false}) : '' }
function applyPreset(p){ if(p.baseUrl) aiForm.baseUrl=p.baseUrl }
async function load(){ loading.value=true; const [settings,config,music,hw,w,mode,release] = await Promise.all([api.adminGetSettings().catch(()=>({})),api.adminAiConfig().catch(()=>({})),api.adminMusicSourceConfig().catch(()=>({})),api.adminTranscodeHardware().catch(e=>({reason:e.message})),api.adminWishes().catch(()=>[]),api.adminSourceLibrary({page:0,size:1}).catch(()=>({})),api.releaseInfo().catch(()=>({}))]); Object.assign(form,canonicalizeSettings(settings)); Object.assign(ai,config); Object.assign(aiForm,{enabled:config.enabled,baseUrl:config.baseUrl,bulkModel:config.bulkModel,reasoningModel:config.reasoningModel,timeoutSeconds:config.timeoutSeconds,identityThreshold:config.identityThreshold,classificationThreshold:config.classificationThreshold,jsonMode:config.jsonMode||'AUTO',bulkConcurrency:config.bulkConcurrency||2,reasoningConcurrency:config.reasoningConcurrency||1}); Object.assign(musicForm,{enabled:music.enabled||false,providers:music.providers||[],resultLimit:music.resultLimit||20,timeoutSeconds:music.timeoutSeconds||5,searchCacheHours:music.searchCacheHours||6,concurrencyLimit:music.concurrencyLimit||1,requestIntervalMs:music.requestIntervalMs||1500,autoApplyThreshold:music.autoApplyThreshold??.95});musicStatus.value=music.providerStatus||[]; Object.assign(hardware,hw); wishes.value=w; libraryMode.value=mode.libraryMode||null; Object.assign(releaseInfo,release); original.value=snapshot(form); aiOriginal.value=snapshot(aiForm); musicOriginal.value=snapshot(musicForm); dirty.value=false; loading.value=false }
watch([form,aiForm,musicForm],()=>{ if(!loading.value) dirty.value=snapshot(form)!==original.value||snapshot(aiForm)!==aiOriginal.value||snapshot(musicForm)!==musicOriginal.value },{deep:true})
async function saveAll(){ const {successes,failures}=await saveDirtySections([{name:'基础设置',dirty:generalDirty.value,save:async()=>{const updated=await api.adminPutSettings({...form});Object.assign(form,canonicalizeSettings(updated));return updated}},{name:'AI 设置',dirty:aiDirty.value,save:async()=>{const config=await api.adminAiPutConfig({...aiForm,apiKey:aiForm.apiKey||null,clearApiKey:clearKey.value});Object.assign(ai,config);aiForm.apiKey='';clearKey.value=false;return config}},{name:'音乐元数据设置',dirty:musicDirty.value,save:async()=>{const music=await api.adminPutMusicSourceConfig({...musicForm});Object.assign(musicForm,{enabled:music.enabled,providers:music.providers,resultLimit:music.resultLimit,timeoutSeconds:music.timeoutSeconds,searchCacheHours:music.searchCacheHours,concurrencyLimit:music.concurrencyLimit,requestIntervalMs:music.requestIntervalMs,autoApplyThreshold:music.autoApplyThreshold});musicStatus.value=music.providerStatus||[];return music}}]); for(const item of successes){if(item.name==='基础设置')original.value=snapshot(form);if(item.name==='AI 设置')aiOriginal.value=snapshot(aiForm);if(item.name==='音乐元数据设置')musicOriginal.value=snapshot(musicForm)} dirty.value=generalDirty.value||aiDirty.value||musicDirty.value; if(failures.length){const saved=successes.map(item=>item.name).join('、')||'无';const failed=failures.map(item=>item.name).join('、');await alertDialog(`已保存：${saved}。保存失败：${failed}。失败部分仍保留为未保存状态。`,{title:'设置保存不完整',tone:'warning'})} }
function resetChanges(){ Object.assign(form,JSON.parse(original.value)); Object.assign(aiForm,JSON.parse(aiOriginal.value)); Object.assign(musicForm,JSON.parse(musicOriginal.value)); dirty.value=false }
function chooseModel(model){ if(modelTarget.value==='reasoning') aiForm.reasoningModel=model; else aiForm.bulkModel=model }
async function loadModels(target='bulk'){ modelTarget.value=target; try { models.value=(await api.adminAiModels()).models||[] } catch(e){ await alertDialog(e.message||'模型列表获取失败') } }
async function testAi(){ testing.value=true; try { const result=await api.adminAiTestConfig(); testMessage.value=`连接成功，已检测 ${Object.keys(result.testedModels||{}).length} 个模型`; Object.assign(ai,await api.adminAiConfig()) } catch(e){ testMessage.value=e.message||'连接测试失败' } finally { testing.value=false } }
async function testMusic(providers){testingMusic.value=true;try{const result=await api.adminTestMusicSources(providers);musicStatus.value=result.providerStatus||musicStatus.value;const success=(result.results||[]).filter(item=>item.healthy).length;musicTestMessage.value=`${success}/${(result.results||[]).length} 个平台连接成功`}catch(e){musicTestMessage.value=e.message||'平台连接测试失败'}finally{testingMusic.value=false}}
async function restoreTranscodeDefaults(){ if(!await confirmDialog('恢复转码默认规则？',{title:'恢复默认'}))return; const result=await api.adminResetTranscodeDefaults(); Object.assign(form,result) }
function setStandbySongIds(value){ form.standby_song_ids=[...new Set(value.split(/[,，\s]+/).map(Number).filter(id=>Number.isInteger(id)&&id>0))] }
async function uploadStandbyLogo(event){ const file=event.target.files?.[0]; if(!file)return; try{await api.adminUploadStandbyLogo(file); Object.assign(form,canonicalizeSettings(await api.adminGetSettings())); original.value=snapshot(form)}catch(e){await alertDialog(e.message||'Logo 上传失败')}finally{event.target.value=''} }
onMounted(load)
function beforeUnload(e){ if(dirty.value){e.preventDefault();e.returnValue=''} }
onMounted(()=>window.addEventListener('beforeunload',beforeUnload)); onBeforeUnmount(()=>window.removeEventListener('beforeunload',beforeUnload))
onBeforeRouteLeave(async()=>{ if(!dirty.value)return true; return await confirmDialog('有未保存的设置，确定离开吗？',{title:'未保存修改',tone:'warning'}) })
</script>

<style scoped>
.settings-page{width:100%;max-width:1180px;margin:0 auto;padding-bottom:76px;color:#172033}
.page-head{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:20px}
.page-head h1{font-size:22px;line-height:1.25;color:#172033}
.page-head p{margin-top:6px;color:#64748b;font-size:13px;line-height:1.5}
.page-context{flex:none;margin-bottom:1px;padding:5px 9px;border:1px solid #dbe3ee;border-radius:6px;background:#fff;color:#64748b;font-size:11px}
.settings-shell{display:grid;grid-template-columns:240px minmax(0,1fr);gap:18px;align-items:start}
.settings-nav{position:sticky;top:18px;min-width:0;padding:10px;background:#fff;border:1px solid #dfe6ef;border-radius:8px;box-shadow:0 1px 2px rgba(15,23,42,.03)}
.search-wrap{position:relative}
.search-wrap>svg{position:absolute;left:11px;top:50%;transform:translateY(-50%);color:#94a3b8;pointer-events:none}
.search{width:100%;height:38px;padding:0 10px 0 34px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#172033;font-size:12px}
.search:focus,.input:focus{border-color:#60a5fa;box-shadow:0 0 0 3px rgba(37,99,235,.1);outline:0}
.category-nav,.search-results{display:flex;flex-direction:column;gap:4px;margin-top:10px}
.category-button{display:grid;grid-template-columns:32px minmax(0,1fr) 16px;align-items:center;gap:9px;width:100%;min-height:58px;padding:8px;border:1px solid transparent;border-radius:6px;text-align:left;color:#475569}
.category-button:hover,.search-results button:hover{background:#f8fafc;color:#1e293b}
.category-button.active{border-color:#bfdbfe;background:#eff6ff;color:#1d4ed8}
.category-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:6px;background:#f1f5f9;color:#64748b}
.category-button.active .category-icon{background:#dbeafe;color:#2563eb}
.category-copy{display:block;min-width:0}
.category-copy strong,.category-copy small{display:block;letter-spacing:0}
.category-copy strong{font-size:12px;line-height:1.4}
.category-copy small{margin-top:3px;color:#94a3b8;font-size:10px;line-height:1.35}
.category-button.active .category-copy small{color:#60a5fa}
.chevron{color:#94a3b8}
.search-results button{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;padding:10px;border-radius:6px;text-align:left;color:#475569}
.search-results button span,.search-results strong,.search-results small{display:block}
.search-results strong{font-size:12px}.search-results small{margin-top:3px;color:#94a3b8;font-size:10px}
.empty{display:block;padding:18px 10px;text-align:center;color:#94a3b8;font-size:12px}
.settings-content{min-width:0}
.section{overflow:hidden;background:#fff;border:1px solid #dfe6ef;border-radius:8px;box-shadow:0 1px 2px rgba(15,23,42,.03)}
.section-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:19px 20px;border-bottom:1px solid #e8edf3;background:#fbfcfe}
.section-head :deep(.section-heading){display:flex;align-items:center;gap:11px;min-width:0}
.section-head :deep(.section-icon){display:grid;place-items:center;width:36px;height:36px;flex:none;border-radius:7px;background:#eaf2ff;color:#2563eb}
.section-head :deep(h2){font-size:17px;line-height:1.35;color:#172033}
.section-head :deep(p){margin-top:4px;color:#64748b;font-size:11px;line-height:1.5}
.section-head :deep(.section-aside){display:flex;align-items:center;min-height:36px;flex:none}
.source-pill{display:inline-flex;align-items:center;gap:6px;padding:5px 9px;border:1px solid #e2e8f0;border-radius:999px;background:#f8fafc;color:#64748b;font-size:10px;white-space:nowrap}
.source-pill i{width:6px;height:6px;border-radius:50%;background:#94a3b8}
.source-pill.ok{border-color:#bbf7d0;background:#f0fdf4;color:#166534}.source-pill.ok i{background:#16a34a}
.ai-status{display:grid;grid-template-columns:repeat(3,1fr);margin:16px 20px 0;border:1px solid #e2e8f0;border-radius:7px;background:#f8fafc}
.ai-status>div{min-width:0;padding:10px 12px;border-right:1px solid #e2e8f0}.ai-status>div:last-child{border-right:0}
.ai-status span,.ai-status strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.ai-status span{color:#94a3b8;font-size:10px}.ai-status strong{margin-top:4px;color:#334155;font-size:11px}
.setting-group{margin:18px 20px 0}
.setting-group:last-child{margin-bottom:20px}
.group-head{display:flex;align-items:flex-end;justify-content:space-between;gap:18px;padding:0 0 9px;border-bottom:1px solid #dfe6ef}
.group-head>div{min-width:0}.group-head strong,.group-head span{display:block}
.group-head strong{color:#1e293b;font-size:12px}.group-head span{margin-top:3px;color:#94a3b8;font-size:10px;line-height:1.4}
.warning-group .group-head{border-bottom-color:#fed7aa}.warning-group .group-head strong{color:#9a3412}
.setting-row{display:grid;grid-template-columns:minmax(190px,250px) minmax(0,1fr);align-items:center;gap:24px;min-height:62px;padding:10px 0;border-bottom:1px solid #eef2f7}
.setting-group .setting-row:last-child{border-bottom:0}
.setting-row :deep(.setting-label){min-width:0}.setting-row :deep(.setting-label strong),.setting-row :deep(.setting-label small){display:block;letter-spacing:0}
.setting-row :deep(.setting-label strong){color:#334155;font-size:12px;line-height:1.45}
.setting-row :deep(.setting-label small){max-width:270px;margin-top:4px;color:#94a3b8;font-size:10px;line-height:1.5}
.setting-row :deep(.setting-value){display:flex;align-items:center;justify-content:flex-start;gap:9px;min-width:0}
.input{width:min(250px,100%);height:36px;padding:0 10px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#172033;font-size:12px;line-height:1.4;box-shadow:0 1px 2px rgba(15,23,42,.03)}
textarea.input{height:auto;min-height:58px;padding:8px 10px;resize:vertical}
.input.wide{width:min(440px,100%)}.input.short{width:76px;flex:none}
.ai-section .input.wide{width:min(560px,100%)}
.readonly{color:#64748b;font-size:11px}.path-value{padding:5px 8px;border-radius:5px;background:#f1f5f9;color:#475569;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
.metric-value{color:#1d4ed8;font-size:20px;font-weight:700}.metric-value small{margin-left:4px;color:#64748b;font-size:10px;font-weight:500}
.switch{position:relative;width:42px;height:24px;flex:none;border-radius:999px;background:#cbd5e1;transition:background .18s}
.switch :deep(span){position:absolute;top:3px;left:3px;width:18px;height:18px;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(15,23,42,.22);transition:transform .18s}
.switch.on{background:#2563eb}.switch.on :deep(span){transform:translateX(18px)}
.key-control{display:grid;grid-template-columns:minmax(0,560px);justify-items:start;width:100%}
.key-control .input{width:100%}
.key-meta{display:flex;align-items:center;justify-content:flex-start;gap:10px;margin-top:7px}.key-tail{color:#15803d;font-size:10px}
.model-control{display:flex;align-items:center;justify-content:flex-start;gap:8px;width:100%;max-width:560px}.model-control .input{width:auto;min-width:0;flex:1}
.presets,.thresholds{display:flex;align-items:center;justify-content:flex-start;gap:8px;flex-wrap:wrap}
.thresholds label{display:flex;align-items:center;gap:6px;color:#64748b;font-size:10px}.thresholds label span{white-space:nowrap}
.unit-input{display:flex;align-items:center;gap:7px;color:#64748b;font-size:11px}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:36px;padding:0 14px;border-radius:6px;background:#2563eb;color:#fff;box-shadow:none;font-size:12px;font-weight:600;white-space:nowrap}
.btn:hover:not(:disabled){background:#1d4ed8}.btn:disabled{cursor:not-allowed;opacity:.5}
.btn.ghost{background:#fff;color:#475569;border:1px solid #cbd5e1}.btn.ghost:hover:not(:disabled){background:#f8fafc;color:#172033;border-color:#94a3b8}
.btn.small{min-height:32px;padding:0 11px;font-size:11px}
.text-btn{flex:none;color:#2563eb;font-size:11px;font-weight:600}.text-btn:hover{color:#1d4ed8}.text-btn.danger{color:#b91c1c}
.preset,.model-list button{min-height:30px;padding:0 10px;border:1px solid #cbd5e1;border-radius:5px;background:#fff;color:#475569;font-size:10px}
.preset:hover,.model-list button:hover{border-color:#60a5fa;background:#eff6ff;color:#1d4ed8}
.model-list{display:flex;flex-wrap:wrap;justify-content:flex-start;gap:6px;margin-left:274px;padding:0 0 10px}.model-list-label{width:100%;color:#94a3b8;font-size:10px;line-height:1.4;text-align:left}
.checks{display:flex;flex-wrap:wrap;justify-content:flex-start;gap:6px;max-width:520px}
.checks :deep(.check-chip){display:inline-flex;align-items:center;gap:5px;min-height:28px;padding:0 8px;border:1px solid #dbe3ee;border-radius:5px;background:#fff;color:#64748b;font-size:10px}
.checks :deep(.check-chip:hover){border-color:#93c5fd;color:#1d4ed8}.checks :deep(.check-chip.selected){border-color:#93c5fd;background:#eff6ff;color:#1d4ed8;font-weight:600}
.checks :deep(.check-mark){display:grid;place-items:center;width:13px;height:13px;border:1px solid #cbd5e1;border-radius:3px;background:#fff;color:#fff;font-size:9px;line-height:1}
.checks :deep(.check-chip.selected .check-mark){border-color:#2563eb;background:#2563eb}
.section-actions{display:flex;align-items:center;gap:12px;margin:18px 20px 20px;padding-top:16px;border-top:1px solid #dfe6ef}.test-message{color:#15803d;font-size:11px}
.provider-health{display:grid;grid-template-columns:10px minmax(0,1fr) auto;align-items:center;gap:10px;min-height:54px;border-bottom:1px solid #eef2f7}.provider-health:last-child{border-bottom:0}.provider-health strong,.provider-health small{display:block}.provider-health strong{font-size:12px;color:#334155}.provider-health small{margin-top:3px;color:#94a3b8;font-size:10px}.health-dot{width:7px;height:7px;border-radius:50%;background:#cbd5e1}.health-dot.ok{background:#16a34a}
.upload-btn{display:inline-flex;align-items:center;min-height:34px;padding:0 12px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#2563eb;font-size:11px;font-weight:600;cursor:pointer}.upload-btn:hover{background:#f8fafc}.upload-btn input{display:none}
.save-bar{position:fixed;right:24px;bottom:16px;z-index:50;display:flex;align-items:center;justify-content:flex-end;gap:10px;width:min(720px,calc(100vw - 292px));padding:10px 12px;border:1px solid #334155;border-radius:8px;background:#172033;color:#fff;box-shadow:0 10px 30px rgba(15,23,42,.24)}
.save-bar>span{display:flex;align-items:center;gap:7px;margin-right:auto;font-size:11px}.save-bar .btn{min-height:34px}.save-bar .btn.ghost{background:transparent;border-color:#64748b;color:#e2e8f0}.save-bar .btn.ghost:hover{background:#334155;color:#fff}
@media(max-width:1040px){
  .settings-shell{grid-template-columns:218px minmax(0,1fr);gap:14px}
  .setting-row{grid-template-columns:minmax(160px,210px) minmax(0,1fr);gap:18px}
  .model-list{margin-left:228px}
}
@media(max-width:900px){
  .settings-shell{grid-template-columns:1fr}
  .settings-nav{position:static}
  .category-nav{display:grid;grid-template-columns:repeat(6,minmax(112px,1fr));overflow-x:auto;padding-bottom:2px}
  .category-button{grid-template-columns:28px minmax(0,1fr);min-height:50px}.category-icon{width:28px;height:28px}.category-button .chevron,.category-copy small{display:none}
  .setting-row{grid-template-columns:1fr;gap:8px;padding:13px 0}
  .setting-row :deep(.setting-label small){max-width:none}
  .setting-row :deep(.setting-value),.model-control,.presets,.thresholds,.checks{width:100%;justify-content:flex-start}
  .input.wide{width:min(560px,100%)}
  .model-list{margin-left:0}
}
@media(max-width:620px){
  .page-head{align-items:flex-start}.page-context{display:none}
  .settings-nav{padding:9px}.category-nav{grid-template-columns:repeat(6,118px)}
  .section-head{padding:16px}.section-head :deep(p){max-width:245px}.section-head :deep(.section-icon){width:34px;height:34px}
  .ai-status{grid-template-columns:1fr;margin:13px 16px 0}.ai-status>div{border-right:0;border-bottom:1px solid #e2e8f0}.ai-status>div:last-child{border-bottom:0}
  .setting-group{margin:16px 16px 0}
  .group-head{align-items:flex-start;flex-direction:column;gap:7px}
  .input,.input.wide{width:100%}.model-control{align-items:stretch;flex-direction:column}.model-control .btn{align-self:flex-start}
  .key-control{grid-template-columns:1fr}.key-meta{justify-content:space-between;width:100%}
  .model-list{justify-content:flex-start}.model-list-label{text-align:left}
  .checks{max-width:none}.checks :deep(.check-chip){min-height:30px}
  .section-actions{align-items:flex-start;flex-direction:column;margin:16px}.save-bar{right:10px;bottom:8px;width:calc(100vw - 20px);flex-wrap:wrap}.save-bar>span{width:100%;margin-bottom:2px}.save-bar .btn{flex:1}
}
</style>
