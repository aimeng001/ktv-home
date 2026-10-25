import { describe, expect, it } from 'vitest'
import {
  AUDIO_CHANNEL_OPTIONS,
  AUDIO_LAYOUT_OPTIONS,
  audioLayoutLabel,
  createAudioLayoutDraft,
  swapAudioLayout
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

  it('swaps dual-track indexes without mutating the input', () => {
    const layout = { layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 }

    expect(swapAudioLayout(layout)).toEqual({
      layout: 'DUAL_TRACK', originalTrackIndex: 1, accompanimentTrackIndex: 0
    })
    expect(layout).toEqual({ layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1 })
  })

  it('swaps dual-channel roles and leaves ordinary stereo unchanged', () => {
    expect(swapAudioLayout({ layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' }))
      .toEqual({ layout: 'DUAL_CHANNEL', originalChannel: 'RIGHT', accompanimentChannel: 'LEFT' })
    const stereo = { layout: 'NORMAL_STEREO', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' }
    expect(swapAudioLayout(stereo)).toEqual(stereo)
    expect(swapAudioLayout(stereo)).not.toBe(stereo)
  })

  it('returns a readable label for known and unknown layouts', () => {
    expect(audioLayoutLabel({ layout: 'DUAL_TRACK' })).toBe('双独立音轨')
    expect(audioLayoutLabel({ layout: 'DUAL_CHANNEL' })).toBe('单音轨左右声道')
    expect(audioLayoutLabel({ layout: 'OLD_LAYOUT' })).toBe('普通立体声')
    expect(audioLayoutLabel(null)).toBe('普通立体声')
  })
})
