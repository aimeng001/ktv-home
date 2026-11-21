import { describe, expect, it } from 'vitest'
import {
  AUDIO_CHANNEL_OPTIONS,
  AUDIO_LAYOUT_OPTIONS,
  AUDIO_LAYOUT_SOURCE_LABELS,
  audioLayoutLabel,
  audioLayoutSourceLabel,
  createAudioLayoutDraft
} from './audioLayout'

describe('audio layout contract', () => {
  it('offers the supported layouts and independent channel choices', () => {
    expect(AUDIO_LAYOUT_OPTIONS.map(option => option.value)).toEqual([
      'NORMAL_STEREO', 'DUAL_TRACK', 'DUAL_CHANNEL'
    ])
    expect(AUDIO_CHANNEL_OPTIONS.map(option => option.value)).toEqual(['LEFT', 'RIGHT'])
  })

  it('uses a safe stereo draft for absent or unknown layouts', () => {
    expect(createAudioLayoutDraft()).toEqual({
      layout: 'NORMAL_STEREO',
      originalTrackIndex: null,
      accompanimentTrackIndex: null,
      originalChannel: 'LEFT',
      accompanimentChannel: 'RIGHT'
    })
    expect(createAudioLayoutDraft({ layout: 'FUTURE_LAYOUT' }).layout).toBe('NORMAL_STEREO')
  })

  it('preserves valid track indexes including zero and defaults missing channel values', () => {
    expect(createAudioLayoutDraft({
      layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 2,
      originalChannel: '', accompanimentChannel: null
    })).toEqual({
      layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 2,
      originalChannel: 'LEFT', accompanimentChannel: 'RIGHT'
    })
  })

  it('returns a readable label for known and unknown layouts', () => {
    expect(audioLayoutLabel({ layout: 'DUAL_TRACK' })).toBe('双独立音轨')
    expect(audioLayoutLabel({ layout: 'DUAL_CHANNEL' })).toBe('单音轨左右声道')
    expect(audioLayoutLabel({ layout: 'OLD_LAYOUT' })).toBe('普通立体声')
    expect(audioLayoutLabel(null)).toBe('普通立体声')
  })

  it('keeps provenance out of the writable draft', () => {
    const draft = createAudioLayoutDraft({
      layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1, source: 'MANUAL'
    })

    // source 是只读来源标记：写进 draft 就会被 PUT 到 /audio-layout 当业务字段提交。
    expect(draft).not.toHaveProperty('source')
  })

  it('labels the three provenance sources and stays silent for unknown ones', () => {
    expect(AUDIO_LAYOUT_SOURCE_LABELS).toEqual({
      LEGACY: '历史数据（未确认）',
      AUTO_DEFAULT: '自动识别',
      MANUAL: '管理员手动设置'
    })
    expect(audioLayoutSourceLabel('MANUAL')).toBe('管理员手动设置')
    expect(audioLayoutSourceLabel('AUTO_DEFAULT')).toBe('自动识别')
    expect(audioLayoutSourceLabel('LEGACY')).toBe('历史数据（未确认）')
    expect(audioLayoutSourceLabel()).toBe('')
    expect(audioLayoutSourceLabel(null)).toBe('')
    expect(audioLayoutSourceLabel('FUTURE_SOURCE')).toBe('')
  })
})
