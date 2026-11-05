import { NavLink } from 'react-router-dom'
import { seccionesDe } from '../secciones.js'

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

/**
 * Enlaces del menú según el rol, agrupados. Los mismos enlaces sirven al
 * menú lateral de escritorio y al desplegable del celular: cambia solo
 * el CSS, así que no hay dos copias que mantener ni leer.
 */
export default function MenuLateral({ id, rol, abierto, nombre, alSalir }) {
  return (
    <nav id={id} className={`menu-lateral${abierto ? ' abierto' : ''}`} aria-label="Principal">
      {agrupar(seccionesDe(rol)).map((g) => (
        <div className="menu-grupo" key={g.nombre}>
          <p className="menu-grupo-titulo" id={`menu-grupo-${g.nombre}`}>{g.nombre}</p>
          <ul aria-labelledby={`menu-grupo-${g.nombre}`}>
            {g.items.map((s) => (
              <li key={s.ruta}>
                <NavLink to={s.ruta}>{s.etiqueta}</NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
      <div className="menu-sesion">
        <span className="menu-nombre">{nombre}</span>
        <button type="button" className="boton secundario" onClick={alSalir}>Salir</button>
      </div>
    </nav>
  )
}
