import { describe, expect, it } from 'vitest'
import { dialogKeyAction } from './dialogHostState'

describe('dialogKeyAction', () => {
  it('ignores keys while closed and unrelated keys while open', () => {
    expect(dialogKeyAction({ open: false, mode: 'confirm', key: 'Escape' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Tab' })).toBe('ignore')
  })

  it('maps Escape to cancel and Enter to confirm except inside a textarea', () => {
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Escape' })).toBe('cancel')
    expect(dialogKeyAction({ open: true, mode: 'alert', key: 'Enter', targetTag: 'BUTTON' })).toBe('confirm')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'TEXTAREA' })).toBe('ignore')
  })
})
