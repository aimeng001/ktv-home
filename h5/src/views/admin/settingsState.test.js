import { describe, expect, it, vi } from 'vitest'
import { canonicalizeSettings, releaseLabel, saveDirtySections, loadSettingsSections, canSaveSection, runSettingsAction, editableSettingsPayload } from './settingsState'

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
