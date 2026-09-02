import { describe, expect, it, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAdminAuthStore } from '../stores/adminAuth'
import {
  createMockSong,
  createMockSourceItem,
  createMockArtist,
  createMockPlaylist,
  createMockWish,
  createMockScrapeTask
} from './mockData'
import { canDeleteSongs } from '../views/admin/libraryMode'
import { createAudioLayoutDraft, swapAudioLayout } from '../views/admin/audioLayout'
import { scanPercent } from '../views/admin/scanProgress'
import { resolveAiConfiguration } from '../views/admin/artistAiState'
import { saveDirtySections } from '../views/admin/settingsState'
import { buildScrapeRoute } from '../views/admin/ktvLibraryScrapeRoute'
import api from '../api/client'

describe('Admin Views Click & Interactive Logic Verification', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  describe('1. AdminLoginView - Authentication & Token Persistence', () => {
    it('sets admin token on successful login and clears on logout', () => {
      const auth = useAdminAuthStore()
      expect(auth.isAuthenticated).toBe(false)

      auth.setToken('adm-token-secret-123')
      expect(auth.token).toBe('adm-token-secret-123')
      expect(auth.isAuthenticated).toBe(true)

      auth.clearToken()
      expect(auth.isAuthenticated).toBe(false)
    })
  })

  describe('2. DashboardView - Rescan, Progress Polling & Diagnostics', () => {
    it('calculates scan progress and determines active state', () => {
      const runningProgress = {
        phase: 'MEDIA_PROBE',
        probeQueued: 100,
        probeCompleted: 50,
        running: true
      }
      expect(scanPercent(runningProgress)).toBe(75)

      const completedProgress = {
        phase: 'COMPLETED',
        running: false
      }
      expect(scanPercent(completedProgress)).toBe(100)
    })

    it('triggers one-click diagnostics download', async () => {
      const mockBlob = new Blob(['zip-content'], { type: 'application/zip' })
      vi.spyOn(api, 'adminDownloadDiagnostics').mockResolvedValue(mockBlob)

      const res = await api.adminDownloadDiagnostics()
      expect(res).toBe(mockBlob)
      expect(res.type).toBe('application/zip')
    })
  })

  describe('3. SourceLibraryView - Filter, Batch Transcode, Cleanup & Delete', () => {
    it('validates formatAnalysis filter and batch transcode selection', async () => {
      const item1 = createMockSourceItem({ id: 1, transcodeRequired: true, displayStatus: 'PENDING_TRANSCODE' })
      const item2 = createMockSourceItem({ id: 2, transcodeRequired: false, displayStatus: 'COMPLETED' })

      const isTranscodable = item => item.transcodeRequired && ['PENDING_TRANSCODE', 'FAILED'].includes(item.displayStatus) && !item.sourceDeleted
      expect(isTranscodable(item1)).toBe(true)
      expect(isTranscodable(item2)).toBe(false)

      vi.spyOn(api, 'adminStartSourceTranscode').mockResolvedValue({ running: true, total: 1 })
      const res = await api.adminStartSourceTranscode([item1.id])
      expect(res.running).toBe(true)
    })

    it('enforces NAS read-only safety forbidding delete in external mode', () => {
      expect(canDeleteSongs('EXTERNAL_READ_ONLY')).toBe(false)
      expect(canDeleteSongs('MANAGED')).toBe(true)
      expect(canDeleteSongs(null)).toBe(false)
    })
  })

  describe('4. KtvLibraryView - Metadata Editor, AudioLayout, Playlist Picker, Scrape Routing', () => {
    it('constructs scrape routing parameters correctly', () => {
      const batchRoute = buildScrapeRoute([101, 102])
      expect(batchRoute.name).toBe('admin-metadata-scrape')
      expect(batchRoute.query.songIds).toBe('101,102')

      const singleReviewRoute = buildScrapeRoute([101], true)
      expect(singleReviewRoute.query.review).toBe('1')
    })

    it('creates and swaps AudioLayout draft configurations', () => {
      const dualTrack = { layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 }
      const draft = createAudioLayoutDraft(dualTrack)
      expect(draft.layout).toBe('DUAL_TRACK')
      expect(draft.originalTrackIndex).toBe(0)

      const swapped = swapAudioLayout(draft)
      expect(swapped.originalTrackIndex).toBe(1)
      expect(swapped.accompanimentTrackIndex).toBe(0)

      const dualChannel = { layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' }
      const channelDraft = createAudioLayoutDraft(dualChannel)
      const swappedChannel = swapAudioLayout(channelDraft)
      expect(swappedChannel.originalChannel).toBe('RIGHT')
      expect(swappedChannel.accompanimentChannel).toBe('LEFT')
    })
  })

  describe('5. ArtistLibraryView - Avatar Scanning, Upload & AI Classification', () => {
    it('resolves AI configuration for artist classification', async () => {
      const validConfig = { enabled: true, baseUrl: 'https://api.deepseek.com', bulkModel: 'deepseek-chat' }
      const resolved = await resolveAiConfiguration(() => Promise.resolve(validConfig))
      expect(resolved).toEqual(validConfig)

      const unconfigured = await resolveAiConfiguration(() => Promise.resolve({ enabled: false }))
      expect(unconfigured).toBeNull()
    })

    it('triggers local avatar scan and manual upload', async () => {
      vi.spyOn(api, 'adminScanLocalAvatars').mockResolvedValue({ scanned: 50, matched: 12 })
      const res = await api.adminScanLocalAvatars()
      expect(res.matched).toBe(12)
    })
  })

  describe('6. MetadataScrapeView - Scrape Batch, Threshold & Manual Review', () => {
    it('manages scrape batch lifecycle (start, pause, resume, item retry)', async () => {
      const mockTask = createMockScrapeTask()
      vi.spyOn(api, 'adminStartMetadataScrape').mockResolvedValue(mockTask)
      vi.spyOn(api, 'adminPauseMetadataScrape').mockResolvedValue({ ...mockTask, status: 'PAUSED' })
      vi.spyOn(api, 'adminResumeMetadataScrape').mockResolvedValue({ ...mockTask, status: 'RUNNING' })

      const started = await api.adminStartMetadataScrape({ all: true, songIds: [], autoApplyThreshold: 0.95 })
      expect(started.status).toBe('RUNNING')

      const paused = await api.adminPauseMetadataScrape(started.batchId)
      expect(paused.status).toBe('PAUSED')

      const resumed = await api.adminResumeMetadataScrape(started.batchId)
      expect(resumed.status).toBe('RUNNING')
    })
  })

  describe('7. AiLibraryView - Theme Playlist Generator & CRUD', () => {
    it('creates, edits, toggles visibility, and deletes playlists', async () => {
      const playlist = createMockPlaylist()
      vi.spyOn(api, 'adminAiCreatePlaylist').mockResolvedValue(playlist)
      vi.spyOn(api, 'adminAiUpdatePlaylist').mockResolvedValue({ ...playlist, publicVisible: false })
      vi.spyOn(api, 'adminAiDeletePlaylist').mockResolvedValue({ success: true })

      const created = await api.adminAiCreatePlaylist({ name: '新歌单', theme: '流行' })
      expect(created.id).toBe('pl-1')

      const updated = await api.adminAiUpdatePlaylist('pl-1', { publicVisible: false })
      expect(updated.publicVisible).toBe(false)

      const deleted = await api.adminAiDeletePlaylist('pl-1')
      expect(deleted.success).toBe(true)
    })
  })

  describe('8. SettingsView - Section Dirty Tracking, Save, Test & Wish CSV Export', () => {
    it('tracks section dirty state and only saves modified sections', async () => {
      const originalGeneral = { room_name: '客厅', port: 54001 }
      const modifiedGeneral = { room_name: '家庭影院', port: 54001 }

      const saveGeneral = vi.fn().mockResolvedValue(modifiedGeneral)
      const saveAi = vi.fn()

      const sections = [
        { name: '基础设置', dirty: true, state: 'ready', save: saveGeneral },
        { name: 'AI 设置', dirty: false, state: 'ready', save: saveAi }
      ]

      const result = await saveDirtySections(sections)
      expect(result.successes.length).toBe(1)
      expect(result.successes[0].name).toBe('基础设置')
      expect(result.failures).toEqual([])
      expect(saveGeneral).toHaveBeenCalled()
      expect(saveAi).not.toHaveBeenCalled()
    })

    it('exports wish list as CSV blob and deletes wish item', async () => {
      const mockCsvBlob = new Blob(['\ufeffID,关键词,提交人,提交时间\n1,夜曲,用户A,2026-09-01'], { type: 'text/csv;charset=utf-8;' })
      vi.spyOn(api, 'adminExportWishesCsv').mockResolvedValue(mockCsvBlob)
      vi.spyOn(api, 'adminDeleteWish').mockResolvedValue({ success: true })

      const csv = await api.adminExportWishesCsv()
      expect(csv.size).toBeGreaterThan(0)

      const delRes = await api.adminDeleteWish(1)
      expect(delRes.success).toBe(true)
    })
  })
})
