import { Link } from 'react-router-dom'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { fechaCorta, NUMERO } from '../../servicios/formato.js'
import { conSigno, LIMITE_MAXIMO, tipoDe, variacion } from './movimientos.js'

function Detalle({ m }) {
  const partes = []
  if (m.motivo) partes.push(<span key="m">{m.motivo}</span>)
  if (m.documento) partes.push(<span key="d">Documento {m.documento}</span>)
  if (m.recepcionId) partes.push(<span key="r">Recepción de compra n.º {m.recepcionId}</span>)
  return partes.length === 0 ? '—' : <span className="detalle-movimiento">{partes}</span>
}

/**
 * Las filas de un kárdex. Con `porProducto` la lista es de una sola
 * referencia, y entonces se puede comprobar la cadena: la existencia con
 * la que arranca un movimiento tiene que ser en la que terminó el anterior
 * (los datos llegan del más reciente al más antiguo). Un hueco ahí es lo
 * único que las restricciones de la base no pueden ver, así que se dice.
 */
export default function TablaMovimientos({ movimientos, porProducto = false, etiqueta }) {
  const hueco = (m, i) => {
    const anterior = movimientos[i + 1]
    return porProducto && anterior && anterior.existenciaPosterior !== m.existenciaAnterior
      ? anterior.existenciaPosterior : null
  }

  const columnas = [
    { clave: 'fecha', titulo: 'Fecha', principal: porProducto, render: (m) => fechaCorta(m.fecha) },
    ...(porProducto ? [] : [{
      clave: 'producto', titulo: 'Producto', principal: true,
      render: (m) => (
        <span className="producto-movimiento">
          <Link to={`/inventario/productos/${m.productoId}`}>{m.producto}</Link>
          <small>{m.codigo}</small>
        </span>
      ),
    }]),
    {
      clave: 'tipo', titulo: 'Tipo',
      render: (m) => <span className={`tipo-movimiento ${tipoDe(m.tipo).clase}`}>{tipoDe(m.tipo).texto}</span>,
    },
    {
      clave: 'variacion', titulo: 'Cambio', numerica: true,
      render: (m) => <span className={`cambio ${variacion(m) < 0 ? 'cambio-baja' : ''}`}>{conSigno(variacion(m))}</span>,
    },
    {
      clave: 'existencia', titulo: 'Existencia', numerica: true,
      render: (m) => {
        const i = movimientos.indexOf(m)
        const termino = hueco(m, i)
        return (
          <span className="existencia-movimiento">
            <span>{`${NUMERO.format(m.existenciaAnterior)} → ${NUMERO.format(m.existenciaPosterior)}`}</span>
            {termino !== null && (
              <small className="hueco">
                No sigue al movimiento anterior (terminó en {NUMERO.format(termino)})
              </small>
            )}
          </span>
        )
      },
    },
    { clave: 'detalle', titulo: 'Detalle', render: (m) => <Detalle m={m} /> },
    { clave: 'usuario', titulo: 'Responsable', render: (m) => m.usuario ?? '—' },
  ]

  return (
    <TablaOTarjetas
      etiqueta={etiqueta}
      columnas={columnas}
      filas={movimientos}
      claveFila={(m) => m.movimientoId}
    />
  )
}

/** Dice que la lista es una ventana de lo más reciente cuando llena el límite. */
export function NotaLimite({ cantidad, limite }) {
  if (cantidad < limite) return null
  return (
    <p className="nota-limite">
      Se muestran los últimos {limite} movimientos; puede haber más antiguos.{' '}
      {limite < LIMITE_MAXIMO
        ? 'Sube la cantidad para ver más.'
        : 'Es el máximo que entrega el sistema.'}
    </p>
  )
}
