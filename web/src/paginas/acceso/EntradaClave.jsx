import { useEffect, useState } from 'react'
import '../../estilos/acceso.css'

/**
 * Entrada de contraseña con un botón para mostrarla u ocultarla.
 *
 * Va dentro de `Campo`, que le pasa `id`, `aria-invalid` y `aria-describedby`.
 * El botón no usa `aria-label`: su nombre es el texto visible más el del campo
 * dicho solo al lector («Mostrar contraseña nueva»), porque hay varios en
 * una misma pantalla y «Mostrar» a secas no distingue cuál.
 *
 * `descritoPor` suma ids propios (la lista de requisitos) a los que pone Campo.
 * `reinicio` es un contador: cada vez que cambia, la contraseña vuelve a
 * ocultarse (la pantalla lo sube al enviar, para que no quede a la vista).
 * `entradaRef` da acceso al input para llevarle el foco.
 */
export default function EntradaClave({
  nombre, descritoPor, reinicio, entradaRef, 'aria-describedby': deCampo, ...entrada
}) {
  const [visible, setVisible] = useState(false)
  useEffect(() => { setVisible(false) }, [reinicio])
  const descripcion = [deCampo, descritoPor].filter(Boolean).join(' ') || undefined
  return (
    <div className="entrada-clave">
      <input
        {...entrada}
        ref={entradaRef}
        spellCheck={false}
        autoCapitalize="none"
        autoCorrect="off"
        type={visible ? 'text' : 'password'}
        aria-describedby={descripcion}
      />
      <button
        type="button"
        className="entrada-clave-boton"
        aria-controls={entrada.id}
        onClick={() => setVisible((v) => !v)}
      >
        {visible ? 'Ocultar' : 'Mostrar'}{' '}
        <span className="solo-lector">{nombre}</span>
      </button>
    </div>
  )
}
