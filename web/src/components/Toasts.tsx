import { Radio, RefreshCw, X } from 'lucide-react'
import { useNavigate } from 'react-router'
import { useToasts, type Toast } from '../live/LiveProvider'

/** A burst of pushes shows the newest few; the rest are in history. */
export const MAX_TOASTS = 3

export function Toasts() {
  const { toasts, dismiss } = useToasts()
  return (
    <div className="toast-stack" aria-live="polite">
      {toasts.slice(0, MAX_TOASTS).map((t) => (
        <ToastView key={t.id} toast={t} dismiss={() => dismiss(t.id)} />
      ))}
    </div>
  )
}

function ToastView({ toast, dismiss }: { toast: Toast; dismiss: () => void }) {
  const navigate = useNavigate()
  if (toast.kind === 'notice') {
    const { notice } = toast
    return (
      <div className="toast">
        <span className="toast-icon">
          <Radio size={20} />
        </span>
        <button
          className="toast-content"
          onClick={() => {
            dismiss()
            navigate(
              notice.game ? `/games/${encodeURIComponent(notice.game.id)}` : '/notifications',
            )
          }}
        >
          <small>SIDELINE · LIVE ALERT</small>
          <strong>{notice.headline.text}</strong>
        </button>
        <button aria-label="Dismiss alert" onClick={dismiss}>
          <X size={16} />
        </button>
      </div>
    )
  }
  const { text, action } = toast
  return (
    <div className={toast.kind === 'error' ? 'toast error-toast' : 'toast update-toast'}>
      {toast.kind === 'update' && (
        <span className="toast-icon">
          <RefreshCw size={18} />
        </span>
      )}
      <p>{text}</p>
      {action && (
        <button
          className="text-button"
          onClick={() => {
            dismiss()
            action.run()
          }}
        >
          {action.label}
        </button>
      )}
      <button aria-label={toast.kind === 'error' ? 'Dismiss error' : 'Dismiss'} onClick={dismiss}>
        <X size={16} />
      </button>
    </div>
  )
}
