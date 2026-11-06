import { cloneElement, useId } from 'react'

/**
 * Un campo con su mensaje al lado. El mensaje no va dentro de la etiqueta,
 * para que un lector de pantalla no lo lea como parte del nombre del campo.
 */
export default function Campo({ etiqueta, motivo, ayuda, children }) {
  const id = useId()
  const idMensaje = `${id}-mensaje`
  return (
    <div className="campo">
      <label htmlFor={id}><span>{etiqueta}</span></label>
      {cloneElement(children, {
        id,
        'aria-invalid': motivo ? 'true' : undefined,
        'aria-describedby': idMensaje,
      })}
      <small id={idMensaje} role={motivo ? 'status' : undefined} className={`campo-mensaje ${motivo ? 'error' : ''}`}>
        {motivo || ayuda || ''}
      </small>
    </div>
  )
}
