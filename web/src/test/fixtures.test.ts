import { errorExamples, gameSummary, nflCatalog } from './fixtures'

describe('fixtures from openapi.yaml', () => {
  it('reads the game summary example', () => {
    expect(gameSummary.id).toBe('nfl-401872948')
    expect(gameSummary.version).toBe(57)
  })

  it('reads the NFL catalog example', () => {
    expect(nflCatalog.map((a) => a.type)).toEqual(['TOUCHDOWN', 'CLOSE_LATE', 'RED_ZONE'])
  })

  it('reads the error examples', () => {
    expect(errorExamples.unauthenticated.error.code).toBe('unauthenticated')
  })
})
