import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('H5 Views Wiring & Error Contracts (Batch 4)', () => {
  const viewsDir = path.resolve(__dirname, '../views')

  it('R-05: AiLibraryView mounts AiTaskPanel with correct import path', () => {
    const aiViewContent = fs.readFileSync(path.join(viewsDir, 'admin/AiLibraryView.vue'), 'utf-8')
    expect(aiViewContent).toContain("import AiTaskPanel from '../../components/admin/AiTaskPanel.vue'")
    expect(aiViewContent).toMatch(/<AiTaskPanel\b/)
  })

  it('R-06: HomeView cats contains recent history entry', () => {
    const homeContent = fs.readFileSync(path.join(viewsDir, 'HomeView.vue'), 'utf-8')
    expect(homeContent).toContain("label: '最近唱'")
    expect(homeContent).toContain('History')
  })

  it('R-42: QueueView uses tri-state equality check for tvOnline', () => {
    const queueContent = fs.readFileSync(path.join(viewsDir, 'QueueView.vue'), 'utf-8')
    expect(queueContent).toContain('player.tvOnline === false')
    expect(queueContent).not.toMatch(/v-if="!player\.tvOnline"/)
  })

  it('R-22: SourceLibraryView and KtvLibraryView contain error state notice and retry', () => {
    const sourceContent = fs.readFileSync(path.join(viewsDir, 'admin/SourceLibraryView.vue'), 'utf-8')
    expect(sourceContent).toContain('loadError')
    expect(sourceContent).toMatch(/class="notice error-notice"/)

    const ktvContent = fs.readFileSync(path.join(viewsDir, 'admin/KtvLibraryView.vue'), 'utf-8')
    expect(ktvContent).toContain('loadError')
    expect(ktvContent).toMatch(/class="notice error-notice"/)
  })

  it('R-43: PlaylistDetailView differentiates 404 from other errors', () => {
    const playlistContent = fs.readFileSync(path.join(viewsDir, 'PlaylistDetailView.vue'), 'utf-8')
    expect(playlistContent).toContain('notFound')
    expect(playlistContent).toContain('loadError')
    expect(playlistContent).toMatch(/404/)
  })

  it('retries only failed settings sections and preserves unsaved drafts', () => {
    const settingsContent = fs.readFileSync(path.join(viewsDir, 'admin/SettingsView.vue'), 'utf-8')
    expect(settingsContent).toContain('@click="retryFailedSections"')
    expect(settingsContent).toContain('failedSettingsSections(sectionState)')
    expect(settingsContent).toContain('reconcileSettingsDraft(')
  })

  it('guards scan and transcode progress polling with a generation-aware single-flight gate', () => {
    const sourceContent = fs.readFileSync(path.join(viewsDir, 'admin/SourceLibraryView.vue'), 'utf-8')
    const dashboardContent = fs.readFileSync(path.join(viewsDir, 'admin/DashboardView.vue'), 'utf-8')
    expect(sourceContent).toContain('createProgressPollGate()')
    expect(sourceContent).toContain('progressPollGate.isCurrent(request)')
    expect(dashboardContent).toContain('createProgressPollGate()')
    expect(dashboardContent).toContain('scanPollGate.isCurrent(request)')
  })

  it('keeps existing search results visible and offers retry for a failed next page', () => {
    const searchContent = fs.readFileSync(path.join(viewsDir, 'SearchView.vue'), 'utf-8')
    expect(searchContent).toContain('v-if="searchError && results.length"')
    expect(searchContent).toContain('@click="retryLoadMore"')
    expect(searchContent).toContain('v-if="hasMore && !searchError"')
  })

  it('exposes home, now-playing, remote vocal and lyric navigation actions to keyboard users', () => {
    const homeContent = fs.readFileSync(path.join(viewsDir, 'HomeView.vue'), 'utf-8')
    const nowPlayingContent = fs.readFileSync(path.join(viewsDir, '../components/NowPlayingBar.vue'), 'utf-8')
    const remoteContent = fs.readFileSync(path.join(viewsDir, 'RemoteView.vue'), 'utf-8')
    const lyricContent = fs.readFileSync(path.join(viewsDir, 'LyricView.vue'), 'utf-8')

    expect(homeContent).toMatch(/<button[^>]*class="search"[^>]*@click=/)
    expect(homeContent).toContain('aria-label="搜索歌名、歌手或拼音"')
    expect(homeContent).toMatch(/<button[^>]*class="cat"[^>]*@click=/)
    expect(homeContent).not.toMatch(/<div[^>]*class="cat"[^>]*@click=/)
    expect(nowPlayingContent).toContain('<RouterLink')
    expect(remoteContent).toMatch(/<button[^>]*aria-pressed=/)
    expect(remoteContent).not.toMatch(/<div[^>]*@click="setVocal/)
    expect(lyricContent).toMatch(/<button[^>]*class="down"[^>]*aria-label=/)
    expect(lyricContent).not.toMatch(/<span[^>]*class="down"[^>]*@click=/)
  })

  it('R-44: PlaylistDetailView reports a queue-full partial result', () => {
    const playlistContent = fs.readFileSync(path.join(viewsDir, 'PlaylistDetailView.vue'), 'utf-8')
    expect(playlistContent).toContain('result.queueFull')
    expect(playlistContent).toContain('队列已满')
  })

  it('R-09b: SettingsView synchronizes only the transcode baseline after reset defaults', () => {
    const settingsContent = fs.readFileSync(path.join(viewsDir, 'admin/SettingsView.vue'), 'utf-8')
    expect(settingsContent).toMatch(/canonicalizeSettings\(result\.value\)/)
    expect(settingsContent).toContain('mergeSettingsSection(form, settings, TRANSCODE_SETTING_KEYS)')
    expect(settingsContent).toContain('mergeSettingsSection(baseline, form, TRANSCODE_SETTING_KEYS)')
  })

  it('metadata review invalidates stale responses and commits only to the captured target', () => {
    const reviewContent = fs.readFileSync(path.join(viewsDir, 'admin/MetadataScrapeView.vue'), 'utf-8')
    expect(reviewContent).toContain('createMetadataReviewRequestGate')
    expect(reviewContent).toContain('reviewRequestGate.invalidate()')
    expect(reviewContent).toContain('reviewRequestGate.begin(targetKey)')
    expect(reviewContent).toContain('if(!current())return')
  })

  it('AI repair batch reports executor-rejected tasks while retaining the batch ID', () => {
    const taskPanelContent = fs.readFileSync(path.join(__dirname, '../components/admin/AiTaskPanel.vue'), 'utf-8')
    expect(taskPanelContent).toContain('result.dispatchRejected')
    expect(taskPanelContent).toContain('未能入队')
  })
})
