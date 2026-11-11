import { describe, expect, it } from 'vitest'
import { dialogKeyAction } from './dialogHostState'

describe('global dialog keyboard behavior', () => {
  it('leaves Enter activation to the focused dialog control', () => {
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'BUTTON' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'BODY' })).toBe('ignore')
  })

  it('routes Tab to the focus loop in both dialog modes', () => {
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Tab' })).toBe('trap-focus')
    expect(dialogKeyAction({ open: true, mode: 'alert', key: 'Tab' })).toBe('trap-focus')
  })

  it('cancels any open dialog with Escape', () => {
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Escape', targetTag: 'SECTION' })).toBe('cancel')
  })

  it('ignores Enter when the dialog is closed or a textarea owns the key', () => {
    expect(dialogKeyAction({ open: false, mode: 'confirm', key: 'Enter', targetTag: 'BUTTON' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'TEXTAREA' })).toBe('ignore')
  })
})
