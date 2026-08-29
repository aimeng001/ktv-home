export function canonicalizeSettings(settings = {}) {
  const result = { ...settings }
  if (!String(result.display_address ?? '').trim()) {
    result.display_address = result.qr_address ?? ''
  }
  delete result.qr_address
  return result
}

export function releaseLabel(release = {}) {
  return release.version ? `home-ktv v${release.version}` : '版本信息不可用'
}

export async function saveDirtySections(sections) {
  const successes = []
  const failures = []
  for (const section of sections) {
    if (!section.dirty) continue
    try {
      const value = await section.save()
      successes.push({ name: section.name, value })
    } catch (error) {
      failures.push({ name: section.name, error })
    }
  }
  return { successes, failures }
}
