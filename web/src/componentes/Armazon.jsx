import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../servicios/api.js'
import { seccionDe } from '../secciones.js'
import { borrarSesion, leerSesion } from '../servicios/sesion.js'
import AvisoConexion from './comun/AvisoConexion.jsx'
import ErrorBoundary from './ErrorBoundary.jsx'
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
  const contenido = useRef(null)
  const rutaAnterior = useRef(pathname)

  // Navegar cierra el menú: si no, taparía la pantalla a la que se fue.
  useEffect(() => { setAbierto(false) }, [pathname])

  // Al cambiar de página (no en la primera carga ni al cambiar solo la
  // búsqueda): título de la pestaña y foco en el h1 nuevo, o en el main si
  // no hay. Una página que carga bajo demanda trae su h1 un momento después.
  useEffect(() => {
    if (rutaAnterior.current === pathname) return undefined
    rutaAnterior.current = pathname
    const seccion = seccionDe(pathname)
    if (seccion) document.title = `${seccion.etiqueta} · SGIP`
    const main = contenido.current
    if (!main) return undefined
    function enfocarTitulo() {
      const h1 = main.querySelector('h1')
      if (!h1) return false
      h1.setAttribute('tabindex', '-1')
      h1.focus()
      return true
    }
    if (enfocarTitulo()) return undefined
    main.focus()
    const observador = new MutationObserver(() => {
      const activo = document.activeElement
      if (activo !== main && activo !== document.body) { observador.disconnect(); return }
      if (enfocarTitulo()) observador.disconnect()
    })
    observador.observe(main, { childList: true, subtree: true })
    const limite = setTimeout(() => observador.disconnect(), 3000)
    return () => { observador.disconnect(); clearTimeout(limite) }
  }, [pathname])

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
      <main ref={contenido} id="contenido-principal" className="armazon-contenido" tabIndex={-1}>
        <AvisoConexion />
        <ErrorBoundary reinicio={pathname}><Outlet /></ErrorBoundary>
      </main>
    </div>
  )
}
