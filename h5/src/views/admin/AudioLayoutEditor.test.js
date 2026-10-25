// @vitest-environment jsdom
import { createApp, defineComponent, h, nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import api from '../../api/client'
import AudioLayoutEditor from './AudioLayoutEditor.vue'

vi.mock('../../api/client', () => ({
  default: {
    adminUpdateAudioLayout: vi.fn(),
    adminSwapAudioLayout: vi.fn()
  }
}))

describe('AudioLayoutEditor guarded actions', () => {
  it('does not save or swap when the draft or file identity is unavailable', async () => {
    const { app, actions } = mountEditor({
      fileId: null, title: '无文件歌曲', audioTracks: 0, audioLayout: { layout: 'NORMAL_STEREO' }
    })
    try {
      await actions.save()
      actions.openEditor()
      await actions.save()
      await actions.swap()
      actions.draft = { ...actions.draft, layout: 'DUAL_CHANNEL' }
      await actions.swap()

      expect(api.adminUpdateAudioLayout).not.toHaveBeenCalled()
      expect(api.adminSwapAudioLayout).not.toHaveBeenCalled()
    } finally {
      app.unmount()
    }
  })

  it('does not swap when a layout does not support vocal switching', async () => {
    const { app, actions, host } = mountEditor({
      fileId: 9, title: '普通立体声', audioTracks: 2, audioLayout: { layout: 'NORMAL_STEREO' }
    })
    try {
      expect(host.querySelector('.audio-layout-trigger').disabled).toBe(false)
      actions.openEditor()
      await actions.swap()

      expect(api.adminSwapAudioLayout).not.toHaveBeenCalled()
    } finally {
      app.unmount()
    }
  })

  it('treats an invalid audio-track count as an empty track list', async () => {
    const { app, actions, host } = mountEditor({
      fileId: 10, title: '缺少音轨信息', audioTracks: 'invalid', audioLayout: { layout: 'DUAL_TRACK' }
    })
    try {
      actions.openEditor()
      await nextTick()

      expect(host.querySelector('.audio-layout-hint').textContent).toContain('0 条音轨')
      expect(host.querySelectorAll('.audio-layout-field select option')).toHaveLength(3)
    } finally {
      app.unmount()
    }
  })

  it('swaps and saves a valid dual-track layout through the service', async () => {
    const song = { fileId: 12, title: '双音轨歌曲', artist: '歌手', audioTracks: 2, audioLayout: { layout: 'DUAL_TRACK' } }
    const { app, actions, updated } = mountEditor(song)
    const swapped = { layout: 'DUAL_TRACK', originalTrackIndex: 1, accompanimentTrackIndex: 0 }
    const saved = { layout: 'NORMAL_STEREO' }
    api.adminSwapAudioLayout.mockResolvedValue(swapped)
    api.adminUpdateAudioLayout.mockResolvedValue(saved)

    try {
      actions.openEditor()
      expect(actions.draft.originalTrackIndex).toBe(0)
      expect(actions.draft.accompanimentTrackIndex).toBe(1)

      await actions.swap()
      expect(api.adminSwapAudioLayout).toHaveBeenCalledWith(12)
      expect(actions.draft.originalTrackIndex).toBe(1)
      expect(actions.draft.accompanimentTrackIndex).toBe(0)
      expect(updated).toHaveBeenCalledWith(swapped)

      await actions.save()
      expect(api.adminUpdateAudioLayout).toHaveBeenCalledWith(12, expect.objectContaining(swapped))
      expect(updated).toHaveBeenLastCalledWith(saved)
      expect(actions.open).toBe(false)
      expect(actions.saving).toBe(false)
    } finally {
      app.unmount()
    }
  })

  it('shows service errors and safe fallback messages while keeping the editor open', async () => {
    const { app, actions, host } = mountEditor({
      fileId: 18, title: '左右声道歌曲', audioTracks: 2, audioLayout: { layout: 'DUAL_CHANNEL' }
    })
    api.adminUpdateAudioLayout
      .mockRejectedValueOnce(new Error('保存被服务拒绝'))
      .mockRejectedValueOnce({})
    api.adminSwapAudioLayout
      .mockRejectedValueOnce(new Error('交换被服务拒绝'))
      .mockRejectedValueOnce({})

    try {
      actions.openEditor()
      expect(host.querySelector('.audio-layout-trigger').disabled).toBe(false)

      await actions.save()
      expect(host.querySelector('[role="alert"]').textContent).toBe('保存被服务拒绝')
      expect(actions.open).toBe(true)
      expect(actions.saving).toBe(false)

      await actions.save()
      expect(host.querySelector('[role="alert"]').textContent).toBe('音频布局保存失败')

      await actions.swap()
      expect(host.querySelector('[role="alert"]').textContent).toBe('交换被服务拒绝')

      await actions.swap()
      expect(host.querySelector('[role="alert"]').textContent).toBe('原唱/伴唱交换失败')
      expect(actions.open).toBe(true)
      expect(actions.saving).toBe(false)
    } finally {
      app.unmount()
    }
  })

  it('keeps the editor open when a close is requested during a save', async () => {
    let finishSave
    api.adminUpdateAudioLayout.mockReturnValueOnce(new Promise(resolve => { finishSave = resolve }))
    const { app, host } = mountEditor({
      fileId: 19, title: '保存中的歌曲', audioTracks: 2, audioLayout: { layout: 'DUAL_CHANNEL' }
    })

    try {
      host.querySelector('.audio-layout-trigger').click()
      await nextTick()
      host.querySelector('.audio-layout-close').click()
      await nextTick()
      expect(host.querySelector('.audio-layout-modal')).toBeNull()

      host.querySelector('.audio-layout-trigger').click()
      await nextTick()
      host.querySelector('.audio-layout-primary').click()
      await nextTick()

      expect(host.querySelector('.audio-layout-primary').textContent).toContain('保存中')
      host.querySelector('.audio-layout-close').click()
      await nextTick()
      expect(host.querySelector('.audio-layout-modal')).not.toBeNull()

      finishSave({ layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' })
      await Promise.resolve()
      await nextTick()
      expect(host.querySelector('.audio-layout-modal')).toBeNull()
    } finally {
      finishSave?.({ layout: 'DUAL_CHANNEL', originalChannel: 'LEFT', accompanimentChannel: 'RIGHT' })
      app.unmount()
    }
  })
})

function mountEditor(song) {
  const host = document.createElement('div')
  const updated = vi.fn()
  const app = createApp(defineComponent({
    render: () => h(AudioLayoutEditor, { song, onUpdated: updated })
  }))
  app.mount(host)
  return { app, actions: app._instance.subTree.component.setupState, host, updated }
}
