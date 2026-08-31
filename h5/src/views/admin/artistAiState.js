/**
 * Gate artist AI actions on a trustworthy configuration read. A transport
 * failure must not be treated as an unconfigured account.
 */
export async function resolveAiConfiguration(loadConfig, { onError = async () => {}, onRedirect = async () => {} } = {}) {
  let config
  try {
    config = await loadConfig()
  } catch (error) {
    await onError(error?.message || 'AI 配置读取失败')
    return null
  }
  if (config?.enabled && config?.apiKeyConfigured && config?.baseUrl && config?.bulkModel) return config
  await onError('批量 AI 分析需要先配置并启用 AI 模型。')
  await onRedirect()
  return null
}
