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

/**
 * Load independent settings sections without allowing one failed request to
 * turn the whole page into a set of plausible-looking defaults.
 */
export async function loadSettingsSections(loaders = {}) {
  const values = {}
  const states = Object.fromEntries(Object.keys(loaders).map(name => [name, 'loading']))
  const errors = {}
  await Promise.all(Object.entries(loaders).map(async ([name, loader]) => {
    try {
      values[name] = await loader()
      states[name] = 'ready'
    } catch (error) {
      states[name] = 'error'
      errors[name] = error
    }
  }))
  return { values, states, errors }
}

export function canSaveSection(dirty, state) {
  return Boolean(dirty) && state === 'ready'
}

export async function runSettingsAction(action) {
  try {
    return { ok: true, value: await action() }
  } catch (error) {
    return { ok: false, error }
  }
}

export async function saveDirtySections(sections) {
  const successes = []
  const failures = []
  for (const section of sections) {
    if (!section.dirty) continue
    if (section.state && !canSaveSection(section.dirty, section.state)) {
      failures.push({
        name: section.name,
        blocked: true,
        error: new Error(section.errorMessage || '该设置分区尚未成功加载，暂不能保存')
      })
      continue
    }
    try {
      const value = await section.save()
      successes.push({ name: section.name, value })
    } catch (error) {
      failures.push({ name: section.name, error })
    }
  }
  return { successes, failures }
}
