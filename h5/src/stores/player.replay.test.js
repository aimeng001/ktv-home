// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePlayerStore } from './player'

function createFakeSocket() {
  return {
    sent: [],
    pending: [],
    open: false,
    send(message) {
      if (!this.open) return false
      this.sent.push(message)
      return true
    },
    queueReport(descriptor) { this.pending.push(descriptor) },
    clearPendingReports() { this.pending = [] },
    replayPending(replay) {
      const queued = this.pending
      this.pending = []
      queued.forEach(replay)
      return queued.length
    }
  }
}

describe('player 上报重放', () => {
  let player
  let socket

  beforeEach(() => {
    setActivePinia(createPinia())
    player = usePlayerStore()
    socket = createFakeSocket()
    player.socket = socket
    player.tvOnline = false
    player.modeOverride = 'auto'
    player.webPlayerRole = 'ACTIVE'
    player.webPlayerGeneration = 3
  })

  it('断线时上报失败只入队 queueId，不存带 generation 的消息体', () => {
    socket.open = false

    const sent = player.reportFinished(9)

    expect(sent).toBe(false)
    expect(socket.pending).toEqual([{ kind: 'finished', queueId: 9 }])
  })

  it('重连后重放使用当前 generation，而不是断线时的旧值', () => {
    socket.open = false
    player.reportFinished(9)                       // 断线：generation=3 的消息被丢弃并入队

    socket.open = true
    player.webPlayerRole = 'STANDBY'
    player.webPlayerGeneration = 0
    player.handleEvent('player_role', { role: 'ACTIVE', generation: 7 })

    expect(socket.pending).toHaveLength(0)
    expect(socket.sent).toHaveLength(1)
    expect(socket.sent[0]).toMatchObject({
      type: 'finished',
      payload: { queue_id: 9, generation: 7 }
    })
  })

  it('重连后变成 STANDBY 时丢弃待重放条目，不报错', () => {
    socket.open = false
    player.reportFinished(9)

    player.handleEvent('player_role', { role: 'STANDBY', generation: 0 })

    expect(socket.pending).toHaveLength(0)
    expect(socket.sent).toHaveLength(0)
  })

  it('电视上线（快照 tvOnline=true）时丢弃待重放条目', () => {
    socket.open = false
    player.reportFinished(9)

    player.handleEvent('sync_full', {
      list: [], state: 'playing', volume: 60, muted: false,
      vocalMode: 'accompaniment', tvOnline: true, connectedPhones: 1
    })

    expect(socket.pending).toHaveLength(0)
  })

  it('播放错误上报失败同样入队，并保留 fileId 与文案', () => {
    socket.open = false

    const sent = player.reportPlayError(55, 9, '解码失败')

    expect(sent).toBe(false)
    expect(socket.pending).toEqual([{ kind: 'play_error', queueId: 9, fileId: 55, message: '解码失败' }])
  })

  it('断线后 onStatus(false) 即使把 webPlayerRole 置为 STANDBY 且清零 stateRevision，仍可入队并在重连后重放', () => {
    socket.onStatus = null
    player.connected = true
    player.stateRevision = 88
    player.webPlayerRole = 'ACTIVE'
    player.webPlayerGeneration = 3

    // 模拟真实 KtvSocket 断连回调把 connected 置 false、角色重置并清空 stateRevision
    player.connected = false
    player.wasWebHostBeforeDisconnect = true
    player.stateRevision = 0
    player.webPlayerRole = 'STANDBY'
    player.webPlayerGeneration = 0

    expect(player.reportFinished(12)).toBe(false)
    expect(player.reportPlayError(77, 13, '断网后错误')).toBe(false)
    expect(socket.pending).toEqual([
      { kind: 'finished', queueId: 12 },
      { kind: 'play_error', queueId: 13, fileId: 77, message: '断网后错误' },
    ])

    socket.open = true
    player.connected = true
    player.handleEvent('player_role', { role: 'ACTIVE', generation: 9 })

    expect(player.wasWebHostBeforeDisconnect).toBe(false)
    expect(socket.pending).toHaveLength(0)
    expect(socket.sent).toEqual([
      { type: 'finished', payload: { queue_id: 12, generation: 9 } },
      { type: 'play_error', payload: { file_id: 77, queue_id: 13, message: '断网后错误', generation: 9 } },
    ])
  })
})
