import { Route, Routes } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import '../../estilos/inventario.css'

/**
 * Punto de entrada de la sección. App.jsx la registra una sola vez como
 * «<ruta>/*»; las subrutas se declaran aquí, con este mismo <Routes>.
 */
export default function Seccion() {
  return (
    <Routes>
      <Route
        path="*"
        element={(
          <div className="contenido">
            <EncabezadoPagina titulo="Inventario" />
            <EstadoVacio
              titulo="En construcción"
              detalle="Esta sección todavía no está disponible."
            />
          </div>
        )}
      />
    </Routes>
  )
}
