import { useId, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiInventario } from '../../servicios/api/inventario.js'
import { NUMERO } from '../../servicios/formato.js'
import { TOPE_BUSQUEDA } from './movimientos.js'

const AYUDA = 'Escribe el nombre o el código del producto y pulsa Buscar.'

/**
 * Busca un producto en el servidor y deja elegir uno.
 *
 * Busca al pulsar Buscar o Enter, no a cada tecla: es una petición por
 * intención, y el catálogo nunca entra entero al estado. No es un
 * formulario para poder vivir dentro de otro.
 */
export default function ElegirProducto({ etiqueta, alElegir, error: errorDelForm }) {
  const id = useId()
  const [texto, setTexto] = useState('')
  const [buscado, setBuscado] = useState(null)
  const [vacio, setVacio] = useState(false)
  const { datos, cargando, error, sinPermiso, recargar } = useDatos(
    () => (buscado ? apiInventario.buscarProductos(buscado) : Promise.resolve(null)),
    [buscado],
  )

  function buscar() {
    const limpio = texto.trim()
    setVacio(limpio === '')
    if (limpio !== '') setBuscado(limpio)
  }

  if (sinPermiso) return <EstadoSinPermiso />

  const motivo = vacio ? 'Escribe el nombre o el código del producto para buscar.' : errorDelForm
  const idMensaje = `${id}-mensaje`

  let resultados = null
  // Los resultados son del texto buscado: si se editó sin buscar, no se muestran.
  if (buscado && buscado === texto.trim()) {
    if (error) {
      resultados = (
        <div className="busqueda-error">
          <Aviso tipo="peligro">{error.message}</Aviso>
          <button type="button" className="boton secundario" onClick={recargar}>Reintentar</button>
        </div>
      )
    } else if (cargando && !datos) {
      resultados = <p role="status" className="busqueda-estado">Buscando…</p>
    } else if (datos?.length === 0) {
      resultados = <p role="status" className="busqueda-estado">No hay productos con esa búsqueda. Prueba con otra parte del nombre o con el código.</p>
    } else if (datos) {
      resultados = (
        <>
          <ul className="resultados" aria-label="Resultados de la búsqueda">
            {datos.map((p) => (
              <li key={p.productoId}>
                <button type="button" className="resultado" onClick={() => alElegir(p)}>
                  <strong>{p.nombre}</strong>
                  <span>{p.codigo}</span>
                  <span>Existencia: {NUMERO.format(p.existencia)}</span>
                </button>
              </li>
            ))}
          </ul>
          {datos.length >= TOPE_BUSQUEDA && (
            <p role="status" className="busqueda-estado">
              Se muestran los primeros {TOPE_BUSQUEDA}. Escribe más del nombre para afinar.
            </p>
          )}
        </>
      )
    }
  }

  return (
    <div className="elegir-producto">
      <div className="campo">
        <label htmlFor={id}><span>{etiqueta}</span></label>
        <div className="buscador">
          <input
            id={id}
            type="text"
            autoComplete="off"
            value={texto}
            aria-invalid={motivo ? 'true' : undefined}
            aria-describedby={idMensaje}
            onChange={(e) => setTexto(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); buscar() } }}
          />
          <button type="button" className="boton secundario" onClick={buscar}>Buscar</button>
        </div>
        <small id={idMensaje} className={`campo-mensaje ${motivo ? 'error' : ''}`}>{motivo || AYUDA}</small>
      </div>
      {resultados}
    </div>
  )
}
