import { formatDay } from '../dates'

/** The days /games serves, as buttons: weekday (or Today) over the date. */
export function DateStrip({
  days,
  today,
  value,
  onChange,
}: {
  days: string[]
  today: string
  value: string
  onChange: (day: string) => void
}) {
  return (
    <div className="date-strip" role="group" aria-label="Pick a day">
      {days.map((day) => {
        const isToday = day === today
        const full = formatDay(day, { weekday: 'long', month: 'long', day: 'numeric' })
        return (
          <button
            key={day}
            data-date={day}
            aria-pressed={day === value}
            aria-label={isToday ? `Today, ${full}` : full}
            className={day === value ? 'active' : undefined}
            onClick={() => onChange(day)}
          >
            <span>{isToday ? 'Today' : formatDay(day, { weekday: 'short' })}</span>
            <strong>{formatDay(day, { month: 'short', day: 'numeric' })}</strong>
          </button>
        )
      })}
    </div>
  )
}
