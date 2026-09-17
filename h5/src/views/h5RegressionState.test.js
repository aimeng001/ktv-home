import { describe, expect, it, vi } from 'vitest'
import { createLatestRequest } from './latestRequest'
import { loadAllFavoritePages } from './favoritesState'
import { recordScanPollFailure, recordScanPollSuccess } from './admin/dashboardPollState'
import { mergeSettingsSection, canStartSettingsSave } from './admin/settingsState'
import { buildSongEditPayload } from './admin/editSongState'
import { loadAiTaskData } from './admin/aiTaskState'

describe('H5 regression state policies', () => {
  it('does not accept a late playlist response after a route change', () => {
    const requests = createLatestRequest()
    const first = requests.begin()
    const second = requests.begin()

    expect(requests.isCurrent(first.id)).toBe(false)
    expect(requests.isCurrent(second.id)).toBe(true)
    expect(first.signal.aborted).toBe(true)
  })

  it('loads every favorite page until the server returns a short page', async () => {
    const loadPage = vi.fn()
      .mockResolvedValueOnce(Array.from({ length: 100 }, (_, i) => ({ id: i + 1 })))
      .mockResolvedValueOnce([{ id: 101 }])

    await expect(loadAllFavoritePages(loadPage, 100)).resolves.toEqual(
      Array.from({ length: 100 }, (_, i) => ({ id: i + 1 })).concat({ id: 101 })
    )
    expect(loadPage).toHaveBeenNthCalledWith(1, 0, 100)
    expect(loadPage).toHaveBeenNthCalledWith(2, 1, 100)
  })

  it('stops scan polling after consecutive progress failures', () => {
    let state = { failures: 0, running: true }
    state = recordScanPollFailure(state, new Error('503'))
    state = recordScanPollFailure(state, new Error('503'))
    state = recordScanPollFailure(state, new Error('503'))

    expect(state.stop).toBe(true)
    expect(state.running).toBe(false)
    expect(state.error.message).toBe('503')
    expect(recordScanPollSuccess(state, { running: true }).failures).toBe(0)
  })

  it('merges only the requested settings section', () => {
    const current = { display_address: 'local', transcode_video_codec: 'h264' }
    const server = { display_address: 'server', transcode_video_codec: 'hevc' }

    expect(mergeSettingsSection(current, server, ['transcode_video_codec']))
      .toEqual({ display_address: 'local', transcode_video_codec: 'hevc' })
  })

  it('rejects a second settings save while one save is active', () => {
    expect(canStartSettingsSave(false)).toBe(true)
    expect(canStartSettingsSave(true)).toBe(false)
  })

  it('does not submit derived pinyin fields in a song edit', () => {
    expect(buildSongEditPayload({
      title: '晴天', artist: '周杰伦', language: '国语', tags: '流行, 经典', pinyin: 'WR'
    })).toEqual({
      title: '晴天', artist: '周杰伦', language: '国语', tags: ['流行', '经典']
    })
  })

  it('keeps AI task/config failures observable instead of turning them into empty data', async () => {
    const result = await loadAiTaskData(
      () => Promise.reject(new Error('任务服务不可用')),
      () => Promise.resolve({ enabled: true })
    )

    expect(result.tasks).toEqual([])
    expect(result.config).toEqual({ enabled: true })
    expect(result.errors.tasks.message).toBe('任务服务不可用')
  })
})
