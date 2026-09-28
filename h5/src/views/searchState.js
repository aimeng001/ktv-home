export function searchViewState(keyword, loading, error, results = []) {
  if (loading) return 'loading'
  if (results.length) return 'results'
  if (error) return 'error'
  return keyword.trim() ? 'empty' : 'idle'
}
