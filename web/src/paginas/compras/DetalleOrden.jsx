import { useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { verOrden } from '../../servicios/api/compras.js'
import { fechaCorta } from '../../servicios/formato.js'
import { InsigniaEstado, nombreDeEstado, pesos } from './EstadoOrden.jsx'
import FormularioRecepcion from './FormularioRecepcion.jsx'

const volver = <Link to="/compras">Volver a órdenes de compra</Link>

const COLUMNAS = [
  {
    clave: 'producto', titulo: 'Producto', principal: true,
    render: (l) => <>{l.producto}<span className="sub-linea">{l.codigo}</span></>,
  },
  { clave: 'cantidad', titulo: 'Pedido', numerica: true },
  { clave: 'cantidadRecibida', titulo: 'Recibido', numerica: true },
  { clave: 'pendiente', titulo: 'Pendiente', numerica: true },
  { clave: 'costoUnitario', titulo: 'Costo unitario', numerica: true, render: (l) => pesos(l.costoUnitario) },
  { clave: 'subtotal', titulo: 'Subtotal', numerica: true, render: (l) => pesos(l.subtotal) },
]

/** La orden ya cargada. Se separa para que cada orden tenga su propio estado. */
function Orden({ inicial, creada }) {
  const [orden, setOrden] = useState(inicial)
  const [mensaje, setMensaje] = useState(null)

  const alRecibir = (actualizada) => {
    setOrden(actualizada)
    setMensaje(`Recepción registrada. La orden quedó: ${nombreDeEstado(actualizada.estado)}.`)
  }

  return (
    <div className="contenido">
      <EncabezadoPagina titulo={`Orden ${orden.consecutivo}`} acciones={volver} />
      {creada && !mensaje && (
        <Aviso tipo="exito">Orden creada. Todavía no cambia el inventario: eso ocurre al registrar la recepción.</Aviso>
      )}
      {mensaje && <Aviso tipo="exito">{mensaje}</Aviso>}
      <dl className="ficha">
        <div><dt>Proveedor</dt><dd>{orden.proveedor}</dd></div>
        <div><dt>Estado</dt><dd><InsigniaEstado estado={orden.estado} /></dd></div>
        <div><dt>Fecha</dt><dd>{fechaCorta(orden.fecha)}</dd></div>
        <div><dt>Registrada por</dt><dd>{orden.usuario}</dd></div>
        {orden.observacion && <div><dt>Observación</dt><dd>{orden.observacion}</dd></div>}
      </dl>

      <TablaOTarjetas
        etiqueta="Productos de la orden"
        filas={orden.items}
        claveFila={(l) => l.itemId}
        columnas={COLUMNAS}
      />
      <p className="total-vista"><span>Total de la orden</span> <strong>{pesos(orden.total)}</strong></p>

      {(orden.estado === 'ABIERTA' || orden.estado === 'PARCIAL') && (
        <FormularioRecepcion orden={orden} alRecibir={alRecibir} />
      )}
      {orden.estado === 'RECIBIDA' && <p className="leyenda">Esta orden ya se recibió completa.</p>}
      {orden.estado === 'ANULADA' && <p className="leyenda">Esta orden está anulada y no admite recepciones.</p>}
    </div>
  )
}

export default function DetalleOrden() {
  const { ordenId } = useParams()
  const { state } = useLocation()
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(() => verOrden(ordenId), [ordenId])

  if (sinPermiso) return <div className="contenido"><EstadoSinPermiso /></div>
  if (error?.estado === 404) {
    return (
      <div className="contenido">
        <EstadoVacio titulo="Esa orden no existe" detalle="Quizá el enlace está mal escrito." accion={volver} />
      </div>
    )
  }
  if (error) return <div className="contenido"><EstadoError error={error} alReintentar={recargar} /></div>
  if (cargando && !datos) return <div className="contenido"><EstadoCarga mensaje="Cargando orden…" /></div>

  return <Orden key={datos.ordenId} inicial={datos} creada={state?.creada} />
}
