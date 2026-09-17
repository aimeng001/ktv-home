export function buildSongEditPayload(form = {}) {
  return {
    title: String(form.title || '').trim(),
    artist: String(form.artist || '').trim(),
    language: form.language ? String(form.language).trim() : null,
    tags: String(form.tags || '').split(/[,，\s]+/).filter(Boolean)
  }
}
