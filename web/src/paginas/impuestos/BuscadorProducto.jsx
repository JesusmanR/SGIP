import { useId, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { apiImpuestos } from '../../servicios/api/impuestos.js'

const AYUDA = 'Escribe el nombre o el código del producto y pulsa Buscar.'

/**
 * Busca un producto en el servidor y deja elegir uno. Busca al pulsar Buscar o
 * Enter, no a cada tecla: es una petición por intención, y el catálogo nunca
 * entra entero a la pantalla. No es un formulario porque vive dentro de otro.
 */
export default function BuscadorProducto({ elegido, alElegir, alCambiar, motivo }) {
  const id = useId()
  const [texto, setTexto] = useState('')
  const [buscado, setBuscado] = useState(null)
  const [vacio, setVacio] = useState(false)
  const { datos, cargando, error, recargar } = useDatos(
    () => (buscado ? apiImpuestos.buscarProductos(buscado) : Promise.resolve(null)),
    [buscado],
  )

  function buscar() {
    const limpio = texto.trim()
    setVacio(limpio === '')
    if (limpio !== '') setBuscado(limpio)
  }

  if (elegido) {
    return (
      <div className="campo producto-elegido-caja">
        <p className="producto-elegido">
          Producto elegido: <strong>{elegido.nombre}</strong> ({elegido.codigo})
        </p>
        <button type="button" className="boton secundario menudo" onClick={alCambiar}>Elegir otro</button>
      </div>
    )
  }

  const mensaje = vacio ? 'Escribe el nombre o el código del producto para buscar.' : motivo
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
      resultados = <p role="status" className="nota">Buscando…</p>
    } else if (datos?.length === 0) {
      resultados = (
        <p role="status" className="nota">
          No hay productos con esa búsqueda. Prueba con otra parte del nombre o con el código.
        </p>
      )
    } else if (datos) {
      resultados = (
        <ul className="resultados" aria-label="Resultados de la búsqueda">
          {datos.map((p) => (
            <li key={p.productoId}>
              <button type="button" className="resultado" onClick={() => alElegir(p)}>
                <strong>{p.nombre}</strong>
                <span>{p.codigo}</span>
              </button>
            </li>
          ))}
        </ul>
      )
    }
  }

  return (
    <div className="buscador-producto">
      <div className="campo">
        <label htmlFor={id}><span>Buscar producto</span></label>
        <div className="buscador">
          <input
            id={id}
            type="text"
            autoComplete="off"
            value={texto}
            aria-invalid={mensaje ? 'true' : undefined}
            aria-describedby={idMensaje}
            onChange={(e) => setTexto(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); buscar() } }}
          />
          <button type="button" className="boton secundario" onClick={buscar}>Buscar</button>
        </div>
        <small id={idMensaje} className={`campo-mensaje ${mensaje ? 'error' : ''}`}>{mensaje || AYUDA}</small>
      </div>
      {resultados}
    </div>
  )
}
