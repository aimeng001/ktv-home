import { afterEach, describe, expect, it } from 'vitest'
import { alertDialog, confirmDialog, useDialog } from './useDialog'

describe('useDialog', () => {
  afterEach(() => useDialog().close(false))

  it('provides warning defaults and resolves the confirmation choice', async () => {
    const result = confirmDialog('删除歌曲？')

    expect(useDialog().state).toMatchObject({
      open: true,
      mode: 'confirm',
      title: '请确认操作',
      message: '删除歌曲？',
      tone: 'warning'
    })
    useDialog().close(true)
    await expect(result).resolves.toBe(true)
  })

  it('replaces a pending dialog by resolving it as cancelled', async () => {
    const first = confirmDialog('先打开的确认框')
    const second = alertDialog('新的提示框', { tone: 'success' })

    await expect(first).resolves.toBe(false)
    expect(useDialog().state).toMatchObject({
      open: true,
      mode: 'alert',
      title: '操作提示',
      message: '新的提示框',
      tone: 'success'
    })
    useDialog().close(false)
    await expect(second).resolves.toBe(false)
  })

  it('ignores close requests while no dialog is open', () => {
    const dialog = useDialog()

    dialog.close(true)

    expect(dialog.state.open).toBe(false)
  })
})
