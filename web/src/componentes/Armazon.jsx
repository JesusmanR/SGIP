import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../servicios/api.js'
import { borrarSesion, leerSesion } from '../servicios/sesion.js'
import MenuLateral from './MenuLateral.jsx'
import '../estilos/armazon.css'

/**
 * Armazón de las pantallas con sesión: menú lateral en escritorio, barra
 * superior con menú desplegable en celular, y el único <main>.
 */
export default function Armazon() {
  const navegar = useNavigate()
  const { pathname } = useLocation()
  const sesion = leerSesion()
  const [abierto, setAbierto] = useState(false)
  const boton = useRef(null)

  // Navegar cierra el menú: si no, taparía la pantalla a la que se fue.
  useEffect(() => { setAbierto(false) }, [pathname])

  useEffect(() => {
    if (!abierto) return undefined
    function alTeclear(e) {
      if (e.key === 'Escape') {
        setAbierto(false)
        boton.current?.focus()
      }
    }
    document.addEventListener('keydown', alTeclear)
    return () => document.removeEventListener('keydown', alTeclear)
  }, [abierto])

  async function salir() {
    try {
      await api.salir()
    } catch {
      // Si la petición falla, la sesión local se cierra igual: dejar al
      // usuario dentro porque la red falló sería lo peor de los dos
      // mundos. El token queda vivo en el servidor hasta que expire.
    }
    borrarSesion()
    navegar('/acceso', { replace: true })
  }

  return (
    <div className="armazon">
      <a className="saltar" href="#contenido-principal">Saltar al contenido</a>
      <header className="armazon-barra">
        <span className="marca">SGIP</span>
        <button
          ref={boton}
          type="button"
          className="armazon-boton-menu"
          aria-expanded={abierto}
          aria-controls="menu-principal"
          onClick={() => setAbierto((a) => !a)}
        >
          Menú
        </button>
      </header>
      <MenuLateral id="menu-principal" rol={sesion?.rol} abierto={abierto} nombre={sesion?.nombre} alSalir={salir} />
      <main id="contenido-principal" className="armazon-contenido" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}
