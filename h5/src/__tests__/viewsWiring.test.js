import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('H5 Views Wiring & Error Contracts', () => {
  const viewsDir = path.resolve(__dirname, '../views')
  const ktvDir = path.resolve(__dirname, '../components/ktv')

  it('R-05: AiLibraryView mounts AiTaskPanel with correct import path', () => {
    const aiViewContent = fs.readFileSync(path.join(viewsDir, 'admin/AiLibraryView.vue'), 'utf-8')
    expect(aiViewContent).toContain("import AiTaskPanel from '../../components/admin/AiTaskPanel.vue'")
    expect(aiViewContent).toMatch(/<AiTaskPanel\b/)
  })

  it('KTV kiosk mounts header, bento/browser, bottom dock, media surface, drawer, and modals', () => {
    const kioskContent = fs.readFileSync(path.join(viewsDir, 'KtvKioskView.vue'), 'utf-8')
    expect(kioskContent).toContain('<KtvHeaderBar')
    expect(kioskContent).toContain('<KtvBentoDashboard')
    expect(kioskContent).toContain('<KtvThreeColumnBrowser')
    expect(kioskContent).toContain('<KtvBottomDock')
    expect(kioskContent).toContain('<KtvMediaSurface')
    expect(kioskContent).toContain('<KtvQueueDrawer')
    expect(kioskContent).toContain('<KtvModals')
  })

  it('KtvThreeColumnBrowser includes error retry, tri-state tvOnline drawer notice, and top ordering', () => {
    const browserContent = fs.readFileSync(path.join(ktvDir, 'KtvThreeColumnBrowser.vue'), 'utf-8')
    const drawerContent = fs.readFileSync(path.join(ktvDir, 'KtvQueueDrawer.vue'), 'utf-8')
    expect(browserContent).toContain('@click="reloadActive"')
    expect(browserContent).toContain('orderTopSong')
    expect(drawerContent).toContain('player.tvOnline === false')
  })

  it('KtvMediaSurface suppresses stale liveTranscode progress during source reload and exposes fullscreen navigation', () => {
    const mediaContent = fs.readFileSync(path.join(ktvDir, 'KtvMediaSurface.vue'), 'utf-8')
    const headerContent = fs.readFileSync(path.join(ktvDir, 'KtvHeaderBar.vue'), 'utf-8')
    expect(mediaContent).toContain('awaitingMediaReload')
    expect(mediaContent).toContain("$emit('open-browser')")
    expect(mediaContent).toContain("$emit('open-home')")
    expect(headerContent).toContain('player.connected')
  })

  it('R-22: SourceLibraryView and KtvLibraryView contain error state notice and retry', () => {
    const sourceContent = fs.readFileSync(path.join(viewsDir, 'admin/SourceLibraryView.vue'), 'utf-8')
    expect(sourceContent).toContain('loadError')
    expect(sourceContent).toMatch(/class="notice error-notice"/)

    const ktvContent = fs.readFileSync(path.join(viewsDir, 'admin/KtvLibraryView.vue'), 'utf-8')
    expect(ktvContent).toContain('loadError')
    expect(ktvContent).toMatch(/class="notice error-notice"/)
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
