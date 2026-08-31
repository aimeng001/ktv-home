export function dialogKeyAction({ open, mode, key, targetTag = '' }) {
  if (!open) return 'ignore'
  if (key === 'Escape') return 'cancel'
  if (key === 'Enter' && targetTag !== 'TEXTAREA') return 'confirm'
  return 'ignore'
}
