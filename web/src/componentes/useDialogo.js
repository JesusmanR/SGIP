import { useEffect, useRef } from 'react'

const FOCALIZABLES = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), '
  + 'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Si el navegador podría enfocarlo: sin el atributo hidden (ni en un
 * ascendiente) y sin display:none ni visibility:hidden. Un elemento que no
 * pasa esto no recibe el foco con Tab, de modo que no puede ser el «último».
 */
function seEnfoca(el) {
  if (el.hidden || el.closest('[hidden]')) return false
  const estilo = getComputedStyle(el)
  if (estilo.visibility === 'hidden' || estilo.visibility === 'collapse') return false
  for (let nodo = el; nodo; nodo = nodo.parentElement) {
    if (getComputedStyle(nodo).display === 'none') return false
  }
  return true
}

/**
 * El teclado de un diálogo modal.
 *
 * Al abrirse el foco entra en el diálogo; al cerrarse vuelve a lo que lo abrió,
 * para que quien usa teclado no pierda su sitio en la tabla. Escape cierra, y
 * Tab da la vuelta dentro del diálogo en lugar de salir a la página que queda
 * detrás, que `aria-modal` declara inerte pero el navegador no bloquea.
 *
 * Devuelve el `ref` y el `onKeyDown` que van en el elemento con `role="dialog"`,
 * que además tiene que llevar `tabIndex={-1}` para poder recibir el foco.
 */
export default function useDialogo(onCerrar) {
  const ref = useRef(null)

  useEffect(() => {
    const previo = document.activeElement
    ref.current?.focus()
    return () => { if (previo?.isConnected) previo.focus?.() }
  }, [])

  function alTeclear(evento) {
    if (evento.key === 'Escape') {
      evento.stopPropagation()
      onCerrar()
      return
    }
    if (evento.key !== 'Tab' || !ref.current) return
    const dentro = [...ref.current.querySelectorAll(FOCALIZABLES)].filter(seEnfoca)
    if (dentro.length === 0) return
    const primero = dentro[0]
    const ultimo = dentro[dentro.length - 1]
    const activo = document.activeElement
    if (evento.shiftKey && (activo === primero || activo === ref.current)) {
      evento.preventDefault()
      ultimo.focus()
    } else if (!evento.shiftKey && activo === ultimo) {
      evento.preventDefault()
      primero.focus()
    }
  }

  return { ref, onKeyDown: alTeclear }
}
