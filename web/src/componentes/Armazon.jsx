import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../servicios/api.js'
import { seccionDe } from '../secciones.js'
import { borrarSesion, leerSesion } from '../servicios/sesion.js'
import AvisoConexion from './comun/AvisoConexion.jsx'
import ContadorPrueba from './ContadorPrueba.jsx'
import ErrorBoundary from './ErrorBoundary.jsx'
import MenuLateral from './MenuLateral.jsx'
import '../estilos/armazon.css'

/**
 * Armazón de las pantallas con sesión: menú lateral blanco (cajón en el
 * celular), barra superior con migas y el único <main>.
 */
export default function Armazon() {
  const navegar = useNavigate()
  const { pathname } = useLocation()
  const sesion = leerSesion()
  const [abierto, setAbierto] = useState(false)
  const boton = useRef(null)
  const barra = useRef(null)
  // Adónde devolver el foco al cerrar el cajón: al botón del menú, a la página o a ninguna parte. Se hace en
  // un efecto, cuando `inert` ya salió del DOM: antes, focus() no haría nada.
  const devolverFoco = useRef(false)
  const eraAbierto = useRef(false)
  const contenido = useRef(null)
  const rutaAnterior = useRef(pathname)
  // Dónde estaba el foco al pintar la navegación, antes de que la página nueva lo toque.
  const focoAntes = useRef(null)
  focoAntes.current = document.activeElement

  // Navegar cierra el menú: si no, taparía la pantalla a la que se fue.
  useEffect(() => { setAbierto(false) }, [pathname])

  // Al cambiar de página (no en la primera carga ni al cambiar solo la
  // búsqueda): título de la pestaña y foco en el h1 nuevo, o en el main si
  // no hay. Una página que carga bajo demanda trae su h1 un momento después.
  // Espera a que el cajón esté cerrado: mientras lo está, lo de atrás es inerte.
  useEffect(() => {
    if (abierto || rutaAnterior.current === pathname) return undefined
    rutaAnterior.current = pathname
    const seccion = seccionDe(pathname)
    if (seccion) document.title = `${seccion.etiqueta} · SGIP`
    const main = contenido.current
    if (!main) return undefined
    // Una página que movió su propio foco al montarse (un buscador, por
    // ejemplo) lo conserva: se nota porque el foco ya no está donde estaba
    // antes de navegar.
    const activo = document.activeElement
    if (activo !== main && main.contains(activo) && activo !== focoAntes.current) return undefined
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
  }, [pathname, abierto])

  useEffect(() => {
    if (!abierto) return undefined
    function alTeclear(e) {
      if (e.key === 'Escape') cerrarMenu()
    }
    document.addEventListener('keydown', alTeclear)
    return () => document.removeEventListener('keydown', alTeclear)
  }, [abierto])

  // Abierto el cajón, el foco entra en él (en «Cerrar navegación», lo primero
  // que se necesita para salir). Al cerrarse por Escape, el botón de cerrar, el
  // velo o el paso a escritorio, el foco vuelve al botón del menú; al cerrarse
  // por navegar no: ahí el foco lo toma el título de la página nueva.
  useEffect(() => {
    if (abierto) {
      document.getElementById('menu-principal')?.querySelector('.menu-cerrar')?.focus()
    } else if (eraAbierto.current && devolverFoco.current === 'boton') {
      boton.current?.focus()
    } else if (eraAbierto.current && devolverFoco.current === 'pagina') {
      // En escritorio el botón del menú no se ve: el foco no puede quedarse allí.
      const h1 = contenido.current?.querySelector('h1')
      if (h1) { h1.setAttribute('tabindex', '-1'); h1.focus() } else contenido.current?.focus()
    }
    if (!abierto) devolverFoco.current = false
    eraAbierto.current = abierto
  }, [abierto])

  // Si el diseño deja de ser el del celular con el cajón abierto, deja de ser
  // cajón: se cierra. Misma consulta que el CSS (max-width: 48rem). El foco solo
  // se mueve si estaba dentro del cajón.
  useEffect(() => {
    if (!abierto || !window.matchMedia) return undefined
    const celular = window.matchMedia('(max-width: 48rem)')
    const alCambiar = (e) => {
      if (e.matches) return
      const menu = document.getElementById('menu-principal')
      cerrarMenu(menu?.contains(document.activeElement) ? 'pagina' : false)
    }
    celular.addEventListener('change', alCambiar)
    return () => celular.removeEventListener('change', alCambiar)
  }, [abierto])

  // La barra puede crecer una fila en el celular (contador de la prueba): el
  // margen de desplazamiento para el foco sigue su altura real.
  useEffect(() => {
    const el = barra.current
    if (!el || typeof ResizeObserver === 'undefined') return undefined
    const raiz = document.documentElement
    const medir = () => raiz.style.setProperty('--alto-barra-real', `${el.offsetHeight}px`)
    const observador = new ResizeObserver(medir)
    observador.observe(el)
    return () => { observador.disconnect(); raiz.style.removeProperty('--alto-barra-real') }
  }, [])

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

  const seccionActual = seccionDe(pathname)
  /** `devolver`: 'boton' (por defecto), 'pagina' o false (no mover el foco). */
  function cerrarMenu(devolver = 'boton') {
    devolverFoco.current = devolver
    setAbierto(false)
  }

  return (
    <div className="armazon">
      <a className="saltar" href="#contenido-principal" inert={abierto ? '' : undefined}>Saltar al contenido</a>
      <MenuLateral
        id="menu-principal" rol={sesion?.rol} abierto={abierto} nombre={sesion?.nombre}
        alSalir={salir} alCerrar={() => cerrarMenu()}
      />
      {abierto && <div className="armazon-velo" aria-hidden="true" onClick={() => cerrarMenu()} />}
      {/* Con el cajón abierto lo de atrás no se alcanza con teclado ni lector. */}
      <div className="armazon-columna" inert={abierto ? '' : undefined}>
        <header ref={barra} className="armazon-barra">
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
          <nav className="migas" aria-label="Ruta de navegación">
            <ol>
              <li className="miga-raiz">Espacio de trabajo</li>
              {seccionActual && <li><span aria-current="page">{seccionActual.etiqueta}</span></li>}
            </ol>
          </nav>
          <ContadorPrueba rol={sesion?.rol} />
        </header>
        <main ref={contenido} id="contenido-principal" className="armazon-contenido" tabIndex={-1}>
          <AvisoConexion />
          <ErrorBoundary reinicio={pathname}><Outlet /></ErrorBoundary>
        </main>
      </div>
    </div>
  )
}
