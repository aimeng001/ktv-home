export async function loadAiTaskData(loadTasks, loadConfig) {
  const [tasksResult, configResult] = await Promise.allSettled([loadTasks(), loadConfig()])
  return {
    tasks: tasksResult.status === 'fulfilled' && Array.isArray(tasksResult.value) ? tasksResult.value : [],
    config: configResult.status === 'fulfilled' && configResult.value ? configResult.value : {},
    errors: {
      tasks: tasksResult.status === 'rejected' ? tasksResult.reason : null,
      config: configResult.status === 'rejected' ? configResult.reason : null
    }
  }
}
