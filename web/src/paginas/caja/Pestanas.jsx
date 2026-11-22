import { useId } from 'react'
import '../../estilos/caja.css'

/**
 * Pestañas con el teclado de siempre: las flechas cambian de pestaña y solo la activa entra con Tab.
 * `pestanas` es `[{ id, titulo }]`; el contenido de la activa va como hijo, dentro de su panel.
 */
export default function Pestanas({ etiqueta, pestanas, activa, alCambiar, children }) {
  const base = useId()

  function alTeclear(evento) {
    const i = pestanas.findIndex((p) => p.id === activa)
    let siguiente = null
    if (evento.key === 'ArrowRight') siguiente = pestanas[(i + 1) % pestanas.length]
    else if (evento.key === 'ArrowLeft') siguiente = pestanas[(i - 1 + pestanas.length) % pestanas.length]
    if (!siguiente) return
    evento.preventDefault()
    alCambiar(siguiente.id)
    document.getElementById(`${base}-${siguiente.id}`)?.focus()
  }

  return (
    <div className="caja-pestanas">
      <div role="tablist" aria-label={etiqueta} className="caja-pestanas-lista" onKeyDown={alTeclear}>
        {pestanas.map((p) => (
          <button
            key={p.id} id={`${base}-${p.id}`} type="button" role="tab" className="caja-pestana"
            aria-selected={p.id === activa} aria-controls={`${base}-panel`} tabIndex={p.id === activa ? 0 : -1}
            onClick={() => alCambiar(p.id)}
          >
            {p.titulo}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`${base}-panel`} aria-labelledby={`${base}-${activa}`}>{children}</div>
    </div>
  )
}
