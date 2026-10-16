import { useEffect, useRef, useState } from 'react'
import { imagenAutenticada } from '../servicios/api.js'

/**
 * Casilla de imagen que además sirve para reemplazarla.
 *
 * El input de archivo va oculto y lo dispara la propia miniatura: un
 * «Seleccionar archivo» suelto junto a cada fila convertiría la tabla en
 * una lista de botones. La miniatura ya dice qué hay y dónde tocar.
 *
 * La imagen NO se pone directamente en el src. Una etiqueta <img> pide
 * su contenido por cuenta del navegador, sin cabecera de autorización, y
 * el backend la rechaza por anónima. Se trae con fetch autenticado y se
 * muestra como URL de objeto, que hay que liberar al dejar de usarla.
 */
export default function SubirImagen({ src, alt, onSubir, puedeEditar, redondo = false }) {
  const entrada = useRef(null)
  const [subiendo, setSubiendo] = useState(false)
  const [version, setVersion] = useState(0)
  const [objeto, setObjeto] = useState(null)
  const [fallo, setFallo] = useState(false)

  useEffect(() => {
    if (!src) {
      setObjeto(null)
      return undefined
    }
    let vigente = true
    let creada = null

    setFallo(false)
    imagenAutenticada(src)
      .then((url) => {
        if (!vigente) {
          // El componente cambió de imagen mientras esta se descargaba.
          URL.revokeObjectURL(url)
          return
        }
        creada = url
        setObjeto(url)
      })
      .catch(() => {
        if (vigente) {
          setObjeto(null)
          setFallo(true)
        }
      })

    return () => {
      vigente = false
      if (creada) URL.revokeObjectURL(creada)
    }
    // version fuerza a traerla de nuevo tras reemplazarla: la dirección
    // no cambia y sin esto se quedaría la anterior.
  }, [src, version])

  async function elegido(evento) {
    const archivo = evento.target.files?.[0]
    // Limpiar el valor permite volver a elegir el MISMO archivo después
    // de un error; sin esto el navegador no dispara el evento otra vez.
    evento.target.value = ''
    if (!archivo) return

    setSubiendo(true)
    try {
      await onSubir(archivo)
      setVersion((v) => v + 1)
    } finally {
      setSubiendo(false)
    }
  }

  return (
    <>
      <button
        type="button"
        className={`casilla-imagen${redondo ? ' redondo' : ''}`}
        onClick={() => entrada.current?.click()}
        disabled={!puedeEditar || subiendo}
        title={puedeEditar ? 'Subir o reemplazar la imagen' : alt}
      >
        {subiendo ? (
          <span className="pista">…</span>
        ) : objeto ? (
          <img src={objeto} alt={alt} />
        ) : (
          <span className="pista">{fallo ? '!' : puedeEditar ? '+' : '—'}</span>
        )}
      </button>
      <input
        ref={entrada}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        hidden
        onChange={elegido}
      />
    </>
  )
}
