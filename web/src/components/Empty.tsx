import { Radio } from 'lucide-react'
import type { ReactNode } from 'react'

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <Radio size={28} />
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  )
}
