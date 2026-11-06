import { Route, Routes } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiPlataformaTributario } from '../../servicios/api/plataforma-tributario.js'
import '../../estilos/plataforma-tributario.css'
import AlertaUvt from './AlertaUvt.jsx'
import SeccionImpuestos from './SeccionImpuestos.jsx'
import SeccionUvt from './SeccionUvt.jsx'

function Pagina() {
  const uvt = useDatos(() => apiPlataformaTributario.listarUvt(), [])
  const alerta = useDatos(() => apiPlataformaTributario.alertaUvt(), [])
  const impuestos = useDatos(() => apiPlataformaTributario.listarImpuestos(), [])

  // Al fijar un año cambia la lista y puede apagarse la alerta: se piden las dos.
  const alGuardarUvt = () => { uvt.recargar(); alerta.recargar() }

  return (
    <div className="contenido plataforma-tributario">
      <EncabezadoPagina
        titulo="Datos tributarios"
        descripcion="Los valores nacionales que usan todas las tiendas: la UVT de cada año y los impuestos."
      />
      <AlertaUvt alerta={alerta.datos} />
      <SeccionUvt uvt={uvt} alGuardar={alGuardarUvt} />
      <SeccionImpuestos impuestos={impuestos} />
    </div>
  )
}

/**
 * Sección Datos tributarios de la plataforma, solo del super administrador.
 * App.jsx la registra una sola vez como «/plataforma/tributario/*».
 */
export default function Seccion() {
  return (
    <Routes>
      <Route path="*" element={<Pagina />} />
    </Routes>
  )
}
