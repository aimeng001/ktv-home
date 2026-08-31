<template>
  <div class="page">
    <header class="top"><button @click="$router.back()">‹</button><strong>分类浏览</strong><span></span></header>
    <!-- 标签切换栏 / Tab switcher -->
    <div class="tabs"><button v-for="item in tabs" :key="item.key" :class="{ on: tab === item.key }" @click="tab = item.key">{{ item.label }}</button></div>
    <main>
      <!-- 歌手模式 / Artists mode -->
      <template v-if="tab === 'artists'">
        <div class="gender-filter"><button v-for="item in genderOptions" :key="item.value" :class="{on:artistGender===item.value}" @click="artistGender=item.value">{{ item.label }}</button></div>
        <!-- 首字母索引 / Initial letter index -->
        <div class="letters"><button v-for="letter in availableLetters" :key="letter" :class="{ on: activeLetter === letter }" @click="activeLetter = letter">{{ letter }}</button></div>
        <div v-if="loading && !filteredArtists.length" class="tip">加载中…</div>
        <div v-else-if="loadError && !filteredArtists.length" class="tip error-tip">歌手读取失败：{{ loadError.message || '网络错误，请稍后重试' }} <button class="retry" @click="retry">重试</button></div>
        <!-- 歌手列表 / Artist list -->
        <div v-else-if="filteredArtists.length" class="artist-list"><router-link v-for="artist in filteredArtists" :key="artist.artistKey || artist.name" :to="{ name:'artist', params:{ name:artist.name }, query:{ artistKey:artist.artistKey || '' } }"><span class="avatar"><img v-if="avatarUrlFor(artist, avatarFailures)" :src="avatarUrlFor(artist, avatarFailures)" :alt="`${artist.name}头像`" @error="rememberAvatar(artist)" /><span v-else>{{ artist.name.slice(0,1) }}</span></span><span><strong>{{ artist.name }}</strong><small>{{ artist.songCount }} 首</small></span><em>›</em></router-link></div>
        <div v-else class="tip">暂无{{ activeGenderLabel }}</div>
        <div v-if="loadError && filteredArtists.length" class="tip error-tip">后续歌手读取失败：{{ loadError.message || '网络错误，请稍后重试' }} <button class="retry" @click="retry">重试</button></div>
        <div v-if="filteredArtists.length && hasMoreArtists" class="load-more"><button :disabled="loading" @click="loadMoreArtists">{{ loading ? '加载中…' : '加载更多' }}</button></div>
      </template>
      <!-- 语种/分类模式 / Languages & tags mode -->
      <template v-else>
        <div v-if="loading" class="tip">加载中…</div>
        <div v-else-if="loadError" class="tip error-tip">分类读取失败：{{ loadError.message || '网络错误，请稍后重试' }} <button class="retry" @click="retry">重试</button></div>
        <!-- 卡片网格 / Card grid -->
        <div v-else-if="currentItems.length" class="cards"><router-link v-for="item in currentItems" :key="item.name" :to="songLink(item)"><span>{{ iconFor(item.name) }}</span><strong>{{ item.name }}</strong><small>{{ item.songCount }} 首</small></router-link></div>
        <div v-else class="tip">暂无分类</div>
      </template>
    </main>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 分类浏览页 — 按歌手首字母、语种或分类标签浏览歌曲库。
 *
 * Category browse page — browse the song library by artist initial,
 * language, or category tag.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api/client'
import { useAsyncResource } from '../composables/useAsyncResource'
import TabBar from '../components/TabBar.vue'
import { avatarUrlFor, rememberAvatarFailure } from './artistAvatarState'

const route = useRoute()
/** 顶部标签页：歌手 / 语种 / 分类。Top tabs: artists / languages / tags. */
const tabs = [{ key:'artists',label:'歌手' },{ key:'languages',label:'语种' },{ key:'tags',label:'分类' }]
const tab = ref(route.query.tab || 'artists'), activeLetter = ref('热门')
const artistGender = ref('')
const genderOptions = [{value:'',label:'全部歌手'},{value:'男歌手',label:'男歌手'},{value:'女歌手',label:'女歌手'},{value:'组合',label:'组合'}]
const artistsResource = useAsyncResource(() => api.browseArtistPage({
  gender: artistGender.value,
  initial: activeLetter.value === '热门' ? '' : activeLetter.value,
  page: artistPage.value,
  size: 30
}), { items: [], total: 0, page: 0, size: 30 })
const artistInitialsResource = useAsyncResource(() => api.browseArtistInitials(artistGender.value), [])
const languagesResource = useAsyncResource(() => api.browseLanguages(), [])
const tagsResource = useAsyncResource(() => api.browseTags(), [])
const artistPage = ref(0)
const artistTotal = ref(0)
const artistRows = ref([])
const artists = computed(() => artistRows.value)
const artistInitials = computed(() => Array.isArray(artistInitialsResource.data.value) ? artistInitialsResource.data.value : [])
const languages = languagesResource.data
const tags = tagsResource.data
const avatarFailures = ref(new Set())
const currentResource = computed(() => tab.value === 'artists' ? artistsResource : tab.value === 'languages' ? languagesResource : tagsResource)
const loading = computed(() => tab.value === 'artists'
  ? artistsResource.status.value === 'loading' || artistInitialsResource.status.value === 'loading'
  : currentResource.value.status.value === 'loading')
const loadError = computed(() => tab.value === 'artists'
  ? artistsResource.error.value || artistInitialsResource.error.value
  : currentResource.value.error.value)
onMounted(load); watch(tab, load)
watch(artistGender, () => {
  const alreadyHot = activeLetter.value === '热门'
  activeLetter.value = '热门'
  if (tab.value === 'artists') {
    if (alreadyHot) loadArtistPage(true)
    artistInitialsResource.load()
  }
})
watch(activeLetter, () => {
  if (tab.value === 'artists') loadArtistPage(true)
})

/**
 * 按当前标签页懒加载数据，每个分类只请求一次。
 *
 * Lazy-loads data for the active tab; each category is fetched at most once.
 */
async function load() {
  if (tab.value === 'artists') {
    await Promise.all([loadArtistPage(true), artistInitialsResource.load()])
    return
  }
  const resource = currentResource.value
  if (resource.status.value === 'idle' || resource.status.value === 'error') await resource.load()
}
function retry() {
  if (tab.value === 'artists') {
    return Promise.all([loadArtistPage(true), artistInitialsResource.load()])
  }
  return currentResource.value.load()
}
async function loadArtistPage(reset) {
  if (reset) {
    artistPage.value = 0
    artistRows.value = []
    artistTotal.value = 0
  }
  const requestedPage = artistPage.value
  const result = await artistsResource.load()
  if (!result || requestedPage !== artistPage.value) return result
  const nextItems = Array.isArray(result.items) ? result.items : []
  artistRows.value = reset ? nextItems : [...artistRows.value, ...nextItems]
  artistTotal.value = Number.isFinite(result.total) ? result.total : 0
  return result
}
async function loadMoreArtists() {
  if (loading.value || !hasMoreArtists.value) return
  artistPage.value += 1
  await loadArtistPage(false)
}
function rememberAvatar(artist) { avatarFailures.value = rememberAvatarFailure(avatarFailures.value, artist) }

/** 可选首字母列表，热门置顶。Available initials, with "热门" pinned first. */
const activeGenderLabel = computed(() => genderOptions.find(item => item.value === artistGender.value)?.label || '歌手')
const availableLetters = computed(() => ['热门', ...artistInitials.value.filter(letter => letter !== '热门')])

/** 服务端已经按首字母和热门排序分页。The server filters and pages this list. */
const filteredArtists = computed(() => artists.value)
const hasMoreArtists = computed(() => filteredArtists.value.length < artistTotal.value)

/** 当前标签页对应的数据列表。Data list for the current tab. */
const currentItems = computed(() => tab.value === 'languages' ? languages.value : tags.value)

/**
 * 根据语种/标签生成歌曲列表路由链接。
 *
 * Builds a route link to the song list filtered by language or tag.
 * @param {{ name: string }} item
 * @returns {{ name: string, params: object, query: object }}
 */
function songLink(item) { return { name:'artist', params:{ name:'all' }, query:{ mode:tab.value === 'languages' ? 'language' : 'tag', value:item.name } } }

/**
 * 根据类别名称返回对应图标。
 *
 * Returns an emoji icon for the given category name.
 * @param {string} name
 * @returns {string}
 */
function iconFor(name) { if (/国语|粤语|英语|日语|韩语/.test(name)) return '🌏'; if (/儿歌|儿童/.test(name)) return '🧸'; if (/摇滚/.test(name)) return '🎸'; if (/情歌/.test(name)) return '💞'; return '🎶' }
</script>

<style scoped>
.page{min-height:100vh;padding-bottom:74px}.top{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 14px;border-bottom:1px solid var(--line)}.top button{border:0;background:none;color:var(--text);font-size:30px;width:35px}.top span{width:35px}.tabs{display:flex;padding:10px 16px 0;border-bottom:1px solid var(--line)}.tabs button{flex:1;border:0;background:none;color:var(--dim);padding:10px;border-bottom:2px solid transparent}.tabs button.on{color:var(--gold);border-color:var(--gold)}main{padding:13px 16px}.gender-filter,.letters{display:flex;gap:6px;overflow-x:auto;padding-bottom:10px}.gender-filter button,.letters button{flex:none;border:1px solid var(--glass-border);background:var(--panel2);color:var(--dim);border-radius:8px;padding:6px 10px}.gender-filter button.on,.letters button.on{color:var(--gold);border-color:rgba(240,199,66,.3);background:var(--gold-glow)}.artist-list{background:var(--panel2);border:1px solid var(--glass-border);border-radius:14px;padding:0 12px}.artist-list a{display:flex;align-items:center;gap:11px;padding:10px 0;border-bottom:1px solid var(--line);color:var(--text)}.artist-list a:last-child{border-bottom:0}.avatar{width:40px;height:40px;display:grid;place-items:center;border-radius:50%;background:var(--gold-glow);color:var(--gold);font-weight:800;overflow:hidden}.avatar img{width:100%;height:100%;object-fit:cover;display:block}.artist-list a>span:nth-child(2){display:flex;flex:1;flex-direction:column;gap:3px}.artist-list small{color:var(--dim2)}.artist-list em{font-style:normal;color:var(--dim2);font-size:22px}.cards{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.cards a{display:flex;flex-direction:column;gap:6px;padding:16px;border:1px solid var(--glass-border);background:var(--panel2);border-radius:14px;color:var(--text)}.cards a>span{font-size:24px}.cards strong{font-size:14px}.cards small{color:var(--dim2)}.tip{text-align:center;color:var(--dim2);padding:50px}.error-tip{color:var(--coral)}.retry{margin-left:7px;border:1px solid var(--glass-border);border-radius:7px;padding:4px 9px;color:var(--gold);background:var(--panel2)}.load-more{text-align:center;padding:12px 0}.load-more button{border:1px solid var(--glass-border);border-radius:8px;padding:8px 18px;color:var(--gold);background:var(--panel2)}.load-more button:disabled{opacity:.6}
</style>
