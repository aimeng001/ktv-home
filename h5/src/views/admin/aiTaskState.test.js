import { describe, expect, it, vi } from 'vitest'
import { loadAiTaskData } from './aiTaskState'

describe('loadAiTaskData', () => {
  it('loads valid task arrays and configuration concurrently', async () => {
    const tasks = [{ id: 1 }]
    const config = { enabled: true }
    let resolveTasks
    let resolveConfig
    const loadTasks = vi.fn(() => new Promise(resolve => { resolveTasks = resolve }))
    const loadConfig = vi.fn(() => new Promise(resolve => { resolveConfig = resolve }))

    const result = loadAiTaskData(loadTasks, loadConfig)
    expect(loadTasks).toHaveBeenCalledOnce()
    expect(loadConfig).toHaveBeenCalledOnce()
    resolveConfig(config)
    resolveTasks(tasks)

    await expect(result).resolves.toEqual({
      tasks,
      config,
      errors: { tasks: null, config: null }
    })
  })

  it('uses an empty task list when the fulfilled response is not an array', async () => {
    await expect(loadAiTaskData(async () => ({ items: [] }), async () => null)).resolves.toEqual({
      tasks: [],
      config: {},
      errors: { tasks: null, config: null }
    })
  })

  it('retains each rejected request as an error and falls back independently', async () => {
    const taskError = new Error('tasks unavailable')
    const configError = new Error('config unavailable')
    await expect(loadAiTaskData(
      async () => { throw taskError },
      async () => { throw configError }
    )).resolves.toEqual({
      tasks: [],
      config: {},
      errors: { tasks: taskError, config: configError }
    })
  })

  it('keeps successful data when the other request fails', async () => {
    const tasks = [{ id: 2 }]
    const configError = new Error('config unavailable')
    await expect(loadAiTaskData(
      async () => tasks,
      async () => { throw configError }
    )).resolves.toEqual({
      tasks,
      config: {},
      errors: { tasks: null, config: configError }
    })
  })
})
