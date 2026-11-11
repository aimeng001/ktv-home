import { describe, expect, it } from 'vitest'
import { dialogKeyAction } from './dialogHostState'

describe('dialogKeyAction', () => {
  it('ignores keys while closed and unrelated keys while open', () => {
    expect(dialogKeyAction({ open: false, mode: 'confirm', key: 'Escape' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'ArrowDown' })).toBe('ignore')
  })

  it('maps Escape to cancel and leaves Enter activation to the focused dialog control', () => {
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Escape' })).toBe('cancel')
    expect(dialogKeyAction({ open: true, mode: 'alert', key: 'Enter', targetTag: 'BUTTON' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'BUTTON' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'TEXTAREA' })).toBe('ignore')
  })

  it('routes Tab to the dialog focus loop', () => {
    expect(dialogKeyAction({ open: true, key: 'Tab' })).toBe('trap-focus')
  })
})
