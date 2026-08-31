export function searchViewState(keyword, loading, error, results = []) {
  if (loading) return 'loading'
  if (error) return 'error'
  if (results.length) return 'results'
  return keyword.trim() ? 'empty' : 'idle'
}
