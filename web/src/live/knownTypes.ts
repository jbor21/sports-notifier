import type { AlertPart, AlertType, Notice } from '../api/types'

/** Types that answer to a catalog switch without being one (openapi.yaml, AlertPart). */
export const ANSWERING_TYPES: readonly string[] = [
  'SCORE',
  'RESUMED',
  'GOAL_OVERTURNED',
  'RUN_OVERTURNED',
  'TOUCHDOWN_OVERTURNED',
  'FIELD_GOAL_OVERTURNED',
  'SCORE_OVERTURNED',
]

/** Known = every league's catalog plus the answering types, so a new catalog type needs no UI change. */
export function knownTypes(catalogs: AlertType[][]): Set<string> {
  const known = new Set<string>(ANSWERING_TYPES)
  for (const catalog of catalogs) for (const a of catalog) known.add(a.type)
  return known
}

/** Unknown headline types are skipped. Null means the catalogs haven't loaded yet: show everything. */
export function isKnown(n: Notice, known: Set<string> | null): boolean {
  return known === null || known.has(n.headline.type)
}

export function knownExtras(n: Notice, known: Set<string> | null): AlertPart[] {
  return (n.extras ?? []).filter((e) => known === null || known.has(e.type))
}
