import { createRouter, createWebHistory } from 'vue-router'
import { useAdminAuthStore } from '../stores/adminAuth'

const KtvKioskView = () => import('../views/KtvKioskView.vue')

/**
 * 应用路由配置模块。
 * 统一将点歌与控制台路由映射到安卓 TV 同款 KTV 外壳，保留全部深度链接及 /admin 管理后台路由。
 */
const routes = [
  { path: '/', name: 'entry', component: KtvKioskView, meta: { public: true } },
  { path: '/home', name: 'home', component: KtvKioskView, meta: { public: true } },
  { path: '/search', name: 'search', component: KtvKioskView, meta: { public: true } },
  { path: '/artist/:name', name: 'artist', component: KtvKioskView, meta: { public: true } },
  { path: '/browse', name: 'browse', component: KtvKioskView, meta: { public: true } },
  { path: '/playlists', name: 'playlists', component: KtvKioskView, meta: { public: true } },
  { path: '/playlists/:id', name: 'playlist-detail', component: KtvKioskView, meta: { public: true } },
  { path: '/recent', name: 'recent-history', component: KtvKioskView, meta: { public: true } },
  { path: '/favorites', name: 'favorites', component: KtvKioskView, meta: { public: true } },
  { path: '/queue', name: 'queue', component: KtvKioskView, meta: { public: true } },
  { path: '/remote', name: 'remote', component: KtvKioskView, meta: { public: true } },
  { path: '/lyric', name: 'lyric', component: KtvKioskView, meta: { public: true } },

  // 管理后台登录页与全部后台管理视图
  { path: '/admin/login', name: 'admin-login', component: () => import('../views/admin/AdminLoginView.vue'), meta: { public: true, adminLogin: true } },
  { path: '/admin', name: 'admin-dashboard', component: () => import('../views/admin/DashboardView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/source-library', name: 'admin-source-library', component: () => import('../views/admin/SourceLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/ktv-library', name: 'admin-ktv-library', component: () => import('../views/admin/KtvLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/artists', name: 'admin-artists', component: () => import('../views/admin/ArtistLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/ktv-library/metadata-scrape', name: 'admin-metadata-scrape', component: () => import('../views/admin/MetadataScrapeView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/songs', redirect: { name: 'admin-ktv-library' } },
  { path: '/admin/ai', redirect: { name: 'admin-ktv-library' } },
  { path: '/admin/iktv-fusion', name: 'admin-iktv-fusion', component: () => import('../views/admin/IktvFusionView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/settings', name: 'admin-settings', component: () => import('../views/admin/SettingsView.vue'), meta: { public: true, admin: true } }
]

const router = createRouter({
  history: createWebHistory('/m/'),
  routes
})

router.beforeEach((to) => {
  const admin = useAdminAuthStore()
  if (to.meta.adminLogin && admin.isAuthenticated) return { name: 'admin-dashboard' }
  if (to.meta.admin && !admin.isAuthenticated) {
    return { name: 'admin-login', query: { redirect: to.fullPath } }
  }
})

export default router
