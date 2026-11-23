import { useRef } from 'react'
import { NavLink } from 'react-router-dom'
import { etiquetaDeRol, seccionesDe } from '../secciones.js'

/** Agrupa conservando el orden de la tabla: el grupo aparece donde aparece su primera sección. */
function agrupar(lista) {
  const grupos = []
  for (const s of lista) {
    let g = grupos.find((x) => x.nombre === s.grupo)
    if (!g) { g = { nombre: s.grupo, items: [] }; grupos.push(g) }
    g.items.push(s)
  }
  return grupos
}

/** «Jesús Manuel» → «JM»; un solo nombre, una inicial. */
export function iniciales(nombre) {
  return (nombre ?? '').trim().split(/\s+/).filter(Boolean).slice(0, 2)
    .map((p) => p[0].toUpperCase()).join('')
}

/**
 * Menú lateral: marca, enlaces del rol agrupados y perfil con «Salir». Los
 * mismos elementos sirven a escritorio y al cajón del celular: cambia solo
 * el CSS, así que no hay dos copias que mantener ni leer.
 */
export default function MenuLateral({ id, rol, abierto, nombre, alSalir, alCerrar }) {
  const raiz = useRef(null)

  // Abierto como cajón, Tab da la vuelta dentro de él: lo de atrás está inerte,
  // pero así el foco no se escapa a la barra del navegador a mitad del menú.
  function alTeclear(e) {
    if (!abierto || e.key !== 'Tab') return
    const dentro = [...raiz.current.querySelectorAll('a[href], button:not([disabled])')]
    const primero = dentro[0]
    const ultimo = dentro[dentro.length - 1]
    if (e.shiftKey && document.activeElement === primero) { e.preventDefault(); ultimo.focus() }
    else if (!e.shiftKey && document.activeElement === ultimo) { e.preventDefault(); primero.focus() }
  }

  return (
    <aside ref={raiz} id={id} className={`menu-lateral${abierto ? ' abierto' : ''}`} onKeyDown={alTeclear}>
      <div className="menu-cabeza">
        <span className="marca"><span className="marca-sello" aria-hidden="true"><i /><i /><i /></span>SGIP</span>
        <button type="button" className="menu-cerrar" aria-label="Cerrar navegación" onClick={alCerrar}>
          <span aria-hidden="true">×</span>
        </button>
      </div>
      <nav aria-label="Principal">
        {agrupar(seccionesDe(rol)).map((g) => (
          <div className="menu-grupo" role="group" aria-labelledby={`menu-grupo-${g.nombre}`} key={g.nombre}>
            <p className="menu-grupo-titulo" id={`menu-grupo-${g.nombre}`}>{g.nombre}</p>
            <ul>
              {g.items.map((s) => (
                <li key={s.ruta}>
                  <NavLink to={s.ruta}>{s.etiqueta}</NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
      <section className="menu-perfil" aria-label="Tu perfil">
        <span className="menu-avatar">{iniciales(nombre)}</span>
        <span className="menu-perfil-texto">
          <strong className="menu-nombre">{nombre}</strong>
          <small>{etiquetaDeRol(rol)}</small>
        </span>
        <button type="button" className="boton secundario menu-salir" onClick={alSalir}>Salir</button>
      </section>
    </aside>
  )
}
