export async function loadAllFavoritePages(loadPage, pageSize = 100) {
  const songs = []
  for (let page = 0; ; page += 1) {
    const current = await loadPage(page, pageSize)
    const rows = Array.isArray(current) ? current : []
    songs.push(...rows)
    if (rows.length < pageSize) return songs
  }
}
