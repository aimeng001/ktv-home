export function buildSongEditPayload(form = {}) {
  const payload = {
    title: String(form.title || '').trim(),
    artist: String(form.artist || '').trim(),
    language: form.language ? String(form.language).trim() : null,
    tags: String(form.tags || '').split(/[,，\s]+/).filter(Boolean)
  }
  const vocalForm = String(form.vocalForm || '').trim()
  if (vocalForm) payload.vocalForm = vocalForm
  return payload
}
