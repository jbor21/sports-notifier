import { describe, expect, it } from 'vitest'
import { badge, markSeenLocally, setCount } from './badge'

describe('badge', () => {
  it('shows the last count received, in either order', () => {
    setCount(3)
    setCount(4)
    expect(badge.get()).toBe(4)
    setCount(4)
    setCount(3)
    expect(badge.get()).toBe(3)
  })

  it('goes to 0 when seen', () => {
    setCount(5)
    markSeenLocally()
    expect(badge.get()).toBe(0)
  })
})
