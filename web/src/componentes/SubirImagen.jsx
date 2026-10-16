import { useEffect, useRef, useState } from 'react'
import { imagenAutenticada } from '../servicios/api.js'
import { FORMATOS_ACEPTADOS } from '../servicios/imagenes.js'

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
 *
 * onSubir recibe SIEMPRE un arreglo, incluso cuando multiple es falso.
 * Este componente recoge archivos y nada más: no los prepara ni decide
 * qué hacer con ellos, porque eso depende de si son fotos de producto o
 * un logo, y quien llama es el único que lo sabe.
 */
export default function SubirImagen({
  src,
  alt,
  onSubir,
  onFallo,
  puedeEditar,
  redondo = false,
  multiple = false,
  ayuda,
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
    // version la vuelve a pedir tras reemplazarla, y hace falta solo
    // donde la dirección no cambia aunque el contenido sí: el logo de la
    // empresa, que es siempre /api/v1/empresa/logo. En el catálogo la
    // dirección lleva el identificador de la imagen, de modo que
    // cambiarla cambia el src y esto ni se usa.
  }, [src, version])

  async function elegido(evento) {
    const archivos = Array.from(evento.target.files ?? [])
    // Limpiar el valor permite volver a elegir los MISMOS archivos
    // después de un error; sin esto el navegador no dispara el evento
    // otra vez.
    evento.target.value = ''
    if (archivos.length === 0) return

    setSubiendo(true)
    try {
      await onSubir(archivos)
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
        title={puedeEditar
          ? (ayuda ?? (multiple
            ? 'Subir imágenes. Puedes elegir varias a la vez.'
            : 'Subir o reemplazar la imagen'))
          : alt}
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
        multiple={multiple}
        hidden
        onChange={elegido}
      />
    </>
  )
}
