import { describe, expect, it, vi } from 'vitest'
import { canonicalizeSettings, releaseLabel, saveDirtySections, loadSettingsSections, canSaveSection, runSettingsAction, editableSettingsPayload, normalizeStandbySongIds, exceedsStandbySongLimit, describeSaveFailures, MAX_STANDBY_SONGS } from './settingsState'

describe('admin settings state', () => {
  it('strips internal runtime state from form submission payload', () => {
    const dirtyForm = {
      display_address: '192.168.1.100',
      standby_interval_sec: 10,
      room_host_user_id: 123,
      standby_logo_path: 'standby/logo.png',
      standby_logo_configured: true,
      transcode_hardware_auto_configured: true
    }
    const payload = editableSettingsPayload(dirtyForm)
    expect(payload).toEqual({
      display_address: '192.168.1.100',
      standby_interval_sec: 10
    })
  })

  it('uses display_address as the canonical key while reading legacy qr_address', () => {
    expect(canonicalizeSettings({ qr_address: '192.168.1.10:8080' })).toEqual({
      display_address: '192.168.1.10:8080'
    })
    expect(canonicalizeSettings({ display_address: 'canonical', qr_address: 'legacy' })).toEqual({
      display_address: 'canonical'
    })
  })

  /**
   * 后端 putEditable 在 standby_song_ids 超过 100 或含重复时整包拒绝（400）。
   * 前端必须在提交前对齐同一套约束，否则管理员只会看到「基础设置 保存失败」。
   */
  it('normalizes standby song ids to the server limit', () => {
    const dirty = Array.from({ length: 250 }, (_, index) => index + 1).concat([7, 'abc', -3, 2.5])

    const normalized = normalizeStandbySongIds(dirty)

    expect(normalized).toHaveLength(MAX_STANDBY_SONGS)
    expect(normalized[0]).toBe(1)
    expect(normalized[MAX_STANDBY_SONGS - 1]).toBe(100)
    expect(normalizeStandbySongIds('12, 34， 12 56')).toEqual([12, 34, 56])
    expect(normalizeStandbySongIds(undefined)).toEqual([])
    expect(exceedsStandbySongLimit(dirty)).toBe(true)
    expect(exceedsStandbySongLimit([1, 2, 3])).toBe(false)
  })

  it('normalizes standby song ids while reading settings so the payload stays writable', () => {
    const canonical = canonicalizeSettings({
      standby_song_ids: Array.from({ length: 130 }, (_, index) => index + 1)
    })

    expect(canonical.standby_song_ids).toHaveLength(MAX_STANDBY_SONGS)
  })

  /**
   * 保存失败提示原先只输出分区名，把后端的具体原因（如"最多包含 100 首歌曲"）丢掉了，
   * 管理员无法定位真正出错的设置项。
   */
  it('surfaces the underlying reason when a settings section fails to save', () => {
    expect(describeSaveFailures([
      { name: '基础设置', error: new Error('standby_song_ids 最多包含 100 首歌曲') }
    ])).toBe('基础设置（standby_song_ids 最多包含 100 首歌曲）')
    expect(describeSaveFailures([{ name: 'AI 设置' }])).toBe('AI 设置')
    expect(describeSaveFailures([])).toBe('')
  })

  it('renders the server release version instead of a hardcoded version', () => {
    expect(releaseLabel({ version: '1.0.9' })).toBe('home-ktv v1.0.9')
    expect(releaseLabel({})).toBe('版本信息不可用')
  })

  it('saves only dirty sections and reports partial failures without hiding successes', async () => {
    const saveGeneral = vi.fn().mockResolvedValue({ ok: true })
    const saveAi = vi.fn().mockRejectedValue(new Error('AI 不可用'))
    const saveMusic = vi.fn().mockResolvedValue({ ok: true })

    const result = await saveDirtySections([
      { name: '基础设置', dirty: true, save: saveGeneral },
      { name: 'AI 设置', dirty: true, save: saveAi },
      { name: '音乐元数据设置', dirty: false, save: saveMusic }
    ])

    expect(saveGeneral).toHaveBeenCalledTimes(1)
    expect(saveAi).toHaveBeenCalledTimes(1)
    expect(saveMusic).not.toHaveBeenCalled()
    expect(result.successes.map(item => item.name)).toEqual(['基础设置'])
    expect(result.failures.map(item => item.name)).toEqual(['AI 设置'])
  })

  it('keeps successful settings sections when another section fails to load', async () => {
    const result = await loadSettingsSections({
      general: () => Promise.resolve({ display_address: '192.168.1.20:54001' }),
      ai: () => Promise.reject(new Error('AI 服务不可用')),
      music: () => Promise.resolve({ enabled: false })
    })

    expect(result.values.general).toEqual({ display_address: '192.168.1.20:54001' })
    expect(result.values.music).toEqual({ enabled: false })
    expect(result.states).toEqual({ general: 'ready', ai: 'error', music: 'ready' })
    expect(result.errors.ai).toBeInstanceOf(Error)
  })

  it('blocks a dirty section whose initial read failed', async () => {
    const saveAi = vi.fn()
    const result = await saveDirtySections([
      { name: 'AI 设置', dirty: true, state: 'error', save: saveAi },
      { name: '基础设置', dirty: true, state: 'ready', save: vi.fn().mockResolvedValue({ ok: true }) }
    ])

    expect(canSaveSection(true, 'error')).toBe(false)
    expect(canSaveSection(true, 'ready')).toBe(true)
    expect(saveAi).not.toHaveBeenCalled()
    expect(result.successes.map(item => item.name)).toEqual(['基础设置'])
    expect(result.failures).toHaveLength(1)
    expect(result.failures[0].blocked).toBe(true)
  })

  it('converts a settings action failure into an explicit result', async () => {
    const result = await runSettingsAction(() => Promise.reject(new Error('权限不足')))

    expect(result).toEqual({ ok: false, error: expect.any(Error) })
    expect(result.error.message).toBe('权限不足')
  })
})
