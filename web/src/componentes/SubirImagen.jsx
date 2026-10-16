import { useEffect, useRef, useState } from 'react'
import { imagenAutenticada } from '../servicios/api.js'
import { FORMATOS_ACEPTADOS, prepararImagen } from '../servicios/imagenes.js'

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
export default function SubirImagen({
  src,
  alt,
  onSubir,
  onFallo,
  puedeEditar,
  redondo = false,
  recargar = 0,
}) {
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
    // version la vuelve a pedir tras reemplazarla y recargar tras un
    // cambio hecho en otra parte —la galería—: la dirección no cambia.
    //
    // Que esto baste depende del servidor: mientras la principal de un
    // producto se servía con una semana de caché, pedirla otra vez
    // devolvía los bytes viejos sin salir del navegador. Ahora va con
    // no-store justamente para que este efecto sirva de algo.
  }, [src, version, recargar])

  async function elegido(evento) {
    const archivo = evento.target.files?.[0]
    // Limpiar el valor permite volver a elegir el MISMO archivo después
    // de un error; sin esto el navegador no dispara el evento otra vez.
    evento.target.value = ''
    if (!archivo) return

    setSubiendo(true)
    try {
      // Se prepara antes de enviar: convierte lo que el servidor no sabe
      // leer y reduce lo que no hace falta mandar entero.
      await onSubir(await prepararImagen(archivo))
      setVersion((v) => v + 1)
    } catch (e) {
      // Atraparlo aquí no es cosmética: sin esto el rechazo quedaba como
      // una promesa rechazada sin dueño, visible solo en la consola.
      onFallo?.(e.message)
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
        accept={FORMATOS_ACEPTADOS}
        hidden
        onChange={elegido}
      />
    </>
  )
}
