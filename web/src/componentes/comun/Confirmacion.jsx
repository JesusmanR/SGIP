import { useEffect, useId, useRef } from 'react'
import '../../estilos/comun.css'

/**
 * Confirmación en la misma página: una pregunta que dice qué pasa, con
 * confirmar y cancelar. No hace falta un diálogo para una pregunta corta.
 *
 * - Al abrirse lleva el foco a su caja; Escape cancela (salvo mientras envía).
 * - Mientras `enviando`, los dos botones quedan deshabilitados.
 * - Al cerrarse devuelve el foco. Dónde: lo que diga `volverA(accion)`, si
 *   da un elemento; si no, el botón que tenía el foco al abrirse, si sigue
 *   en la página; y si ya no existe, `focoEstable` (un ref a un elemento con
 *   tabIndex=-1). `accion` es 'confirmar' o 'cancelar'.
 *
 * `volverA` se evalúa al cerrarse, ya con la página nueva en pantalla: sirve
 * cuando la confirmación ocupa el lugar de los botones y estos se vuelven a
 * crear, o cuando tras confirmar el botón de origen cambia de sentido.
 *
 * `children` va entre el texto y los botones (un campo de motivo, por ejemplo).
 */
export default function Confirmacion({
  pregunta, detalle, textoConfirmar, textoCancelar = 'Cancelar', enviando = false,
  alConfirmar, alCancelar, volverA, focoEstable, children,
}) {
  const id = useId()
  const caja = useRef(null)
  // Se lee al renderizar: es el último instante en que el botón que la abrió
  // sigue siendo el elemento activo, aunque la confirmación lo reemplace.
  const origen = useRef(undefined)
  if (origen.current === undefined) {
    const activo = typeof document === 'undefined' ? null : document.activeElement
    origen.current = activo && activo !== document.body ? activo : null
  }
  const accion = useRef('cancelar')
  const ultimo = useRef({ volverA, focoEstable })
  ultimo.current = { volverA, focoEstable }

  useEffect(() => {
    caja.current?.focus()
    return () => {
      const { volverA: elegir, focoEstable: estable } = ultimo.current
      const pedido = elegir?.(accion.current)
      const destino = [pedido, origen.current, estable?.current].find((el) => el?.isConnected)
      destino?.focus()
    }
  }, [])

  function alTeclear(e) {
    if (e.key !== 'Escape' || enviando) return
    accion.current = 'cancelar'
    alCancelar()
  }

  return (
    <div
      className="confirmacion" role="group" aria-labelledby={`${id}-pregunta`}
      tabIndex={-1} ref={caja} onKeyDown={alTeclear}
    >
      <p id={`${id}-pregunta`} className="confirmacion-pregunta">{pregunta}</p>
      {detalle && <p>{detalle}</p>}
      {children}
      <div className="acciones">
        <button
          type="button" className="boton" disabled={enviando}
          onClick={() => { accion.current = 'confirmar'; alConfirmar() }}
        >
          {textoConfirmar}
        </button>
        <button
          type="button" className="boton secundario" disabled={enviando}
          onClick={() => { accion.current = 'cancelar'; alCancelar() }}
        >
          {textoCancelar}
        </button>
      </div>
    </div>
  )
}
