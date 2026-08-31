import { describe, expect, it } from 'vitest'
import { dialogKeyAction } from './dialogHostState'

describe('global dialog keyboard behavior', () => {
  it('confirms a confirm dialog with Enter', () => {
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'BUTTON' })).toBe('confirm')
  })

  it('dismisses an alert dialog with Enter', () => {
    expect(dialogKeyAction({ open: true, mode: 'alert', key: 'Enter', targetTag: 'BUTTON' })).toBe('confirm')
  })

  it('cancels any open dialog with Escape', () => {
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Escape', targetTag: 'SECTION' })).toBe('cancel')
  })

  it('does not confirm when the dialog is closed or a textarea owns Enter', () => {
    expect(dialogKeyAction({ open: false, mode: 'confirm', key: 'Enter', targetTag: 'BUTTON' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'TEXTAREA' })).toBe('ignore')
  })
})
