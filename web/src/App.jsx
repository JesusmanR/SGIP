import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import Armazon from './componentes/Armazon.jsx'
import EstadoCarga from './componentes/comun/EstadoCarga.jsx'
import Acceso from './paginas/Acceso.jsx'
import CambiarClave from './paginas/CambiarClave.jsx'
import Catalogo from './paginas/Catalogo.jsx'
import Empresa from './paginas/Empresa.jsx'
import Importacion from './paginas/Importacion.jsx'
import Panel from './paginas/Panel.jsx'
import { puedeVer, seccionesDe } from './secciones.js'
import { borrarSesion, debeCambiarClave, haySesion, inicioSegunRol, leerSesion } from './servicios/sesion.js'

// Las secciones de la etapa 1 se cargan bajo demanda. Cada una es dueña de
// su carpeta y de sus subrutas: aquí solo se registra una vez.
const Usuarios = lazy(() => import('./paginas/usuarios/index.jsx'))
const Proveedores = lazy(() => import('./paginas/proveedores/index.jsx'))
const Compras = lazy(() => import('./paginas/compras/index.jsx'))
const Inventario = lazy(() => import('./paginas/inventario/index.jsx'))
const Precios = lazy(() => import('./paginas/precios/index.jsx'))
const Impuestos = lazy(() => import('./paginas/impuestos/index.jsx'))
const PlataformaEmpresas = lazy(() => import('./paginas/plataforma-empresas/index.jsx'))
const PlataformaTributario = lazy(() => import('./paginas/plataforma-tributario/index.jsx'))

/** Qué página atiende cada ruta de la tabla de secciones. */
const paginas = {
  '/panel': Panel,
  '/catalogo': Catalogo,
  '/importar': Importacion,
  '/proveedores': Proveedores,
  '/compras': Compras,
  '/inventario': Inventario,
  '/precios': Precios,
  '/impuestos': Impuestos,
  '/usuarios': Usuarios,
  '/empresa': Empresa,
  '/plataforma/empresas': PlataformaEmpresas,
  '/plataforma/tributario': PlataformaTributario,
}

/** A dónde mandar a quien aún no puede ver una pantalla con sesión, o null si puede. */
function desvioDeSesion() {
  if (!haySesion()) return '/acceso'
  // Quien aún tiene la contraseña inicial no ve ninguna otra pantalla:
  // el backend le responde 403 a todo lo demás, y esto le ahorra el muro.
  if (debeCambiarClave()) return '/cambiar-clave'
  return null
}

/**
 * Dónde empieza quien tiene sesión. Un rol que la tabla no conoce no tiene
 * dónde empezar: llevarlo a un inicio que tampoco puede ver sería un bucle
 * de redirecciones, así que se cierra su sesión y va al acceso.
 */
function inicioDeSesion() {
  if (!haySesion()) return '/acceso'
  const sesion = leerSesion()
  if (sesion.debeCambiarClave) return '/cambiar-clave'
  if (seccionesDe(sesion.rol).length === 0) {
    borrarSesion()
    return '/acceso'
  }
  return inicioSegunRol(sesion)
}

/**
 * Esta guarda es comodidad, no seguridad: evita mostrar una pantalla a
 * quien no debe verla. Quien decide de verdad es el backend, que responde
 * 401 o 403. Un front nunca protege datos; solo evita pedirlos en vano.
 *
 * Los permisos salen de la tabla de secciones, la misma del menú.
 */
function RutaDeSeccion({ ruta, children }) {
  const desvio = desvioDeSesion()
  if (desvio) return <Navigate to={desvio} replace />
  if (!puedeVer(ruta, leerSesion()?.rol)) return <Navigate to={inicioDeSesion()} replace />
  return children
}

/** El armazón solo se pinta con sesión válida: sin ella, ni menú ni parpadeo. */
function ArmazonConSesion() {
  const desvio = desvioDeSesion()
  return desvio ? <Navigate to={desvio} replace /> : <Armazon />
}

/** La pantalla de cambiar la contraseña: exige sesión, pero no la bloquea. */
function SoloConSesion({ children }) {
  return haySesion() ? children : <Navigate to="/acceso" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/acceso" element={<Acceso />} />
      <Route path="/cambiar-clave" element={<SoloConSesion><CambiarClave /></SoloConSesion>} />
      <Route element={<ArmazonConSesion />}>
        {Object.entries(paginas).map(([ruta, Pagina]) => (
          <Route
            key={ruta}
            path={`${ruta}/*`}
            element={(
              <RutaDeSeccion ruta={ruta}>
                <Suspense fallback={<EstadoCarga />}><Pagina /></Suspense>
              </RutaDeSeccion>
            )}
          />
        ))}
      </Route>
      <Route path="*" element={<Navigate to={inicioDeSesion()} replace />} />
    </Routes>
  )
}
