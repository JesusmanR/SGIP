import { cloneElement, useId } from 'react'

/**
 * Un campo con su etiqueta visible y su mensaje al lado. El mensaje no va
 * dentro del `label`: si lo estuviera, un lector de pantalla lo leería como
 * parte del nombre del campo. La región existe siempre, vacía si no hay
 * nada que decir, para que se anuncie cuando aparece el texto.
 *
 * Lo usan también las pantallas de proveedores. Es un candidato a pasar a
 * `componentes/comun/` cuando la base lo admita.
 */
export default function Campo({ etiqueta, motivo, ayuda, children }) {
  const id = useId()
  const idMensaje = `${id}-mensaje`
  const mensaje = motivo || ayuda || ''
  return (
    <div className="campo">
      <label htmlFor={id}><span>{etiqueta}</span></label>
      {cloneElement(children, {
        id,
        'aria-invalid': motivo ? 'true' : undefined,
        'aria-describedby': mensaje ? idMensaje : undefined,
      })}
      <small id={idMensaje} role="status" className={`campo-mensaje ${motivo ? 'error' : 'advertencia'}`}>
        {mensaje}
      </small>
    </div>
  )
}
