import { useEffect } from 'react'
import useDialogo from '../../componentes/useDialogo.js'
import '../../estilos/caja.css'

/**
 * El diálogo de la caja: rol, nombre, Escape para cerrar y Tab que no se sale.
 * El foco entra en el primer campo (o en el propio diálogo si no hay) y, al
 * cerrarse, lo recoge la pantalla de la caja para llevarlo al buscador.
 */
export default function Dialogo({ titulo, alCerrar, children }) {
  const dialogo = useDialogo(alCerrar)

  useEffect(() => {
    dialogo.ref.current?.querySelector('input:not([type="hidden"]), select, textarea')?.focus()
  }, [dialogo.ref])

  return (
    <div
      className="caja-velo" role="dialog" aria-modal="true" aria-label={titulo}
      tabIndex={-1} ref={dialogo.ref} onKeyDown={dialogo.onKeyDown}
    >
      <div className="tarjeta caja-dialogo">
        <h2 className="caja-dialogo-titulo">{titulo}</h2>
        {children}
      </div>
    </div>
  )
}
