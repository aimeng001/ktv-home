import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const fromHere = relativePath => readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')

const browser = fromHere('./KtvThreeColumnBrowser.vue')
const softKeyboard = fromHere('./KtvSoftKeyboard.vue')
const headerBar = fromHere('./KtvHeaderBar.vue')
const mediaSurface = fromHere('./KtvMediaSurface.vue')
const modals = fromHere('./KtvModals.vue')
const bottomDock = fromHere('./KtvBottomDock.vue')
const ktvLibrary = fromHere('../../views/admin/KtvLibraryView.vue')
const scrapeView = fromHere('../../views/admin/MetadataScrapeView.vue')

describe('KTV 控件可访问性契约', () => {
  it('不给按钮组挂 role="tablist"（没有 role=tab/aria-selected 就是非法结构）', () => {
    expect(browser).not.toContain('role="tablist"')
    expect(softKeyboard).not.toContain('role="tablist"')
  })

  it('每个滑块都有可读名称', () => {
    const sliders = [mediaSurface, modals, scrapeView]
      .flatMap(source => Array.from(source.match(/<input[^>]*type="range"[^>]*>/g) || []))

    expect(sliders.length).toBe(3)
    for (const tag of sliders) {
      expect(tag).toContain('aria-label')
    }
  })

  it('底部小窗触发全屏是键盘可达的按钮', () => {
    const pip = bottomDock.match(/<div[\s\S]*?class="video-container"[\s\S]*?>/)?.[0] || ''
    expect(pip).toContain('role="button"')
    expect(pip).toContain('tabindex="0"')
    expect(pip).toMatch(/@keydown\.enter(\.prevent)?=/)
    expect(pip).toMatch(/@keydown\.space(\.prevent)?=/)
  })

  it('曲库列表的复选框都有可读名称', () => {
    const checkboxes = Array.from(ktvLibrary.match(/<input[^>]*type="checkbox"[^>]*>/g) || [])
    expect(checkboxes.length).toBeGreaterThanOrEqual(2)
    for (const tag of checkboxes) {
      expect(tag).toContain('aria-label')
    }
    expect(ktvLibrary).toContain('全选当前页')
  })

  it('KTV 控件不再使用 9px 字号', () => {
    for (const [name, source] of Object.entries({ bottomDock, mediaSurface, softKeyboard })) {
      expect(source, `${name} 仍有 9px 字号`).not.toMatch(/font-size:\s*9px/)
    }
  })
})
