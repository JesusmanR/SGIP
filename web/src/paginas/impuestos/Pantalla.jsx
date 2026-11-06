import { useLocation } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiImpuestos } from '../../servicios/api/impuestos.js'
import CatalogoImpuestos from './CatalogoImpuestos.jsx'
import Configuracion from './Configuracion.jsx'
import ReglasIva from './ReglasIva.jsx'

/**
 * Pantalla P-46 · impuestos de la empresa. Lo primero es la configuración;
 * lo demás (reglas y catálogo) cuelga de ella y carga cada cosa por su lado,
 * de modo que si una falla, el resto sigue a la vista.
 */
export default function Pantalla() {
  const aviso = useLocation().state?.aviso
  const { datos, error, sinPermiso, recargar } = useDatos(() => apiImpuestos.configuracion(), [])

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (!datos) cuerpo = <EstadoCarga mensaje="Cargando impuestos…" />
  else {
    cuerpo = (
      <div className="pila">
        <Configuracion guardada={datos} alGuardar={recargar} />
        <ReglasIva responsable={datos.responsableIva} avisoInicial={aviso} />
        <CatalogoImpuestos />
      </div>
    )
  }

  return (
    <div className="contenido seccion-impuestos">
      <EncabezadoPagina
        titulo="Impuestos"
        descripcion="Si tu negocio cobra IVA o no, y qué impuesto lleva cada producto."
      />
      {cuerpo}
    </div>
  )
}
