import { cloneElement, useId } from 'react'
import '../../estilos/comun.css'

/**
 * Un campo con su etiqueta visible y su mensaje junto a él. El mensaje no
 * va dentro de la etiqueta: un lector de pantalla lo leería como parte del
 * nombre del campo. El control hijo recibe `id`, `aria-invalid` y
 * `aria-describedby` apuntando al mensaje.
 *
 * - `motivo` es el error; tiene prioridad sobre `ayuda`.
 * - La región existe siempre, vacía si no hay nada que decir, y por omisión
 *   lleva role="status" siempre: así el lector anuncia el texto cuando aparece.
 * - `rol="alert"` hace que el error interrumpa; el role solo se pone
 *   mientras hay error, porque una alerta vacía y fija no se anuncia bien.
 */
export default function Campo({ etiqueta, motivo, ayuda, rol = 'status', children }) {
  const id = useId()
  const idMensaje = `${id}-mensaje`
  const mensaje = motivo || ayuda || ''
  const role = rol === 'alert' && !motivo ? undefined : rol
  return (
    <div className="campo">
      <label htmlFor={id}><span>{etiqueta}</span></label>
      {cloneElement(children, {
        id,
        'aria-invalid': motivo ? 'true' : undefined,
        'aria-describedby': mensaje ? idMensaje : undefined,
      })}
      <small id={idMensaje} role={role} className={`campo-mensaje ${motivo ? 'error' : 'ayuda'}`}>
        {mensaje}
      </small>
    </div>
  )
}
