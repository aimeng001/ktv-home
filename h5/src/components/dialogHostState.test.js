import { describe, expect, it } from 'vitest'
import { dialogKeyAction } from './dialogHostState'

describe('dialogKeyAction', () => {
  it('ignores keys while closed and unrelated keys while open', () => {
    expect(dialogKeyAction({ open: false, mode: 'confirm', key: 'Escape' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'ArrowDown' })).toBe('ignore')
    // 弹窗关闭时 Enter 一律不处理，即使焦点在按钮上。
    expect(dialogKeyAction({ open: false, mode: 'confirm', key: 'Enter', targetTag: 'BUTTON' })).toBe('ignore')
  })

  it('maps Escape to cancel and leaves Enter activation to the focused dialog control', () => {
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Escape' })).toBe('cancel')
    // targetTag 不影响 Escape 的取消语义。
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Escape', targetTag: 'SECTION' })).toBe('cancel')
    expect(dialogKeyAction({ open: true, mode: 'alert', key: 'Enter', targetTag: 'BUTTON' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'BUTTON' })).toBe('ignore')
    // 焦点落在 body 上时不代为触发确认，交给控件自身处理。
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'BODY' })).toBe('ignore')
    expect(dialogKeyAction({ open: true, mode: 'confirm', key: 'Enter', targetTag: 'TEXTAREA' })).toBe('ignore')
  })

  it('routes Tab to the dialog focus loop', () => {
    expect(dialogKeyAction({ open: true, key: 'Tab' })).toBe('trap-focus')
    // confirm 与 alert 两种模式都要把 Tab 交给焦点循环。
    expect(dialogKeyAction({ open: true, mode: 'alert', key: 'Tab' })).toBe('trap-focus')
  })
})
