import { useState } from 'react'
import { Link } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { listarOrdenes } from '../../servicios/api/compras.js'
import { fechaCorta } from '../../servicios/formato.js'
import { InsigniaEstado, pesos } from './EstadoOrden.jsx'

/** El servidor trae 50 por omisión y no pasa de 200: se pide más de a poco. */
const ESCALONES = [50, 100, 200]

const FILTROS = [
  ['', 'Todas'],
  ['ABIERTA', 'Pendientes'],
  ['PARCIAL', 'Recibidas en parte'],
  ['RECIBIDA', 'Recibidas'],
  ['ANULADA', 'Anuladas'],
]

export default function ListaOrdenes() {
  const [estado, setEstado] = useState('')
  const [limite, setLimite] = useState(ESCALONES[0])
  const { datos, error, sinPermiso, recargar } = useDatos(
    () => listarOrdenes({ estado, limite }), [estado, limite],
  )

  const cambiarEstado = (nuevo) => { setEstado(nuevo); setLimite(ESCALONES[0]) }
  const siguiente = ESCALONES.find((n) => n > limite)
  const hayMas = datos && datos.length >= limite

  let cuerpo
  if (sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (error) cuerpo = <EstadoError error={error} alReintentar={recargar} />
  else if (!datos) cuerpo = <EstadoCarga mensaje="Cargando órdenes de compra…" />
  else if (datos.length === 0 && estado) {
    cuerpo = (
      <EstadoVacio
        titulo="No hay órdenes con ese estado"
        accion={<button type="button" className="boton secundario" onClick={() => cambiarEstado('')}>Quitar el filtro</button>}
      />
    )
  } else if (datos.length === 0) {
    cuerpo = (
      <EstadoVacio
        titulo="Todavía no hay órdenes de compra"
        detalle="Una orden es el pedido que le haces a un proveedor."
        accion={<Link className="boton" to="/compras/nueva">Crear la primera orden</Link>}
      />
    )
  } else {
    cuerpo = (
      <>
        <TablaOTarjetas
          etiqueta="Órdenes de compra"
          filas={datos}
          claveFila={(o) => o.ordenId}
          columnas={[
            {
              clave: 'consecutivo', titulo: 'Orden', principal: true,
              render: (o) => <Link to={`/compras/${o.ordenId}`}>{o.consecutivo}</Link>,
            },
            { clave: 'proveedor', titulo: 'Proveedor' },
            { clave: 'fecha', titulo: 'Fecha', render: (o) => fechaCorta(o.fecha) },
            { clave: 'estado', titulo: 'Estado', render: (o) => <InsigniaEstado estado={o.estado} /> },
            { clave: 'total', titulo: 'Total', numerica: true, render: (o) => pesos(o.total) },
          ]}
        />
        {hayMas && siguiente && (
          <div className="acciones-pie">
            <button type="button" className="boton secundario" onClick={() => setLimite(siguiente)}>
              Ver más órdenes
            </button>
          </div>
        )}
        {hayMas && !siguiente && (
          <p className="leyenda">
            Se muestran las 200 más recientes. Para ver otras, filtra por estado.
          </p>
        )}
      </>
    )
  }

  return (
    <div className="contenido">
      <EncabezadoPagina
        titulo="Órdenes de compra"
        descripcion="Lo que le pides a tus proveedores y lo que ya te entregaron."
        acciones={<Link className="boton" to="/compras/nueva">Nueva orden</Link>}
      />
      {!sinPermiso && !error && (
        <div className="filtros">
          <div className="campo">
            <label htmlFor="filtro-estado"><span>Estado</span></label>
            <select id="filtro-estado" value={estado} onChange={(e) => cambiarEstado(e.target.value)}>
              {FILTROS.map(([valor, texto]) => <option key={valor} value={valor}>{texto}</option>)}
            </select>
          </div>
        </div>
      )}
      {cuerpo}
      {datos?.length > 0 && (
        <Aviso tipo="info">
          El inventario solo aumenta al registrar una recepción. Puedes recibir una orden en varias entregas.
        </Aviso>
      )}
    </div>
  )
}
