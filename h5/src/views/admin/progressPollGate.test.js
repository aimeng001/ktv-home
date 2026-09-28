import { describe, expect, it } from 'vitest'
import { createProgressPollGate } from './progressPollGate'

describe('progress poll gate', () => {
  it('allows only one request at a time and queues one follow-up poll', () => {
    const gate = createProgressPollGate()
    const first = gate.begin()

    expect(gate.begin()).toBeNull()
    expect(gate.finish(first)).toBe(true)

    const followUp = gate.begin()
    expect(gate.isCurrent(followUp)).toBe(true)
    expect(gate.finish(followUp)).toBe(false)
  })

  it('invalidates an older response and requests a fresh poll for the new generation', () => {
    const gate = createProgressPollGate()
    const oldRequest = gate.begin()

    gate.invalidate({ queueLatest: true })

    expect(gate.isCurrent(oldRequest)).toBe(false)
    expect(gate.finish(oldRequest)).toBe(true)
    const newRequest = gate.begin()
    expect(gate.isCurrent(newRequest)).toBe(true)
  })

  it('drops queued work when polling is stopped', () => {
    const gate = createProgressPollGate()
    const request = gate.begin()
    expect(gate.begin()).toBeNull()

    gate.invalidate()

    expect(gate.isCurrent(request)).toBe(false)
    expect(gate.finish(request)).toBe(false)
  })
})
