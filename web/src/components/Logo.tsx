import { Link } from 'react-router'

export function Logo() {
  return (
    <Link className="brand" to="/">
      <img src="/sideline.svg" alt="" />
      sideline<span className="brand-dot">.</span>
    </Link>
  )
}
