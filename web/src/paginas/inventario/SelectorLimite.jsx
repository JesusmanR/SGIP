import { useId } from 'react'
import { LIMITES } from './movimientos.js'

export default function SelectorLimite({ limite, alCambiar }) {
  const id = useId()
  return (
    <div className="campo selector-limite">
      <label htmlFor={id}><span>Mostrar los últimos</span></label>
      <select id={id} value={limite} onChange={(e) => alCambiar(Number(e.target.value))}>
        {LIMITES.map((n) => <option key={n} value={n}>{n} movimientos</option>)}
      </select>
    </div>
  )
}
