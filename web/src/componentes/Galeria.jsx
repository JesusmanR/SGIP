import { useCallback, useEffect, useRef, useState } from 'react'
import { api, imagenAutenticada } from '../servicios/api.js'
import { FORMATOS_ACEPTADOS } from '../servicios/imagenes.js'
import { MAXIMO_POR_PRODUCTO as MAXIMO, subirImagenes } from '../servicios/subidas.js'

/** Una miniatura de la galería, con su propia descarga autenticada. */
function Miniatura({ imagen }) {
  const [objeto, setObjeto] = useState(null)

  useEffect(() => {
    let vigente = true
    let creada = null
    // La miniatura, no la imagen entera: se pinta a 120 puntos y la
    // completa son 800×800. Abrir una galería de cinco descargaba medio
    // mega para nada.
    imagenAutenticada(imagen.urlMiniatura)
      .then((url) => {
        if (!vigente) return URL.revokeObjectURL(url)
        creada = url
        setObjeto(url)
      })
      .catch(() => {})
    return () => { vigente = false; if (creada) URL.revokeObjectURL(creada) }
  }, [imagen.urlMiniatura])

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
      // Se devuelve la lista, no un aviso: quien escucha necesita saber
      // cuál quedó como principal, porque su identificador es el que
      // forma la dirección de la miniatura en el catálogo.
      onCambio?.(frescas ?? [])
    } catch (e) {
      setError(e.message)
    } finally {
      setOcupado(false)
    }
  }

  /**
   * Varias imágenes de una vez. El bucle vive en servicios/subidas.js
   * porque el recuadro de la tabla hace exactamente lo mismo, y dos
   * copias del mismo bucle acaban discrepando.
   *
   * Aquí sí se sabe cuántas hay ya, de modo que se avisa de las que no
   * caben sin gastar un viaje al servidor.
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

    let problemas = []
    try {
      const resultado = await subirImagenes(productoId, elegidas, imagenes.length)
      problemas = resultado.problemas
      const frescas = await recargar()
      if (resultado.creadas.length > 0) onCambio?.(frescas ?? [])
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
