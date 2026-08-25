import { describe, expect, it } from 'vitest'
import {
  AUDIO_CHANNEL_OPTIONS,
  AUDIO_LAYOUT_OPTIONS,
  createAudioLayoutDraft,
  swapAudioLayout
} from './audioLayout'

describe('admin audio layout editor contract', () => {
  it('exposes all supported layouts and safe left/right defaults', () => {
    expect(AUDIO_LAYOUT_OPTIONS.map(item => item.value)).toEqual([
      'NORMAL_STEREO', 'DUAL_TRACK', 'DUAL_CHANNEL'
    ])
    expect(AUDIO_CHANNEL_OPTIONS.map(item => item.value)).toEqual(['LEFT', 'RIGHT'])
    expect(createAudioLayoutDraft({ layout: 'DUAL_CHANNEL' })).toMatchObject({
      layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT'
    })
  })

  it('swaps channel semantics and track semantics independently', () => {
    expect(swapAudioLayout({
      layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT'
    })).toMatchObject({ originalChannel: 'RIGHT', accompanimentChannel: 'LEFT' })
    expect(swapAudioLayout({
      layout: 'DUAL_TRACK', originalTrackIndex: 0, accompanimentTrackIndex: 1
    })).toMatchObject({ originalTrackIndex: 1, accompanimentTrackIndex: 0 })
  })

  it('does not invent a swap for normal stereo', () => {
    const layout = { layout: 'NORMAL_STEREO', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' }
    expect(swapAudioLayout(layout)).toEqual(layout)
  })
})
