import { useState } from 'react'
import { Link } from 'react-router-dom'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiInventario } from '../../servicios/api/inventario.js'
import CuadreProducto from './CuadreProducto.jsx'
import { LIMITE_INICIAL, NOTA_SIN_BORRAR, unidades } from './movimientos.js'
import ProductoNoEncontrado from './ProductoNoEncontrado.jsx'
import SelectorLimite from './SelectorLimite.jsx'
import TablaMovimientos, { NotaLimite } from './TablaMovimientos.jsx'

const VOLVER = <p className="volver"><Link to="/inventario">Volver a movimientos</Link></p>

/** Pantalla 42 · el kárdex de una referencia, con su cuadre. */
export default function HistorialProducto({ productoId }) {
  const [limite, setLimite] = useState(LIMITE_INICIAL)
  // La ficha (nombre, existencia y cuadre) y el kárdex se piden aparte: subir
  // el límite del kárdex no tiene por qué repetir lo otro.
  const ficha = useDatos(
    () => Promise.all([apiInventario.producto(productoId), apiInventario.cuadre(productoId)]),
    [productoId],
  )
  const kardex = useDatos(() => apiInventario.kardex(productoId, limite), [productoId, limite])

  if (!ficha.datos) {
    let cuerpo
    if (ficha.sinPermiso) cuerpo = <EstadoSinPermiso />
    else if (ficha.error?.estado === 404) cuerpo = <ProductoNoEncontrado />
    else if (ficha.error) cuerpo = <EstadoError error={ficha.error} alReintentar={ficha.recargar} />
    else cuerpo = <EstadoCarga mensaje="Cargando producto…" />
    return (
      <div className="contenido inventario">
        {VOLVER}
        <EncabezadoPagina titulo="Historial del producto" />
        {cuerpo}
      </div>
    )
  }

  const [producto, cuadre] = ficha.datos
  const aQuery = `?producto=${productoId}`

  let movimientos
  if (kardex.sinPermiso) movimientos = <EstadoSinPermiso />
  else if (kardex.error) movimientos = <EstadoError error={kardex.error} alReintentar={kardex.recargar} />
  else if (!kardex.datos) movimientos = <EstadoCarga mensaje="Cargando movimientos…" />
  else if (kardex.datos.length === 0) {
    movimientos = (
      <EstadoVacio
        titulo="Este producto todavía no tiene movimientos"
        detalle="Cuando entre mercancía, se venda o se ajuste su existencia, quedará anotado aquí."
      />
    )
  } else {
    movimientos = (
      <>
        <TablaMovimientos movimientos={kardex.datos} porProducto etiqueta="Historial de movimientos" />
        <NotaLimite cantidad={kardex.datos.length} limite={limite} />
      </>
    )
  }

  return (
    <div className="contenido inventario">
      {VOLVER}
      <EncabezadoPagina
        titulo={producto.nombre}
        descripcion={`${producto.codigo} · Historial de movimientos`}
        acciones={(
          <>
            <Link to={`/inventario/ingresos/nuevo${aQuery}`} className="boton secundario enlace-boton">Registrar ingreso</Link>
            <Link to={`/inventario/ajustes/nuevo${aQuery}`} className="boton enlace-boton">Ajustar existencia</Link>
          </>
        )}
      />
      {cuadre.existencia < 0 && (
        <p className="nota-reponer">
          Debes reponer {unidades(-cuadre.existencia)}: la existencia está en negativo porque se
          vendió más de lo que había registrado.
        </p>
      )}
      <CuadreProducto cuadre={cuadre} />
      <div className="barra-inventario">
        <SelectorLimite limite={limite} alCambiar={setLimite} />
      </div>
      {movimientos}
      <p className="nota">{NOTA_SIN_BORRAR}</p>
    </div>
  )
}
