export const EDITABLE_GENERAL_KEYS = [
  'library_watch_enabled',
  'display_address',
  'external_default_audio_layout',
  'delete_source_after_transcode',
  'tv_video_scale_mode',
  'standby_carousel',
  'standby_source',
  'standby_song_ids',
  'anti_burn',
  'mini_qr',
  'standby_welcome',
  'standby_subtitle',
  'standby_interval_sec',
  'direct_copy_containers',
  'direct_copy_video_codecs',
  'direct_copy_audio_codecs',
  'transcode_audio_only',
  'transcode_output_container',
  'transcode_video_codec',
  'transcode_audio_codec',
  'transcode_hardware_acceleration'
]

export function editableSettingsPayload(settings = {}) {
  const payload = {}
  for (const key of EDITABLE_GENERAL_KEYS) {
    if (Object.prototype.hasOwnProperty.call(settings, key)) {
      payload[key] = settings[key]
    }
  }
  return payload
}

export function isAiConfigured(config) {
  return Boolean(config?.enabled && config?.baseUrl && config?.bulkModel)
}

/** 与后端 SettingService.MAX_STANDBY_SONGS 保持一致。 */
export const MAX_STANDBY_SONGS = 100

export const TRANSCODE_SETTING_KEYS = [
  'direct_copy_containers',
  'direct_copy_video_codecs',
  'direct_copy_audio_codecs',
  'transcode_audio_only',
  'transcode_output_container',
  'transcode_video_codec',
  'transcode_audio_codec',
  'transcode_hardware_acceleration'
]

export function mergeSettingsSection(current = {}, server = {}, keys = []) {
  const merged = { ...current }
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(server, key)) merged[key] = server[key]
  }
  return merged
}

export function canStartSettingsSave(saving) {
  return !saving
}

/** 解析逗号/空白分隔的文本或数组，产出合法待机歌曲 ID（正整数、保序、去重）。 */
function collectStandbySongIds(value) {
  const raw = Array.isArray(value) ? value : String(value ?? '').split(/[,，\s]+/)
  const ids = []
  const seen = new Set()
  for (const item of raw) {
    const id = Number(item)
    if (!Number.isInteger(id) || id <= 0 || seen.has(id)) continue
    seen.add(id)
    ids.push(id)
  }
  return ids
}

/**
 * 服务端 putEditable 会在 standby_song_ids 超过上限或含重复时整包 400。
 * 前端在提交与加载时都用同一套约束，避免"库里陈旧脏值导致任何基础设置都保存不了"。
 */
export function normalizeStandbySongIds(value) {
  return collectStandbySongIds(value).slice(0, MAX_STANDBY_SONGS)
}

/** 归一化过程中是否发生了截断，用于给管理员明确提示。 */
export function exceedsStandbySongLimit(value) {
  return collectStandbySongIds(value).length > MAX_STANDBY_SONGS
}

/**
 * 批量保存失败时的提示文案。必须带上每个分区的具体失败原因，
 * 否则任何字段级校验错误都只会显示成"基础设置 保存失败"，管理员无法定位。
 */
export function describeSaveFailures(failures = []) {
  return failures
    .map(item => (item?.error?.message ? `${item.name}（${item.error.message}）` : item?.name))
    .join('、')
}

export function canonicalizeSettings(settings = {}) {
  const result = { ...settings }
  if (!String(result.display_address ?? '').trim()) {
    result.display_address = result.qr_address ?? ''
  }
  delete result.qr_address
  if (Object.prototype.hasOwnProperty.call(result, 'standby_song_ids')) {
    result.standby_song_ids = normalizeStandbySongIds(result.standby_song_ids)
  }
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
