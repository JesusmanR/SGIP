import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiInventario } from '../../servicios/api/inventario.js'
import ElegirProducto from './ElegirProducto.jsx'
import { LIMITE_INICIAL, NOTA_SIN_BORRAR } from './movimientos.js'
import SelectorLimite from './SelectorLimite.jsx'
import TablaMovimientos, { NotaLimite } from './TablaMovimientos.jsx'

const ACCIONES = (
  <>
    <Link to="/inventario/ingresos/nuevo" className="boton secundario enlace-boton">Registrar ingreso</Link>
    <Link to="/inventario/ajustes/nuevo" className="boton enlace-boton">Ajustar existencia</Link>
  </>
)

/** Pantallas 18, 42 y 78 · los últimos movimientos de inventario de la empresa. */
export default function ListaMovimientos() {
  const navegar = useNavigate()
  const [limite, setLimite] = useState(LIMITE_INICIAL)
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(
    () => apiInventario.movimientos(limite),
    [limite],
  )

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (cargando && !datos) cuerpo = <EstadoCarga mensaje="Cargando movimientos…" />
  else if (datos.length === 0) {
    cuerpo = (
      <EstadoVacio
        titulo="Todavía no hay movimientos"
        detalle="Cuando entre mercancía, se venda o se ajuste una existencia, quedará anotado aquí."
        accion={<Link to="/inventario/ajustes/nuevo" className="boton secundario enlace-boton">Registrar primer ajuste</Link>}
      />
    )
  } else {
    cuerpo = (
      <>
        <TablaMovimientos movimientos={datos} etiqueta="Movimientos" />
        <NotaLimite cantidad={datos.length} limite={limite} />
      </>
    )
  }

  return (
    <div className="contenido inventario">
      <EncabezadoPagina
        titulo="Movimientos de inventario"
        descripcion="Cada entrada, salida y ajuste de existencias, del más reciente al más antiguo."
        acciones={sinPermiso ? null : ACCIONES}
      />
      {!sinPermiso && (
        <div className="barra-inventario">
          <ElegirProducto
            etiqueta="Ver el historial de un producto"
            alElegir={(p) => navegar(`/inventario/productos/${p.productoId}`)}
          />
          <SelectorLimite limite={limite} alCambiar={setLimite} />
        </div>
      )}
      {cuerpo}
      {!sinPermiso && <p className="nota">{NOTA_SIN_BORRAR}</p>}
    </div>
  )
}
