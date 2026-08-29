import { describe, expect, it, vi } from 'vitest'
import { canonicalizeSettings, releaseLabel, saveDirtySections } from './settingsState'

describe('admin settings state', () => {
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
})
