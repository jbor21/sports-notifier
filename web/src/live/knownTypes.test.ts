import { describe, expect, it } from 'vitest'
import { isKnown, knownExtras, knownTypes } from './knownTypes'
import type { Notice } from '../api/types'
import { nflCatalog, notification } from '../test/fixtures'

const withHeadline = (type: string): Notice => ({
  ...structuredClone(notification),
  headline: { type, text: 'x' },
})

describe('known alert types', () => {
  it('are the catalogs plus the answering types', () => {
    const known = knownTypes([nflCatalog])
    expect(known.has('TOUCHDOWN')).toBe(true)
    expect(known.has('RED_ZONE')).toBe(true)
    expect(known.has('TOUCHDOWN_OVERTURNED')).toBe(true)
    expect(known.has('SCORE')).toBe(true)
  })

  it('skip a notification whose headline is unknown', () => {
    const known = knownTypes([nflCatalog])
    expect(isKnown(withHeadline('MYSTERY'), known)).toBe(false)
    expect(isKnown(withHeadline('TOUCHDOWN'), known)).toBe(true)
  })

  it('show everything until the catalogs have loaded', () => {
    expect(isKnown(withHeadline('MYSTERY'), null)).toBe(true)
  })

  it('drop unknown extras', () => {
    const known = knownTypes([[...nflCatalog, { ...nflCatalog[0], type: 'LEAD_CHANGE' }]])
    const n: Notice = {
      ...structuredClone(notification),
      extras: [
        { type: 'LEAD_CHANGE', text: 'lead' },
        { type: 'MYSTERY', text: '?' },
      ],
    }
    expect(knownExtras(n, known).map((e) => e.type)).toEqual(['LEAD_CHANGE'])
  })
})
