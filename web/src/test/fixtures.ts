// Fixtures come from the examples in the API contract, so a test's data cannot drift from it.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { parse } from 'yaml'
import type { components } from '../api/schema'

type Schemas = components['schemas']

// Tests run from web/ (jsdom's URL cannot build a file path from import.meta.url).
const spec = parse(readFileSync(resolve(process.cwd(), '../docs/api/openapi.yaml'), 'utf8'))

const example = (name: string) => spec.components.schemas[name].example
const errorExample = (name: string) =>
  spec.components.responses[name].content['application/json'].example

export const user = example('User') as Schemas['User']
export const gameSummary = example('GameSummary') as Schemas['GameSummary']
export const follow = example('Follow') as Schemas['Follow']
export const notification = example('Notification') as Schemas['Notification']
export const nflCatalog = spec.paths['/leagues/{leagueId}/alerts'].get.responses['200'].content[
  'application/json'
].example.items as Schemas['AlertType'][]

export const errorExamples = {
  unauthenticated: errorExample('Unauthenticated'),
  notFound: errorExample('NotFound'),
  validation: errorExample('ValidationError'),
  rateLimited: errorExample('RateLimited'),
} as Record<'unauthenticated' | 'notFound' | 'validation' | 'rateLimited', Schemas['Error']>
