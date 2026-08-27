export const MANAGED_LIBRARY_MODE = 'MANAGED'

/** Destructive song actions require an explicit confirmation of Managed mode. */
export function canDeleteSongs(libraryMode) {
  return libraryMode === MANAGED_LIBRARY_MODE
}
