import { useRef, useState } from 'react'

/**
 * Casilla de imagen que además sirve para reemplazarla.
 *
 * El input de archivo va oculto y lo dispara la propia miniatura: un
 * «Seleccionar archivo» suelto junto a cada fila convertiría la tabla en
 * una lista de botones. La miniatura ya dice qué hay y dónde tocar.
 */
export default function SubirImagen({ src, alt, onSubir, puedeEditar, redondo = false }) {
  const entrada = useRef(null)
  const [subiendo, setSubiendo] = useState(false)
  const [version, setVersion] = useState(0)

  async function elegido(evento) {
    const archivo = evento.target.files?.[0]
    // Limpiar el valor permite volver a elegir el MISMO archivo después
    // de un error; sin esto el navegador no dispara el evento otra vez.
    evento.target.value = ''
    if (!archivo) return

    setSubiendo(true)
    try {
      await onSubir(archivo)
      // El navegador cachea la imagen por su URL, que no cambia al
      // reemplazarla. El contador la obliga a pedirla de nuevo.
      setVersion((v) => v + 1)
    } finally {
      setSubiendo(false)
    }
  }

  const clase = `casilla-imagen${redondo ? ' redondo' : ''}`

  return (
    <>
      <button
        type="button"
        className={clase}
        onClick={() => entrada.current?.click()}
        disabled={!puedeEditar || subiendo}
        title={puedeEditar ? 'Subir o reemplazar la imagen' : alt}
      >
        {subiendo ? (
          <span className="pista">…</span>
        ) : src ? (
          <img src={`${src}?v=${version}`} alt={alt} />
        ) : (
          <span className="pista">{puedeEditar ? '+' : '—'}</span>
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
