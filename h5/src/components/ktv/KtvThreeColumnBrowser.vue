<template>
  <section class="browser-root" aria-label="三栏点歌浏览器">
    <!-- 顶部胶囊分类栏（对标 view_ktv_three_column_browser.xml） -->
    <div class="capsule-bar">
      <button type="button" class="back-hall-btn" @click="$emit('back-hall')">
        <ArrowLeft :size="15" /> 大厅
      </button>
      <div class="capsule-scroll">
        <button
          v-for="item in capsuleTabs"
          :key="item.key"
          type="button"
          class="capsule-tab"
          :class="{ on: activeTab === item.key }"
          @click="switchTab(item.key)"
        >
          {{ item.label }}
        </button>
      </div>
      <button type="button" class="kb-mobile-toggle" @click="mobileKbOpen = !mobileKbOpen">
        <Keyboard :size="15" /> {{ mobileKbOpen ? '收起键盘' : '拼音键盘' }}
      </button>
    </div>

    <!-- 主体三栏：左筛选栏 + 中内容格 + 右软键盘 -->
    <div class="three-col-body" :class="{ 'no-kb': !showKeyboard }">
      <!-- 左栏：语境筛选 -->
      <aside class="left-rail" aria-label="子分类筛选">
        <!-- 歌手性别 + 地区 + 首字母 -->
        <template v-if="activeTab === 'singer' && !selectedArtist">
          <div class="rail-group">
            <button
              v-for="g in genderFilters"
              :key="g.value"
              type="button"
              class="rail-btn"
              :class="{ on: artistGender === g.value }"
              @click="setArtistGender(g.value)"
            >
              {{ g.label }}
            </button>
          </div>
          <template v-if="regionDataAvailable || artistRegion">
            <div class="rail-divider" />
            <div class="rail-group">
              <button
                v-for="reg in regionFilters"
                :key="reg.value"
                type="button"
                class="rail-btn"
                :class="{ on: artistRegion === reg.value }"
                @click="setArtistRegion(reg.value)"
              >
                {{ reg.label }}
              </button>
            </div>
          </template>
          <div class="rail-divider" />
          <div class="rail-letters">
            <button
              v-for="letter in availableLetters"
              :key="letter"
              type="button"
              class="letter-chip"
              :class="{ on: artistLetter === letter }"
              @click="setArtistLetter(letter)"
            >
              {{ letter }}
            </button>
          </div>
        </template>

        <!-- 歌手详情下显示返回歌手按钮、格式、唱法与排序 -->
        <template v-else-if="activeTab === 'singer' && selectedArtist">
          <button type="button" class="rail-btn gold" @click="clearSelectedArtist">
            ← 返回歌手列表
          </button>
          <div class="rail-divider" />
          <div class="rail-group">
            <button
              v-for="mf in mediaFilters"
              :key="mf.value"
              type="button"
              class="rail-btn"
              :class="{ on: mediaFilter === mf.value }"
              @click="setMediaFilter(mf.value)"
            >
              {{ mf.label }}
            </button>
          </div>
          <div class="rail-divider" />
          <div class="rail-group">
            <button
              v-for="vf in vocalFormFilters"
              :key="vf.value"
              type="button"
              class="rail-btn"
              :class="{ on: vocalFormFilter === vf.value }"
              @click="setVocalFormFilter(vf.value)"
            >
              {{ vf.label }}
            </button>
          </div>
          <div class="rail-divider" />
          <button
            v-for="s in sortOptions"
            :key="s.value"
            type="button"
            class="rail-btn"
            :class="{ on: songSort === s.value }"
            @click="setSongSort(s.value)"
          >
            {{ s.label }}
          </button>
        </template>

        <!-- 语种 -->
        <template v-else-if="activeTab === 'language'">
          <button
            v-for="lang in languages"
            :key="lang.name"
            type="button"
            class="rail-btn"
            :class="{ on: selectedLanguage === lang.name }"
            @click="selectLanguage(lang.name)"
          >
            <span>{{ lang.name }}</span>
            <small>{{ lang.songCount }}</small>
          </button>
        </template>

        <!-- 主题分类 -->
        <template v-else-if="activeTab === 'theme'">
          <button
            v-for="tag in tags"
            :key="tag.name"
            type="button"
            class="rail-btn"
            :class="{ on: selectedTag === tag.name }"
            @click="selectTag(tag.name)"
          >
            <span>{{ tag.name }}</span>
            <small>{{ tag.songCount }}</small>
          </button>
        </template>

        <!-- 综艺点歌（8 大节目切图预设） -->
        <template v-else-if="activeTab === 'opera'">
          <button
            v-for="preset in varietyPresets"
            :key="preset.key"
            type="button"
            class="variety-btn"
            :class="{ on: selectedVariety === preset.key }"
            @click="selectVariety(preset.key)"
          >
            <img :src="preset.image" :alt="preset.label" />
            <span>{{ preset.label }}</span>
          </button>
        </template>

        <!-- 精选歌单 -->
        <template v-else-if="activeTab === 'featured'">
          <button
            v-for="pl in playlists"
            :key="pl.id"
            type="button"
            class="rail-btn"
            :class="{ on: Number(selectedPlaylistId) === Number(pl.id) }"
            @click="selectPlaylist(pl.id)"
          >
            <span>{{ pl.name }}</span>
            <small>{{ pl.songCount ?? '' }}</small>
          </button>
        </template>

        <!-- 最近唱 -->
        <template v-else-if="activeTab === 'recent'">
          <button type="button" class="rail-btn" :class="{ on: !recentMineOnly }" @click="setRecentMine(false)">
            全部演唱记录
          </button>
          <button type="button" class="rail-btn" :class="{ on: recentMineOnly }" @click="setRecentMine(true)">
            我唱过的歌
          </button>
        </template>

        <!-- 歌名 / 高清 / 热歌新歌 / 本地 / 抖音 / 收藏：榜单模式、格式、唱法与排序 -->
        <template v-else>
          <template v-if="activeTab === 'hot_new'">
            <div class="rail-group">
              <button
                v-for="hm in hotNewModes"
                :key="hm.value"
                type="button"
                class="rail-btn"
                :class="{ on: hotNewMode === hm.value }"
                @click="setHotNewMode(hm.value)"
              >
                {{ hm.label }}
              </button>
            </div>
            <div class="rail-divider" />
          </template>
          <div class="rail-group">
            <button
              v-for="mf in mediaFilters"
              :key="mf.value"
              type="button"
              class="rail-btn"
              :class="{ on: mediaFilter === mf.value }"
              @click="setMediaFilter(mf.value)"
            >
              {{ mf.label }}
            </button>
          </div>
          <div class="rail-divider" />
          <div class="rail-group">
            <button
              v-for="vf in vocalFormFilters"
              :key="vf.value"
              type="button"
              class="rail-btn"
              :class="{ on: vocalFormFilter === vf.value }"
              @click="setVocalFormFilter(vf.value)"
            >
              {{ vf.label }}
            </button>
          </div>
          <template v-if="activeTab !== 'hot_new' || hotNewMode === 'all'">
            <div class="rail-divider" />
            <div class="rail-group">
              <button
                v-for="s in sortOptions"
                :key="s.value"
                type="button"
                class="rail-btn"
                :class="{ on: songSort === s.value }"
                @click="setSongSort(s.value)"
              >
                {{ s.label }}
              </button>
            </div>
          </template>
        </template>
      </aside>

      <!-- 中栏：歌曲 / 歌手 / 歌单 / 历史卡片舞台 -->
      <div class="center-stage">
        <!-- 歌单详情头部 -->
        <div v-if="activeTab === 'featured' && playlistDetail" class="playlist-hero">
          <div class="pl-meta">
            <span v-if="playlistDetail.aiGenerated" class="ai-tag">AI 精选</span>
            <strong>{{ playlistDetail.name }}</strong>
            <small>{{ playlistDetail.description || '家庭欢唱精选歌单' }} · {{ playlistDetail.songs?.length || 0 }} 首</small>
          </div>
          <button
            type="button"
            class="btn small"
            :disabled="orderingPlaylist || !(playlistDetail.songs?.length)"
            @click="orderWholePlaylist"
          >
            {{ orderingPlaylist ? '加入中…' : '▶ 整单点歌' }}
          </button>
        </div>

        <!-- 歌手头部提示 -->
        <div v-if="activeTab === 'singer' && selectedArtist" class="artist-banner">
          <div class="artist-mini-av">
            <img v-if="avatarUrlFor(selectedArtist, avatarFailures)" :src="avatarUrlFor(selectedArtist, avatarFailures)" alt="" @error="rememberAvatar(selectedArtist)" />
            <span v-else>{{ (selectedArtist.name || '?').slice(0, 1) }}</span>
          </div>
          <div>
            <strong>{{ selectedArtist.name }}</strong>
            <small>{{ songTotal }} 首曲目</small>
          </div>
        </div>

        <!-- 加载 / 错误 / 空状态 -->
        <div
          v-if="activeTab === 'favorites' && favorites.loadFailed"
          class="favorites-sync-error"
          role="alert"
        >
          <span>收藏状态同步失败，收藏标记可能不准确</span>
          <button type="button" class="btn ghost small" @click="syncFavorites">重新同步</button>
        </div>
        <div v-if="loading && !hasItems" class="stage-state">加载中…</div>
        <div v-else-if="loadError && !hasItems" class="stage-state error">
          <span>{{ loadError }}</span>
          <button type="button" class="btn ghost small" @click="reloadActive">重试</button>
        </div>

        <!-- 歌手卡片网格 -->
        <div v-else-if="activeTab === 'singer' && !selectedArtist" class="artist-grid">
          <div v-if="!artists.length" class="stage-state">暂无匹配歌手</div>
          <button
            v-for="artist in artists"
            :key="artist.artistKey || artist.name"
            type="button"
            class="artist-card"
            @click="openArtist(artist)"
          >
            <div class="avatar-circle">
              <img
                v-if="avatarUrlFor(artist, avatarFailures)"
                :src="avatarUrlFor(artist, avatarFailures)"
                :alt="artist.name"
                @error="rememberAvatar(artist)"
              />
              <span v-else>{{ (artist.name || '?').slice(0, 1) }}</span>
            </div>
            <strong>{{ artist.name }}</strong>
            <small>{{ artist.songCount }} 首</small>
          </button>
        </div>

        <!-- 最近演唱历史列表 -->
        <div v-else-if="activeTab === 'recent'" class="song-grid">
          <div v-if="!recentItems.length" class="stage-state">{{ recentMineOnly ? '你还没有唱过歌曲' : '今晚还没有演唱记录' }}</div>
          <article v-for="item in recentItems" :key="item.historyId" class="song-card">
            <div class="card-thumb">
              <img
                v-if="songThumbUrl(item.song)"
                :src="songThumbUrl(item.song)"
                alt=""
                loading="lazy"
                @error="rememberSongThumbFailure(item.song)"
              />
              <span v-else>{{ (item.song?.title || '歌').slice(0, 1) }}</span>
            </div>
            <div class="card-info">
              <strong class="card-title">{{ item.song?.title }}</strong>
              <small class="card-sub">{{ item.song?.artist }} · {{ item.playedByNick }} · {{ formatTimeClock(item.playedAt) }}</small>
            </div>
            <button
              type="button"
              class="order-btn"
              :disabled="busyHistoryId === item.historyId"
              @click="repeatHistorySong(item)"
            >
              {{ busyHistoryId === item.historyId ? '加入中' : '再唱' }}
            </button>
          </article>
        </div>

        <!-- 歌曲卡片 3×3 九宫格（含搜索/分类/收藏/歌单歌曲/心愿反馈） -->
        <div v-else class="song-grid">
          <div v-if="!displayedSongs.length" class="stage-state empty-wish">
            <p>{{ keyword.trim() ? `曲库里还没找到“${keyword.trim()}”` : '当前分类暂无歌曲' }}</p>
            <button v-if="keyword.trim()" type="button" class="btn ghost small" @click="submitWish">
              告诉我们想唱《{{ keyword.trim() }}》
            </button>
          </div>
          <article
            v-for="song in displayedSongs"
            :key="song.id"
            class="song-card"
            :class="{ ordered: orderedIds.has(song.id) }"
          >
            <div class="card-thumb">
              <img
                v-if="songThumbUrl(song)"
                :src="songThumbUrl(song)"
                alt=""
                loading="lazy"
                @error="rememberSongThumbFailure(song)"
              />
              <span v-else>{{ (song.artist || song.title || '音').slice(0, 1) }}</span>
            </div>
            <div class="card-info">
              <div class="card-title-row">
                <strong class="card-title" :title="song.title">{{ song.title }}</strong>
                <span class="tag" :class="formatSongTypeBadge(song).cls">{{ formatSongTypeBadge(song).text }}</span>
              </div>
              <small class="card-sub">
                <span class="song-num">No.{{ formatSongNumber(song) }}</span> · {{ song.artist || '未知歌手' }} · {{ formatDurationMs(song.durationMs) }}
              </small>
            </div>
            <div class="card-actions">
              <button
                type="button"
                class="fav-btn"
                :class="{ on: favorites.has(song.id) }"
                :aria-label="favorites.has(song.id) ? '取消收藏' : '收藏'"
                @click="toggleFav(song.id)"
              >
                <Heart :size="15" :fill="favorites.has(song.id) ? 'currentColor' : 'none'" />
              </button>
              <button
                type="button"
                class="top-order-btn"
                :disabled="song.playable === false"
                title="点歌并置顶优先播放"
                @click="orderTopSong(song)"
              >
                置顶
              </button>
              <button
                type="button"
                class="order-btn"
                :class="{ ordered: orderedIds.has(song.id), unavailable: song.playable === false }"
                :disabled="song.playable === false"
                :title="song.playable === false ? unavailableReasonLabel(song.unavailableReason) : ''"
                @click="orderSong(song)"
              >
                {{ song.playable === false ? '准备中' : orderedIds.has(song.id) ? '已点' : '点歌' }}
              </button>
            </div>
          </article>
        </div>

        <!-- 底部分页条 -->
        <footer v-if="supportsPagination" class="pager-bar">
          <button type="button" class="pager-btn" :disabled="currentPage <= 0 || loading" @click="prevPage">
            上一页
          </button>
          <span class="pager-info">
            <template v-if="approximateSearchPager">第 {{ currentPage + 1 }} 页 · 本页 {{ songs.length }} 首</template>
            <template v-else>第 {{ currentPage + 1 }} / {{ totalPages }} 页 · 共 {{ totalCount }} 项</template>
          </span>
          <button type="button" class="pager-btn" :disabled="nextDisabled || loading" @click="nextPage">
            下一页
          </button>
        </footer>
      </div>

      <!-- 右栏：T9 + 全拼软键盘 -->
      <KtvSoftKeyboard
        v-if="showKeyboard"
        ref="keyboardRef"
        v-model="keyword"
        v-model:word-count="wordCount"
        class="right-keyboard"
        :class="{ 'mobile-open': mobileKbOpen }"
        @submit="triggerSearchNow"
      />
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Heart, Keyboard } from 'lucide-vue-next'
import api, { makeControls } from '../../api/client'
import { useFavoritesStore } from '../../stores/favorites'
import { useUserStore } from '../../stores/user'
import { useOrderLock } from '../../composables/useOrderLock'
import { useQueuedSongIds } from '../../composables/useQueuedSongIds'
import { useToast } from '../../composables/useToast'
import { useGlobalTypingCapture } from '../../composables/useGlobalTypingCapture'
import { confirmDialog } from '../../composables/useDialog'
import { createLatestRequest } from '../../views/latestRequest'
import { loadAllFavoritePages } from '../../views/favoritesState'
import { avatarUrlFor, rememberAvatarFailure } from '../../views/artistAvatarState'
import { formatOrderToast } from '../../views/orderFeedbackState'
import { unavailableReasonLabel } from './songAvailability'
import KtvSoftKeyboard from './KtvSoftKeyboard.vue'
import {
  CAPSULE_TABS,
  GENDER_FILTERS,
  HIGH_DEFINITION_TAG,
  MEDIA_FILTERS,
  REGION_FILTERS,
  TIKTOK_TAG,
  VARIETY_PRESETS,
  VOCAL_FORM_FILTERS,
  formatDurationMs,
  formatSongNumber,
  formatSongTypeBadge,
  formatTimeClock,
} from './ktvCatalogPresets'

const props = defineProps({
  initialTab: { type: String, default: 'song_name' },
  initialQuery: { type: String, default: '' },
  initialArtist: { type: Object, default: null },
  initialLanguage: { type: String, default: '' },
  initialTag: { type: String, default: '' },
  initialVocalForm: { type: String, default: '' },
  initialPlaylistId: { type: [Number, String], default: null },
})
defineEmits(['back-hall'])

const user = useUserStore()
const favorites = useFavoritesStore()
const { toast } = useToast()
const { executeOrder, inflightIds } = useOrderLock()
const { orderedIds, player } = useQueuedSongIds(inflightIds)
const controls = makeControls(user.ensureToken(), { onResponse: res => player.applyControlResponse(res) })

const capsuleTabs = CAPSULE_TABS
const genderFilters = GENDER_FILTERS
const regionFilters = REGION_FILTERS
const mediaFilters = MEDIA_FILTERS
const vocalFormFilters = VOCAL_FORM_FILTERS
const varietyPresets = VARIETY_PRESETS
const hotNewModes = [
  { value: 'all', label: '全部热歌' },
  { value: 'ranking', label: '30天飙升榜' },
  { value: 'new_arrivals', label: '最新入库' },
]
const sortOptions = [
  { value: 'hot', label: '按热度' },
  { value: 'title', label: '按歌名' },
  { value: 'new', label: '最新入库' },
]

const PAGE_SIZE_SONGS = 18
const PAGE_SIZE_ARTISTS = 18
const SEARCH_PAGE_SIZE = 50

const activeTab = ref(props.initialTab || 'song_name')
const keyword = ref(props.initialQuery || '')
const wordCount = ref(0)
const mediaFilter = ref('')
const vocalFormFilter = ref(props.initialVocalForm || '')
const songSort = ref('hot')
const hotNewMode = ref('all')
const artistGender = ref('')
const artistRegion = ref('')
const regionDataAvailable = ref(true)
const artistLetter = ref('热门')
const selectedArtist = ref(props.initialArtist || null)
const selectedLanguage = ref(props.initialLanguage || '')
const selectedTag = ref(props.initialTag || '')
const selectedVariety = ref(VARIETY_PRESETS[0].key)
const selectedPlaylistId = ref(props.initialPlaylistId || null)
const recentMineOnly = ref(false)
const mobileKbOpen = ref(false)
const keyboardRef = ref(null)

const loading = ref(false)
const loadError = ref('')
const songs = ref([])
const songTotal = ref(0)
const songPage = ref(0)
const approximateSearchPager = ref(false)
const searchHasMore = ref(false)

const artists = ref([])
const artistTotal = ref(0)
const artistPage = ref(0)
const artistInitials = ref([])
const avatarFailures = ref(new Set())

const languages = ref([])
const tags = ref([])
const playlists = ref([])
const playlistDetail = ref(null)
const orderingPlaylist = ref(false)
const recentItems = ref([])
const busyHistoryId = ref(null)

const reqGate = createLatestRequest()
let debounceTimer = null
let suppressKeywordWatch = false

const showKeyboard = computed(() => !['featured', 'recent', 'favorites'].includes(activeTab.value))

const availableLetters = computed(() => {
  const letters = artistInitials.value.filter(l => l && l !== '#')
  if (artistInitials.value.includes('#')) letters.push('#')
  return ['热门', ...letters]
})

function filterAndSortClientSongs(list) {
  const kw = keyword.value.trim().toLowerCase()
  const filtered = (Array.isArray(list) ? list : []).filter((song) => {
    if (mediaFilter.value && song.mediaType !== mediaFilter.value) return false
    if (vocalFormFilter.value && (song.vocalForm || '') !== vocalFormFilter.value) return false
    if (wordCount.value > 0) {
      const len = (song.title || '').trim().length
      if (wordCount.value < 6 && len !== wordCount.value) return false
      if (wordCount.value >= 6 && len < 6) return false
    }
    if (kw) {
      const hay = [
        song.title,
        song.artist,
        song.titlePinyin,
        song.titleInitials,
        song.artistPinyin,
        song.artistInitials,
        song.pinyin,
        song.pinyinAbbr,
      ].filter(Boolean).join(' ').toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })
  if (activeTab.value === 'favorites') {
    const copy = [...filtered]
    if (songSort.value === 'title') {
      copy.sort((a, b) => String(a.title || '').localeCompare(String(b.title || ''), 'zh-CN'))
    } else if (songSort.value === 'new') {
      copy.sort((a, b) => Number(b.id || 0) - Number(a.id || 0))
    } else if (songSort.value === 'hot') {
      copy.sort((a, b) => Number(b.playCount || 0) - Number(a.playCount || 0))
    }
    return copy
  }
  return filtered
}

const displayedSongs = computed(() => {
  if (activeTab.value === 'featured') return playlistDetail.value?.songs || []
  if (activeTab.value === 'favorites') return filterAndSortClientSongs(songs.value)
  return songs.value
})

const hasItems = computed(() => {
  if (activeTab.value === 'singer' && !selectedArtist.value) return artists.value.length > 0
  if (activeTab.value === 'recent') return recentItems.value.length > 0
  return displayedSongs.value.length > 0
})

const currentPage = computed(() => (activeTab.value === 'singer' && !selectedArtist.value) ? artistPage.value : songPage.value)
const totalCount = computed(() => (activeTab.value === 'singer' && !selectedArtist.value) ? artistTotal.value : songTotal.value)
const pageSize = computed(() => (activeTab.value === 'singer' && !selectedArtist.value) ? PAGE_SIZE_ARTISTS : PAGE_SIZE_SONGS)
const totalPages = computed(() => Math.max(1, Math.ceil((totalCount.value || 0) / pageSize.value)))

const supportsPagination = computed(() => {
  if (['recent', 'favorites', 'featured'].includes(activeTab.value)) return false
  if (activeTab.value === 'hot_new' && hotNewMode.value !== 'all') return false
  if (approximateSearchPager.value) return searchHasMore.value || currentPage.value > 0
  return totalPages.value > 1 || currentPage.value > 0
})

const nextDisabled = computed(() => (
  approximateSearchPager.value
    ? !searchHasMore.value
    : (currentPage.value + 1 >= totalPages.value)
))

function switchTab(nextTab) {
  if (activeTab.value === nextTab && !selectedArtist.value) return
  activeTab.value = nextTab
  songPage.value = 0
  artistPage.value = 0
  wordCount.value = 0
  approximateSearchPager.value = false
  searchHasMore.value = false
  loadError.value = ''
  if (nextTab !== 'singer') selectedArtist.value = null
  if (nextTab === 'hot_new') songSort.value = 'hot'
  reloadActive()
}

function setArtistGender(g) {
  artistGender.value = g
  artistLetter.value = '热门'
  artistPage.value = 0
  loadArtistsAndInitials()
}

function setArtistRegion(region) {
  artistRegion.value = region
  artistLetter.value = '热门'
  artistPage.value = 0
  loadArtistsAndInitials()
}

function setArtistLetter(l) {
  artistLetter.value = l
  artistPage.value = 0
  loadArtistsOnly()
}

function openArtist(artist) {
  selectedArtist.value = artist
  songPage.value = 0
  loadSongsByBrowse()
}

function clearSelectedArtist() {
  selectedArtist.value = null
  artistPage.value = 0
  loadArtistsOnly()
}

function setMediaFilter(val) {
  mediaFilter.value = val
  songPage.value = 0
  if (activeTab.value !== 'favorites') reloadActive()
}

function setVocalFormFilter(val) {
  vocalFormFilter.value = val
  songPage.value = 0
  if (activeTab.value !== 'favorites') reloadActive()
}

function setHotNewMode(mode) {
  if (hotNewMode.value === mode) return
  hotNewMode.value = mode
  songPage.value = 0
  loadHotNewSongs()
}

function setSongSort(val) {
  songSort.value = val
  songPage.value = 0
  if (activeTab.value !== 'favorites') reloadActive()
}

function selectLanguage(name) {
  selectedLanguage.value = name
  songPage.value = 0
  loadSongsByBrowse()
}

function selectTag(name) {
  selectedTag.value = name
  songPage.value = 0
  loadSongsByBrowse()
}

function selectVariety(presetKey) {
  selectedVariety.value = presetKey
  songPage.value = 0
  loadVarietySongs()
}

async function selectPlaylist(id) {
  selectedPlaylistId.value = id
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  try {
    const detail = await api.playlistDetail(id, { signal: req.signal })
    if (!reqGate.isCurrent(req.id)) return
    playlistDetail.value = detail
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    playlistDetail.value = null
    loadError.value = e.status === 404 ? '歌单不存在或未公开' : (e.message || '歌单读取失败')
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

function setRecentMine(mine) {
  recentMineOnly.value = mine
  loadRecentHistory()
}

function prevPage() {
  if (currentPage.value <= 0) return
  if (activeTab.value === 'singer' && !selectedArtist.value) {
    artistPage.value -= 1
    loadArtistsOnly()
  } else {
    songPage.value -= 1
    loadSongsOnly()
  }
}

function nextPage() {
  if (nextDisabled.value) return
  if (activeTab.value === 'singer' && !selectedArtist.value) {
    artistPage.value += 1
    loadArtistsOnly()
  } else {
    songPage.value += 1
    loadSongsOnly()
  }
}

async function reloadActive() {
  const t = activeTab.value
  if (t === 'singer' && !selectedArtist.value) {
    await loadArtistsAndInitials()
    return
  }
  if (t === 'language') {
    if (!await ensureLanguagesLoaded()) {
      songs.value = []
      songTotal.value = 0
      return
    }
    await loadSongsByBrowse()
    return
  }
  if (t === 'theme') {
    if (!await ensureTagsLoaded()) {
      songs.value = []
      songTotal.value = 0
      return
    }
    await loadSongsByBrowse()
    return
  }
  if (t === 'opera') {
    await loadVarietySongs()
    return
  }
  if (t === 'hot_new') {
    await loadHotNewSongs()
    return
  }
  if (t === 'featured') {
    await loadPlaylists()
    return
  }
  if (t === 'favorites') {
    await loadFavorites()
    return
  }
  if (t === 'recent') {
    await loadRecentHistory()
    return
  }
  await loadSongsOnly()
}

function loadSongsOnly() {
  if (activeTab.value === 'hot_new') {
    return loadHotNewSongs()
  }
  if (activeTab.value === 'opera') {
    return loadVarietySongs()
  }
  if ((activeTab.value === 'song_name' || activeTab.value === 'local') && keyword.value.trim() && !vocalFormFilter.value && !wordCount.value) {
    return loadSongsBySearch()
  }
  return loadSongsByBrowse()
}

function normalizeSongSearchResult(res, page) {
  if (Array.isArray(res)) {
    approximateSearchPager.value = true
    searchHasMore.value = res.length >= SEARCH_PAGE_SIZE
    songs.value = res
    songTotal.value = page * SEARCH_PAGE_SIZE + res.length
    return
  }
  approximateSearchPager.value = false
  searchHasMore.value = false
  songs.value = Array.isArray(res?.items) ? res.items : []
  songTotal.value = Number.isFinite(res?.total) ? res.total : songs.value.length
}

async function loadSongsBySearch(explicitKw = null) {
  const q = (explicitKw ?? keyword.value).trim()
  if (!q) {
    return loadSongsByBrowse()
  }
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  try {
    const res = await api.searchSongs(q, mediaFilter.value, songPage.value, { signal: req.signal })
    if (!reqGate.isCurrent(req.id)) return
    normalizeSongSearchResult(res, songPage.value)
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '搜索失败，请重试'
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

async function loadSongsByBrowse() {
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  approximateSearchPager.value = false
  searchHasMore.value = false
  try {
    const params = {
      sort: songSort.value,
      page: songPage.value,
      size: PAGE_SIZE_SONGS,
    }
    if (mediaFilter.value) params.mediaType = mediaFilter.value
    if (vocalFormFilter.value) params.vocalForm = vocalFormFilter.value
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (wordCount.value > 0) params.wordCount = wordCount.value
    if (activeTab.value === 'hd') {
      params.tag = HIGH_DEFINITION_TAG
    } else if (activeTab.value === 'tiktok') {
      params.tag = TIKTOK_TAG
    } else if (activeTab.value === 'singer' && selectedArtist.value) {
      params.artist = selectedArtist.value.name
      if (selectedArtist.value.artistKey) params.artistKey = selectedArtist.value.artistKey
    } else if (activeTab.value === 'language' && selectedLanguage.value) {
      params.language = selectedLanguage.value
    } else if (activeTab.value === 'theme' && selectedTag.value) {
      params.tag = selectedTag.value
    }
    const res = await api.browseSongPage(params, { signal: req.signal })
    if (!reqGate.isCurrent(req.id)) return
    songs.value = Array.isArray(res?.items) ? res.items : []
    songTotal.value = Number.isFinite(res?.total) ? res.total : songs.value.length
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '歌曲加载失败'
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

async function loadHotNewSongs() {
  if (hotNewMode.value === 'all') {
    return loadSongsByBrowse()
  }
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  approximateSearchPager.value = false
  searchHasMore.value = false
  try {
    const res = hotNewMode.value === 'ranking'
      ? await api.ranking(30, { signal: req.signal })
      : await api.newSongs({ signal: req.signal })
    if (!reqGate.isCurrent(req.id)) return
    const rawList = Array.isArray(res) ? res : (Array.isArray(res?.items) ? res.items : [])
    const filtered = filterAndSortClientSongs(rawList)
    songs.value = filtered
    songTotal.value = filtered.length
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '榜单加载失败'
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

async function loadVarietySongs() {
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  approximateSearchPager.value = false
  searchHasMore.value = false
  try {
    const params = {
      tag: selectedVariety.value,
      sort: songSort.value,
      page: songPage.value,
      size: PAGE_SIZE_SONGS,
    }
    if (mediaFilter.value) params.mediaType = mediaFilter.value
    if (vocalFormFilter.value) params.vocalForm = vocalFormFilter.value
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (wordCount.value > 0) params.wordCount = wordCount.value
    const res = await api.browseSongPage(params, { signal: req.signal })
    if (!reqGate.isCurrent(req.id)) return
    songs.value = Array.isArray(res?.items) ? res.items : []
    songTotal.value = Number.isFinite(res?.total) ? res.total : songs.value.length
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '综艺曲目加载失败'
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

async function loadArtistsAndInitials() {
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  approximateSearchPager.value = false
  searchHasMore.value = false
  try {
    const [pageRes, initRes] = await Promise.all([
      api.browseArtistPage({
        gender: artistGender.value,
        region: artistRegion.value,
        initial: artistLetter.value === '热门' ? '' : artistLetter.value,
        keyword: keyword.value.trim(),
        page: artistPage.value,
        size: PAGE_SIZE_ARTISTS,
      }, { signal: req.signal }),
      api.browseArtistInitials(artistGender.value, artistRegion.value, { signal: req.signal }),
    ])
    if (!reqGate.isCurrent(req.id)) return
    artists.value = Array.isArray(pageRes?.items) ? pageRes.items : []
    artistTotal.value = Number.isFinite(pageRes?.total) ? pageRes.total : artists.value.length
    regionDataAvailable.value = pageRes?.regionDataAvailable !== false
    artistInitials.value = Array.isArray(initRes) ? initRes : []
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '歌手加载失败'
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

async function loadArtistsOnly() {
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  approximateSearchPager.value = false
  searchHasMore.value = false
  try {
    const res = await api.browseArtistPage({
      gender: artistGender.value,
      region: artistRegion.value,
      initial: artistLetter.value === '热门' ? '' : artistLetter.value,
      keyword: keyword.value.trim(),
      page: artistPage.value,
      size: PAGE_SIZE_ARTISTS,
    }, { signal: req.signal })
    if (!reqGate.isCurrent(req.id)) return
    artists.value = Array.isArray(res?.items) ? res.items : []
    artistTotal.value = Number.isFinite(res?.total) ? res.total : artists.value.length
    regionDataAvailable.value = res?.regionDataAvailable !== false
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '歌手加载失败'
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

async function ensureLanguagesLoaded() {
  if (languages.value.length) {
    if (!selectedLanguage.value) selectedLanguage.value = languages.value[0].name
    return Boolean(selectedLanguage.value)
  }
  loading.value = true
  try {
    const list = await api.browseLanguages()
    languages.value = Array.isArray(list) ? list : []
    if (languages.value.length && !selectedLanguage.value) {
      selectedLanguage.value = languages.value[0].name
    }
    return Boolean(selectedLanguage.value)
  } catch (e) {
    loadError.value = e.message || '语种目录读取失败'
    return false
  } finally {
    loading.value = false
  }
}

async function ensureTagsLoaded() {
  if (tags.value.length) {
    if (!selectedTag.value) selectedTag.value = tags.value[0].name
    return Boolean(selectedTag.value)
  }
  loading.value = true
  try {
    const list = await api.browseTags()
    tags.value = Array.isArray(list) ? list : []
    if (tags.value.length && !selectedTag.value) {
      selectedTag.value = tags.value[0].name
    }
    return Boolean(selectedTag.value)
  } catch (e) {
    loadError.value = e.message || '主题目录读取失败'
    return false
  } finally {
    loading.value = false
  }
}

async function loadPlaylists() {
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  try {
    const list = await api.playlists({ signal: req.signal })
    if (!reqGate.isCurrent(req.id)) return
    playlists.value = Array.isArray(list) ? list : []
    const targetId = selectedPlaylistId.value || playlists.value[0]?.id
    if (targetId) {
      await selectPlaylist(targetId)
    } else {
      playlistDetail.value = null
      loading.value = false
    }
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '歌单列表读取失败'
    loading.value = false
  }
}

async function loadFavorites() {
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  try {
    const res = await loadAllFavoritePages((page, size) => api.favorites(user.clientToken, page, size, { signal: req.signal }))
    if (!reqGate.isCurrent(req.id)) return
    songs.value = Array.isArray(res) ? res : []
    songTotal.value = songs.value.length
    // 这份列表就是完整的收藏集合，直接补齐 ID，省掉一次全量分页请求
    favorites.hydrate(songs.value.map(song => song?.id).filter(id => id != null), user.clientToken)
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '收藏读取失败'
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

/** 重新同步收藏 ID；失败状态由 store 的 loadFailed 呈现，这里不再重复弹提示。 */
async function syncFavorites() {
  try {
    await favorites.load(user.clientToken, true)
  } catch {
    // loadFailed 已标记，界面提示条会继续显示
  }
}

async function loadRecentHistory() {
  const req = reqGate.begin()
  loading.value = true
  loadError.value = ''
  try {
    const res = await api.recentHistory(user.clientToken, recentMineOnly.value, { signal: req.signal })
    if (!reqGate.isCurrent(req.id)) return
    recentItems.value = Array.isArray(res) ? res : []
  } catch (e) {
    if (!reqGate.isCurrent(req.id) || e.name === 'AbortError') return
    loadError.value = e.message || '演唱记录读取失败'
  } finally {
    if (reqGate.isCurrent(req.id)) loading.value = false
  }
}

async function orderSong(song) {
  if (song?.playable === false) return
  await executeOrder(song.id, async () => {
    try {
      const res = await controls.order(song.id)
      player.applyControlResponse(res)
      toast(formatOrderToast(res))
    } catch (e) {
      if (e.code === 'SONG_IN_QUEUE') {
        if (await confirmDialog(`${e.message || '已在队列中'}，是否仍然重复点歌？`, { title: '重复点歌确认' })) {
          try {
            const forced = await controls.order(song.id, true)
            player.applyControlResponse(forced)
            toast(formatOrderToast(forced))
          } catch (err2) { toast(err2.message || '点歌失败') }
        }
      } else {
        toast(e.message || '点歌失败')
      }
    }
  })
}

async function orderTopSong(song) {
  if (song?.playable === false) return
  if (player.nowPlaying?.song?.id === song.id) {
    toast('当前已在播放该歌曲')
    return
  }
  const existing = (player.queue || []).find(item => item.song?.id === song.id && item.queueId != null)
  if (existing) {
    try {
      await controls.top(existing.queueId)
      toast(`已将《${song.title}》置顶优先播放`)
    } catch (e) {
      toast(e.message || '置顶失败')
    }
    return
  }
  await executeOrder(song.id, async () => {
    const pickAndTop = async (res) => {
      const list = Array.isArray(res?.snapshot?.list)
        ? res.snapshot.list
        : Array.isArray(res?.list)
          ? res.list
          : player.queue
      const matched = (list || []).filter(item => item.song?.id === song.id && item.queueId != null).pop()
      const targetQueueId = matched?.queueId ?? res?.queueId
      if (targetQueueId != null) {
        await controls.top(targetQueueId)
        toast(`已优先插播《${song.title}》`)
      } else {
        toast(formatOrderToast(res))
      }
    }
    try {
      const res = await controls.order(song.id)
      player.applyControlResponse(res)
      await pickAndTop(res)
    } catch (e) {
      if (e.code === 'SONG_IN_QUEUE') {
        if (await confirmDialog(`${e.message || '已在队列中'}，是否仍然插播至队首？`, { title: '重复插播确认' })) {
          try {
            const forced = await controls.order(song.id, true)
            player.applyControlResponse(forced)
            await pickAndTop(forced)
          } catch (err2) { toast(err2.message || '插播失败') }
        }
      } else {
        toast(e.message || '插播失败')
      }
    }
  })
}

async function orderWholePlaylist() {
  const id = playlistDetail.value?.id
  if (!id || orderingPlaylist.value) return
  orderingPlaylist.value = true
  try {
    const res = await api.orderPlaylist(id, user.clientToken)
    if (res?.queueFull) {
      toast(`已加入 ${res.addedCount || 0} 首，队列已满`)
    } else {
      toast(`已将歌单加入队列（${res?.addedCount ?? playlistDetail.value.songs?.length ?? 0} 首）`)
    }
  } catch (e) {
    toast(e.message || '整单点歌失败')
  } finally {
    orderingPlaylist.value = false
  }
}

async function repeatHistorySong(item) {
  busyHistoryId.value = item.historyId
  try {
    const res = await api.repeatHistory(item.historyId, user.clientToken)
    toast(`已加入队列，第 ${res.position} 位`)
  } catch (error) {
    if (error.code === 'SONG_IN_QUEUE') {
      if (await confirmDialog(error.message, { title: '歌曲已在队列中' })) {
        try {
          const retry = await api.repeatHistory(item.historyId, user.clientToken, true)
          toast(`已重复加入，第 ${retry.position} 位`)
        } catch (err2) { toast(err2.message || '再唱失败') }
      }
    } else {
      toast(error.message || '再唱失败')
    }
  } finally {
    busyHistoryId.value = null
  }
}

async function toggleFav(songId) {
  try {
    const added = await favorites.toggle(songId, user.clientToken)
    toast(added ? '已收藏' : '已取消收藏')
    if (activeTab.value === 'favorites' && !added) {
      songs.value = songs.value.filter(s => s.id !== songId)
      songTotal.value = Math.max(0, songTotal.value - 1)
    }
  } catch (e) {
    toast(e.message || '操作失败')
  }
}

async function submitWish() {
  const kw = keyword.value.trim()
  if (!kw) return
  try {
    await api.addWish(kw, user.clientToken)
    toast(`已记录你想唱的《${kw}》`)
  } catch (e) {
    toast(e.message || '提交心愿失败')
  }
}

function rememberAvatar(artist) {
  avatarFailures.value = rememberAvatarFailure(avatarFailures.value, artist)
}

function songThumbUrl(song) {
  if (!song) return ''
  if (song.coverUrl) return song.coverUrl
  if (activeTab.value === 'singer' && selectedArtist.value) {
    return avatarUrlFor(selectedArtist.value, avatarFailures.value)
  }
  const artistName = (song.artist || '').trim()
  if (artistName && !avatarFailures.value.has(artistName)) {
    return `/api/artists/${encodeURIComponent(artistName)}/avatar`
  }
  return ''
}

function rememberSongThumbFailure(song) {
  if (!song) return
  if (activeTab.value === 'singer' && selectedArtist.value) {
    rememberAvatar(selectedArtist.value)
  }
  const artistName = (song.artist || '').trim()
  if (artistName) {
    avatarFailures.value = rememberAvatarFailure(avatarFailures.value, { name: artistName, artistKey: artistName })
  }
}

function triggerSearchNow() {
  clearTimeout(debounceTimer)
  songPage.value = 0
  artistPage.value = 0
  reloadActive()
}

function focusSearchKeyboard() {
  keyboardRef.value?.focusInput?.()
}

watch(keyword, () => {
  if (suppressKeywordWatch) {
    suppressKeywordWatch = false
    return
  }
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    songPage.value = 0
    artistPage.value = 0
    if (activeTab.value === 'singer' && !selectedArtist.value) {
      loadArtistsOnly()
    } else if (showKeyboard.value) {
      loadSongsOnly()
    }
  }, 280)
})

watch(wordCount, () => {
  songPage.value = 0
  loadSongsOnly()
})

watch(() => player.historyRevision, () => {
  if (activeTab.value === 'recent') loadRecentHistory()
})

watch(
  [
    () => props.initialTab,
    () => props.initialQuery,
    () => props.initialArtist,
    () => props.initialLanguage,
    () => props.initialTag,
    () => props.initialVocalForm,
    () => props.initialPlaylistId,
  ],
  ([tab, q, artist, lang, tag, vocalForm, playlistId]) => {
    const resolvedTab = (tab && CAPSULE_TABS.some(item => item.key === tab)) ? tab : activeTab.value
    const nextKw = q || ''
    clearTimeout(debounceTimer)
    if (keyword.value !== nextKw) suppressKeywordWatch = true
    activeTab.value = resolvedTab
    keyword.value = nextKw
    selectedArtist.value = artist || null
    selectedLanguage.value = lang || ''
    selectedTag.value = tag || ''
    vocalFormFilter.value = vocalForm || ''
    selectedPlaylistId.value = playlistId || null
    songPage.value = 0
    artistPage.value = 0
    approximateSearchPager.value = false
    searchHasMore.value = false
    loadError.value = ''
    reloadActive()
  }
)

let typingCapture = null
onMounted(() => {
  reloadActive()
  typingCapture = useGlobalTypingCapture({
    onChar(ch) {
      if (!showKeyboard.value) {
        switchTab('song_name')
      }
      keyword.value = (keyword.value || '') + ch
    },
    onBackspace() {
      if (keyword.value && keyword.value.length > 0) {
        keyword.value = keyword.value.slice(0, -1)
      }
    }
  })
})

onBeforeUnmount(() => {
  clearTimeout(debounceTimer)
  typingCapture?.dispose()
})

defineExpose({ switchTab, focusSearchKeyboard })
</script>

<style scoped>
.browser-root {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 6px 14px;
  gap: 8px;
  min-height: 0;
}
.capsule-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.back-hall-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  background: rgba(255, 198, 75, 0.16);
  border: 1px solid rgba(255, 198, 75, 0.45);
  color: var(--gold2);
  font-size: 12px;
  font-weight: 700;
  flex: none;
}
.capsule-scroll {
  flex: 1;
  display: flex;
  gap: 6px;
  overflow-x: auto;
  scrollbar-width: none;
}
.capsule-scroll::-webkit-scrollbar { display: none; }
.capsule-tab {
  height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: var(--dim);
}
.capsule-tab.on {
  background: linear-gradient(135deg, var(--gold), #e0a81b);
  color: #191304;
  border-color: var(--gold);
  font-weight: 800;
}
.kb-mobile-toggle {
  display: none;
  align-items: center;
  gap: 4px;
  height: 34px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  font-size: 12px;
}

.three-col-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr) 268px;
  gap: 10px;
}
.three-col-body.no-kb {
  grid-template-columns: 184px minmax(0, 1fr);
}
.left-rail {
  background: rgba(13, 18, 30, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 8px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rail-group { display: flex; flex-direction: column; gap: 5px; }
.rail-divider { height: 1px; background: rgba(255, 255, 255, 0.1); margin: 4px 0; }
.rail-btn {
  width: 100%;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  text-align: left;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.05);
  color: var(--dim);
}
.rail-btn.on { background: var(--gold); color: #191304; font-weight: 800; }
.rail-btn.gold { background: rgba(255, 198, 75, 0.2); color: var(--gold2); font-weight: 700; }
.rail-btn small { font-size: 10px; opacity: 0.78; }
.rail-letters { display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px; }
.letter-chip {
  height: 28px; border-radius: 6px; font-size: 11px;
  background: rgba(255, 255, 255, 0.06); color: var(--dim);
}
.letter-chip.on { background: var(--gold); color: #191304; font-weight: 800; }
.variety-btn {
  display: flex; align-items: center; gap: 8px; padding: 6px;
  border-radius: 8px; background: rgba(255, 255, 255, 0.05); font-size: 12px; text-align: left;
}
.variety-btn.on { border: 1px solid var(--gold); background: rgba(255, 198, 75, 0.16); color: var(--gold2); font-weight: 700; }
.variety-btn img { width: 32px; height: 22px; border-radius: 4px; object-fit: cover; }

.center-stage {
  min-width: 0;
  min-height: 0;
  background: rgba(13, 18, 30, 0.65);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.playlist-hero, .artist-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
}
.artist-banner { justify-content: flex-start; }
.artist-mini-av {
  width: 40px; height: 40px; border-radius: 50%; overflow: hidden;
  background: var(--gold); color: #191304; display: grid; place-items: center; font-weight: 800;
}
.artist-mini-av img { width: 100%; height: 100%; object-fit: cover; }
.pl-meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.pl-meta strong, .artist-banner strong { font-size: 15px; }
.pl-meta small, .artist-banner small { font-size: 11px; color: var(--dim); }
.ai-tag { align-self: flex-start; font-size: 10px; padding: 1px 6px; border-radius: 4px; background: var(--purple-glow); color: var(--purple); }

.song-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-rows: min-content;
  gap: 8px;
}
.song-card {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  min-width: 0;
}
.song-card.ordered {
  border-color: rgba(255, 198, 75, 0.52);
  background: rgba(255, 198, 75, 0.1);
}
.card-thumb {
  width: 42px; height: 42px; border-radius: 8px; overflow: hidden; flex: none;
  background: rgba(255, 255, 255, 0.1); display: grid; place-items: center;
  font-weight: 800; color: var(--gold2);
}
.card-thumb img { width: 100%; height: 100%; object-fit: cover; }
.card-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.card-title-row { display: flex; align-items: center; gap: 6px; min-width: 0; }
.card-title { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; flex: 1; }
.card-title-row .tag { flex-shrink: 0; }
.card-sub { font-size: 11px; color: var(--dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.song-num {
  font-family: monospace;
  font-weight: 700;
  color: var(--gold);
  background: rgba(255, 198, 75, 0.12);
  padding: 1px 4px;
  border-radius: 4px;
  flex-shrink: 0;
}
.card-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.fav-btn {
  width: 28px; height: 28px; border-radius: 50%; display: grid; place-items: center;
  color: var(--dim); background: rgba(255, 255, 255, 0.06);
}
.fav-btn.on { color: #ff6b8b; background: rgba(255, 107, 139, 0.16); }
.top-order-btn {
  padding: 5px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  color: var(--gold2);
  background: rgba(255, 198, 75, 0.14);
  border: 1px solid rgba(255, 198, 75, 0.35);
}
.top-order-btn:hover:not(:disabled) {
  background: rgba(255, 198, 75, 0.25);
}
.top-order-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.order-btn {
  padding: 5px 11px; border-radius: 999px; font-size: 12px; font-weight: 800;
  background: var(--gold); color: #191304;
}
.order-btn.ordered { background: rgba(110, 214, 168, 0.22); color: var(--mint); border: 1px solid rgba(110, 214, 168, 0.45); }
.order-btn.unavailable { background: rgba(255, 255, 255, 0.1); color: var(--dim2); cursor: not-allowed; }

.artist-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  grid-auto-rows: min-content;
  gap: 10px;
}
.artist-card {
  display: flex; flex-direction: column; align-items: center; text-align: center; gap: 6px;
  padding: 10px 6px; border-radius: 12px;
  background: rgba(255, 255, 255, 0.06); border: 1px solid rgba(255, 255, 255, 0.1);
}
.artist-card:hover { border-color: var(--gold); }
.avatar-circle {
  width: 64px; height: 64px; border-radius: 50%; overflow: hidden;
  background: linear-gradient(135deg, rgba(255, 198, 75, 0.3), rgba(84, 214, 226, 0.2));
  display: grid; place-items: center; font-size: 22px; font-weight: 800; color: var(--gold2);
}
.avatar-circle img { width: 100%; height: 100%; object-fit: cover; }
.artist-card strong { font-size: 13px; max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.artist-card small { font-size: 11px; color: var(--dim); }

.stage-state {
  grid-column: 1 / -1;
  padding: 40px 16px;
  text-align: center;
  color: var(--dim);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.stage-state.error { color: var(--coral-soft); }
.favorites-sync-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  margin-bottom: 8px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--coral-soft);
  background: rgba(216, 90, 48, 0.16);
  border: 1px solid rgba(216, 90, 48, 0.4);
}
.pager-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding-top: 4px;
  flex: none;
}
.pager-btn {
  padding: 5px 14px; border-radius: 999px; font-size: 12px; font-weight: 700;
  background: rgba(255, 255, 255, 0.1); border: 1px solid rgba(255, 255, 255, 0.14);
}
.pager-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.pager-info { font-size: 12px; color: var(--dim); }

@media (max-width: 1440px) {
  .song-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .artist-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}

@media (max-width: 899px) {
  .kb-mobile-toggle { display: inline-flex; }
  .three-col-body,
  .three-col-body.no-kb {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
  .left-rail {
    flex-direction: row;
    flex-wrap: nowrap;
    overflow-x: auto;
    max-height: 54px;
    padding: 6px;
  }
  .rail-group { flex-direction: row; }
  .rail-letters { display: flex; }
  .rail-btn, .variety-btn { white-space: nowrap; width: auto; }
  .song-grid { grid-template-columns: 1fr; }
  .artist-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .right-keyboard { display: none; }
  .right-keyboard.mobile-open {
    display: flex;
    position: fixed;
    left: 10px;
    right: 10px;
    bottom: 126px;
    /* dvh 在旧 WebView 上无效，先给 vh 回退再覆盖。 */
    max-height: calc(100vh - 196px);
    max-height: calc(100dvh - 196px);
    overflow-y: auto;
    z-index: 45;
  }
}

@media (max-width: 759px) {
  .right-keyboard.mobile-open {
    bottom: calc(206px + var(--safe-bottom));
    max-height: calc(100vh - 280px - var(--safe-bottom));
    max-height: calc(100dvh - 280px - var(--safe-bottom));
  }
}
</style>
