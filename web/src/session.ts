import useSWR, { type SWRResponse } from 'swr'
import { fetcher, type ApiError } from './api/client'
import type { User } from './api/types'

const PUBLIC_PATHS = ['/signin', '/signup']

/** The screens a signed-out user may see. */
export function isPublicPath(pathname: string): boolean {
  return PUBLIC_PATHS.includes(pathname)
}

/** Where a lost session goes: sign in, then back to `next` (path and query). */
export function signInPath(next: string): string {
  const pathname = next.split(/[?#]/)[0]
  // From sign in or sign up, a second hop would only bury the original next.
  if (isPublicPath(pathname)) return '/signin'
  return '/signin?next=' + encodeURIComponent(next)
}

/**
 * The signed-in user. No retry on error: a 401 is an answer, and retrying it on /signin would
 * be a request loop.
 */
export function useMe(): SWRResponse<User, ApiError> {
  return useSWR<User, ApiError>('/me', fetcher, { shouldRetryOnError: false })
}
