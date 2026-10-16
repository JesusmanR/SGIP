import { useCallback, useEffect, useRef, useState } from 'react'
import { api, imagenAutenticada } from '../servicios/api.js'
import { FORMATOS_ACEPTADOS, prepararImagen } from '../servicios/imagenes.js'

/** El RF-63 admite cinco por producto, y el esquema lo hace cumplir. */
const MAXIMO = 5

/** Una miniatura de la galería, con su propia descarga autenticada. */
function Miniatura({ imagen }) {
  const [objeto, setObjeto] = useState(null)

  useEffect(() => {
    let vigente = true
    let creada = null
    imagenAutenticada(imagen.url)
      .then((url) => {
        if (!vigente) return URL.revokeObjectURL(url)
        creada = url
        setObjeto(url)
      })
      .catch(() => {})
    return () => { vigente = false; if (creada) URL.revokeObjectURL(creada) }
  }, [imagen.url])

  return objeto
    ? <img src={objeto} alt={`Imagen ${imagen.orden}`} />
    : <span className="pista">…</span>
}

/**
 * Las imágenes de un producto: verlas, elegir cuál es la principal y
 * borrar las que sobren.
 *
 * Sin esto, subir una imagen equivocada era definitivo: la miniatura del
 * catálogo solo muestra la principal y no había forma de llegar a las
 * demás.
 */
export default function Galeria({ productoId, nombre, puedeEditar, onCerrar, onCambio }) {
  const [imagenes, setImagenes] = useState([])
  const [error, setError] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [rechazadas, setRechazadas] = useState([])
  const entrada = useRef(null)

  /** Devuelve la lista fresca: quien llama necesita cuántas quedaron. */
  const recargar = useCallback(async () => {
    try {
      const frescas = await api.imagenesDe(productoId)
      setImagenes(frescas)
      setError(null)
      return frescas
    } catch (e) {
      setError(e.message)
      return null
    }
  }, [productoId])

  useEffect(() => { recargar() }, [recargar])

  async function accion(hacer) {
    setOcupado(true)
    setError(null)
    try {
      await hacer()
      const frescas = await recargar()
      // Cuántas quedaron: borrar la última tiene que quitar la miniatura
      // del catálogo, no solo refrescarla.
      onCambio?.(frescas?.length ?? 0)
    } catch (e) {
      setError(e.message)
    } finally {
      setOcupado(false)
    }
  }

  /**
   * Varias imágenes de una vez, pero de una en una.
   *
   * La subida es secuencial a propósito, y no por prudencia: en paralelo
   * se pisarían. Dos peticiones simultáneas sobre un producto sin fotos
   * leen las dos que no hay ninguna, las dos se declaran principal y el
   * índice único rechaza a la segunda; y las dos piden el mayor orden en
   * uso, obtienen el mismo número y chocan contra
   * uq_producto_imagenes_orden. El servidor hace bien en impedirlo: el
   * que no debe provocarlo es el cliente.
   *
   * Un archivo que falle no detiene a los demás. Subir cinco y perderlas
   * todas porque la tercera era un HEIC sería peor que decir cuál falló.
   */
  async function subirVarias(evento) {
    const elegidas = Array.from(evento.target.files ?? [])
    // Limpiar el valor permite volver a elegir los MISMOS archivos tras
    // un error; sin esto el navegador no dispara el evento otra vez.
    evento.target.value = ''
    if (elegidas.length === 0) return

    setOcupado(true)
    setError(null)
    setRechazadas([])

    const problemas = []
    let subidas = 0
    try {
      for (const archivo of elegidas) {
        if (imagenes.length + subidas >= MAXIMO) {
          problemas.push(`${archivo.name}: ya hay ${MAXIMO} imágenes, que es el máximo.`)
          continue
        }
        try {
          await api.subirImagen(productoId, await prepararImagen(archivo))
          subidas += 1
        } catch (e) {
          problemas.push(`${archivo.name}: ${e.message}`)
        }
      }
      const frescas = await recargar()
      if (subidas > 0) onCambio?.(frescas?.length ?? subidas)
    } finally {
      setOcupado(false)
      setRechazadas(problemas)
    }
  }

  return (
    <div className="velo" role="dialog" aria-modal="true" aria-label={`Imágenes de ${nombre}`}>
      <div className="tarjeta panel">
        <div className="panel-cabecera">
          <h3>Imágenes de {nombre}</h3>
          <button className="boton secundario" onClick={onCerrar}>Cerrar</button>
        </div>

        {error && <div className="aviso error" role="alert">{error}</div>}

        {rechazadas.length > 0 && (
          <div className="aviso error" role="alert">
            <p>No se pudieron subir:</p>
            <ul>
              {rechazadas.map((r) => <li key={r}>{r}</li>)}
            </ul>
          </div>
        )}

        {puedeEditar && (
          <div className="panel-acciones">
            <button
              className="boton"
              disabled={ocupado || imagenes.length >= MAXIMO}
              onClick={() => entrada.current?.click()}
            >
              {ocupado ? 'Subiendo…' : 'Añadir imágenes'}
            </button>
            <span className="sub-linea">
              {imagenes.length >= MAXIMO
                ? `Ya hay ${MAXIMO}. Elimina alguna para subir otra.`
                : `Puedes elegir varias a la vez; caben ${MAXIMO - imagenes.length} más.`}
            </span>
            <input
              ref={entrada}
              type="file"
              accept={FORMATOS_ACEPTADOS}
              multiple
              hidden
              onChange={subirVarias}
            />
          </div>
        )}

        {imagenes.length === 0 && !error && !ocupado && (
          <p className="sub-linea">Este producto todavía no tiene imágenes.</p>
        )}

        <ul className="galeria">
          {imagenes.map((i) => (
            <li key={i.id} className={i.principal ? 'principal' : ''}>
              <div className="casilla-imagen grande">
                <Miniatura imagen={i} />
              </div>
              {i.principal
                ? <span className="insignia activo">Principal</span>
                : puedeEditar && (
                  <button
                    className="boton secundario menudo"
                    disabled={ocupado}
                    onClick={() => accion(() => api.hacerPrincipal(i.id))}
                  >
                    Hacer principal
                  </button>
                )}
              {puedeEditar && (
                <button
                  className="boton secundario menudo"
                  disabled={ocupado}
                  onClick={() => accion(() => api.borrarImagen(i.id))}
                >
                  Eliminar
                </button>
              )}
            </li>
          ))}
        </ul>

        <p className="sub-linea">
          Hasta cinco imágenes por producto. La principal es la que aparece en el
          catálogo y en el documento exportable.
        </p>
      </div>
    </div>
  )
}
