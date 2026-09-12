import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useAdminAuthStore } from '../stores/adminAuth'

/**
 * 应用路由配置模块。
 * 定义 H5 前端所有页面的路由映射、懒加载及导航守卫。
 *
 * App router configuration module.
 * Defines route mappings, lazy loading, and navigation guards for all H5 frontend pages.
 */

// H5 页面地图（详设§3.2）
// H5 page map (design spec §3.2)
const routes = [
  { path: '/', name: 'entry', component: () => import('../views/EntryView.vue'), meta: { public: true } },        // H5-01
  { path: '/home', name: 'home', component: () => import('../views/HomeView.vue') },                              // H5-02
  { path: '/search', name: 'search', component: () => import('../views/SearchView.vue') },                        // H5-03
  { path: '/artist/:name', name: 'artist', component: () => import('../views/ArtistView.vue') },                  // H5-04
  { path: '/browse', name: 'browse', component: () => import('../views/CategoryBrowseView.vue') },
  { path: '/playlists', name: 'playlists', component: () => import('../views/PlaylistListView.vue') },
  { path: '/playlists/:id', name: 'playlist-detail', component: () => import('../views/PlaylistDetailView.vue') },
  { path: '/recent', name: 'recent-history', component: () => import('../views/RecentHistoryView.vue') },
  { path: '/favorites', name: 'favorites', component: () => import('../views/FavoritesView.vue') },
  { path: '/queue', name: 'queue', component: () => import('../views/QueueView.vue') },                           // H5-05
  { path: '/remote', name: 'remote', component: () => import('../views/RemoteView.vue') },                        // H5-06
  { path: '/lyric', name: 'lyric', component: () => import('../views/LyricView.vue') },                           // H5-07

  // 管理后台登录页；管理 API 令牌由服务端签发。
  { path: '/admin/login', name: 'admin-login', component: () => import('../views/admin/AdminLoginView.vue'), meta: { public: true, adminLogin: true } },
  // 管理后台（管理员登录后访问，PC/手机浏览器）
  // Admin panel (requires an admin session, accessible from PC/mobile browser)
  { path: '/admin', name: 'admin-dashboard', component: () => import('../views/admin/DashboardView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/source-library', name: 'admin-source-library', component: () => import('../views/admin/SourceLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/ktv-library', name: 'admin-ktv-library', component: () => import('../views/admin/KtvLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/artists', name: 'admin-artists', component: () => import('../views/admin/ArtistLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/ktv-library/metadata-scrape', name: 'admin-metadata-scrape', component: () => import('../views/admin/MetadataScrapeView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/songs', redirect: { name: 'admin-ktv-library' } },
  { path: '/admin/ai', name: 'admin-ai', component: () => import('../views/admin/AiLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/settings', name: 'admin-settings', component: () => import('../views/admin/SettingsView.vue'), meta: { public: true, admin: true } }
]

/**
 * 创建路由实例。
 * 使用 HTML5 History 模式，基础路径为 /m/。
 *
 * Create router instance.
 * Uses HTML5 History mode with base path /m/.
 */
const router = createRouter({
  // 部署在 /m 下
  // Deployed under /m/
  history: createWebHistory('/m/'),
  routes
})

/**
 * 全局前置导航守卫。
 * 未完成服务端身份注册的用户强制跳转至进入页。
 * 详设 H5-01。
 *
 * Global beforeEach navigation guard.
 * Unregistered users (no nickname) are redirected to the entry page.
 * Design spec H5-01.
 */
router.beforeEach((to) => {
  const admin = useAdminAuthStore()
  if (to.meta.adminLogin && admin.isAuthenticated) return { name: 'admin-dashboard' }
  if (to.meta.admin && !admin.isAuthenticated) {
    return { name: 'admin-login', query: { redirect: to.fullPath } }
  }
  const user = useUserStore()
  if (!to.meta.public && !user.isReady) {
    return { name: 'entry' }
  }
})

export default router
