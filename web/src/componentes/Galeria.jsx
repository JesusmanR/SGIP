import { useCallback, useEffect, useState } from 'react'
import { api, imagenAutenticada } from '../servicios/api.js'

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

  const recargar = useCallback(async () => {
    try {
      setImagenes(await api.imagenesDe(productoId))
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }, [productoId])

  useEffect(() => { recargar() }, [recargar])

  async function accion(hacer) {
    setOcupado(true)
    setError(null)
    try {
      await hacer()
      await recargar()
      onCambio?.()
    } catch (e) {
      setError(e.message)
    } finally {
      setOcupado(false)
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

        {imagenes.length === 0 && !error && (
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
