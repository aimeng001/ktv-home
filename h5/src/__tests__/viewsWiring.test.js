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
})
