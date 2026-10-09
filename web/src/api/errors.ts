/** Every failed call, in one shape: the API's Error when the body has it, a plain message otherwise. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fields: Record<string, string> = {},
    readonly retryAfter = 0,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const SERVER_ERROR = 'Something went wrong on our side. Try again.'
const NETWORK_ERROR = "Can't reach Sideline. Check your connection."

export async function toApiError(res: Response): Promise<ApiError> {
  const retryAfter = Number(res.headers.get('Retry-After')) || 0
  let body: unknown
  try {
    body = await res.json()
  } catch {
    body = undefined
  }
  const error = (body as { error?: { code?: unknown; message?: unknown; fields?: unknown } })?.error
  if (error && typeof error.code === 'string' && typeof error.message === 'string') {
    const fields = (error.fields ?? {}) as Record<string, string>
    return new ApiError(res.status, error.code, error.message, fields, retryAfter)
  }
  // Not the Error shape: a bare 500 from a downstream failure (#25) or a proxy's own error page.
  return new ApiError(res.status, 'server_error', SERVER_ERROR, {}, retryAfter)
}

export function networkError(): ApiError {
  return new ApiError(0, 'network', NETWORK_ERROR)
}
