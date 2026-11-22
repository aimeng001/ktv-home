import { describe, expect, it } from 'vitest'
import { getArtistAvatarStyle, hashString, MORANDI_PALETTES } from './avatarColor'

describe('avatarColor utility', () => {
  it('generates deterministic hash for same string', () => {
    expect(hashString('周杰伦')).toBe(hashString('周杰伦'))
    expect(hashString('金玄念')).toBe(hashString('金玄念'))
    expect(hashString('周杰伦')).not.toBe(hashString('金玄念'))
  })

  it('returns valid palette style for artists', () => {
    const style1 = getArtistAvatarStyle('周杰伦')
    expect(style1).toHaveProperty('backgroundColor')
    expect(style1).toHaveProperty('color')
    expect(MORANDI_PALETTES.some(p => p.backgroundColor === style1.backgroundColor && p.color === style1.color)).toBe(true)

    const styleSame = getArtistAvatarStyle('周杰伦')
    expect(styleSame).toEqual(style1)
  })

  it('handles null, empty or undefined names gracefully', () => {
    const defaultStyle = {
      backgroundColor: MORANDI_PALETTES[0].backgroundColor,
      color: MORANDI_PALETTES[0].color
    }
    expect(getArtistAvatarStyle(null)).toEqual(defaultStyle)
    expect(getArtistAvatarStyle('')).toEqual(defaultStyle)
    expect(getArtistAvatarStyle('   ')).toEqual(defaultStyle)
    expect(getArtistAvatarStyle(undefined)).toEqual(defaultStyle)
  })
})
