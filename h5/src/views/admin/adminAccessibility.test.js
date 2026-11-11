import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const fromHere = relativePath => readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
const adminLayout = fromHere('./AdminLayout.vue')
const mutedToken = adminLayout.match(/--admin-muted\s*:\s*(#[\da-f]{3,8})/i)?.[1]

function luminance(hex) {
  const channels = hex.replace('#', '').match(/.{2}/g).map(channel => parseInt(channel, 16) / 255)
  const linear = channels.map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
  return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
}

function contrastRatio(foreground, background) {
  const [lighter, darker] = [luminance(foreground), luminance(background)].sort((a, b) => b - a)
  return (lighter + 0.05) / (darker + 0.05)
}

function colorDeclarations(source, selector) {
  const declarations = []
  for (const [, selectorList, body] of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (!selectorList.split(',').some(candidate => candidate.trim() === selector)) continue
    declarations.push(...Array.from(body.matchAll(/(?:^|;)\s*color\s*:\s*([^;]+)/g), match => match[1].trim()))
  }
  return declarations
}

describe('admin light-surface text contrast contract', () => {
  it('keeps the shared muted text token readable on white and near-white surfaces', () => {
    expect(mutedToken).toBeTruthy()
    expect(contrastRatio(mutedToken, '#ffffff')).toBeGreaterThanOrEqual(4.5)
    expect(contrastRatio(mutedToken, '#f8fafc')).toBeGreaterThanOrEqual(4.5)
  })

  it('uses the shared muted token for visible secondary text on light admin surfaces', () => {
    const sources = [
      { source: adminLayout, selectors: ['.brand span', '.nav-label'] },
      { source: fromHere('../../components/admin/AiTaskPanel.vue'), selectors: ['.meta', '.empty'] },
      {
        source: fromHere('./SettingsView.vue'),
        selectors: [
          '.category-copy small', '.search-results small', '.empty', '.ai-status span',
          '.group-head span', '.setting-row :deep(.setting-label small)', '.model-list-label',
          '.provider-health small', '.wish-content small'
        ]
      }
    ]

    for (const { source, selectors } of sources) {
      for (const selector of selectors) {
        expect(colorDeclarations(source, selector).at(-1), `${selector} must use the shared readable text token`).toBe('var(--admin-muted)')
      }
    }
  })

  it('keeps the active settings category description readable on its selected surface', () => {
    const selectedColor = colorDeclarations(fromHere('./SettingsView.vue'), '.category-button.active .category-copy small').at(-1)
    expect(selectedColor).toBeTruthy()
    expect(contrastRatio(selectedColor, '#eff6ff')).toBeGreaterThanOrEqual(4.5)
  })
})
