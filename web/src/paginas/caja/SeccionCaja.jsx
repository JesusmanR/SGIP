import { useId } from 'react'
import '../../estilos/caja.css'

/**
 * Una tarjeta numerada de la pantalla de la caja («01 Cliente», «02 Productos»): un
 * encabezado con su número, su título y una línea que explica qué se hace ahí. El número
 * es solo decoración (aria-hidden); la región se nombra por su título.
 */
export default function SeccionCaja({ numero, titulo, descripcion, children }) {
  const id = useId()
  return (
    <section className="caja-seccion" aria-labelledby={id}>
      <header className="caja-seccion-cabeza">
        <span className="caja-paso" aria-hidden="true">{numero}</span>
        <div>
          <h2 id={id} className="caja-seccion-titulo">{titulo}</h2>
          {descripcion && <p className="caja-seccion-descripcion">{descripcion}</p>}
        </div>
      </header>
      {children}
    </section>
  )
}
