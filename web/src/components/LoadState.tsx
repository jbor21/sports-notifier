/** The same three states on every screen: an error with Try again, skeletons, or nothing. */
export function LoadState({
  error,
  loading,
  retry,
}: {
  error?: Error
  loading?: boolean
  retry?: () => void
}) {
  if (error)
    return (
      <div className="error-box" role="alert">
        {error.message}
        {retry && <button onClick={retry}>Try again</button>}
      </div>
    )
  if (loading)
    return (
      <div className="skeletons" aria-label="Loading" aria-busy="true">
        <div />
        <div />
        <div />
      </div>
    )
  return null
}
