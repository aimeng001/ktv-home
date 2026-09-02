import { describe, expect, it } from 'vitest'
import { buildScrapeRoute } from './ktvLibraryScrapeRoute'

describe('buildScrapeRoute', () => {
  it('returns route object with query params for batch scrape', () => {
    const route = buildScrapeRoute([12, 34])
    expect(route).toEqual({
      name: 'admin-metadata-scrape',
      query: { songIds: '12,34' }
    })
  })

  it('returns review=1 query param when review is requested for single song', () => {
    const route = buildScrapeRoute([56], true)
    expect(route).toEqual({
      name: 'admin-metadata-scrape',
      query: { songIds: '56', review: '1' }
    })
  })

  it('returns route without query when no song IDs provided', () => {
    const route = buildScrapeRoute([])
    expect(route).toEqual({
      name: 'admin-metadata-scrape',
      query: undefined
    })
  })
})
