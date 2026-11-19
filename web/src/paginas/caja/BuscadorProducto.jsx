import { useEffect, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import { productoPorCodigo, productosPorTexto } from '../../servicios/api/caja.js'
import '../../estilos/caja.css'
import Dialogo from './Dialogo.jsx'

const FORMATOS = ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39']
const CADA_CUANTO_MIRA = 400

/** La cámara se ofrece solo si el navegador sabe leer códigos de barras. */
function haySoporteDeCamara() {
  return typeof window !== 'undefined' && 'BarcodeDetector' in window
    && Boolean(navigator.mediaDevices?.getUserMedia)
}

/**
 * La cámara en un diálogo: lee el primer código que vea con el `BarcodeDetector`
 * del navegador (sin librerías) y lo entrega. Al cerrarse apaga la cámara.
 */
function DialogoCamara({ alLeer, alCerrar }) {
  const video = useRef(null)
  const [fallo, setFallo] = useState(null)
  // La lectura vive en un ref: si cambiara con cada pintado, la cámara se reiniciaría.
  const leer = useRef(alLeer)
  leer.current = alLeer

  useEffect(() => {
    let cerrado = false
    let flujo = null
    let temporizador = null
    let mirando = false

    async function arrancar() {
      try {
        const detector = new window.BarcodeDetector({ formats: FORMATOS })
        flujo = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
        if (cerrado) {
          flujo.getTracks().forEach((t) => t.stop())
          return
        }
        if (video.current) video.current.srcObject = flujo
        temporizador = setInterval(async () => {
          if (mirando || cerrado) return
          mirando = true
          try {
            const codigos = await detector.detect(video.current)
            const leido = codigos?.[0]?.rawValue
            if (leido && !cerrado) {
              cerrado = true
              clearInterval(temporizador)
              leer.current(leido)
            }
          } catch {
            // Un cuadro que no se pudo leer no es un fallo: se mira el siguiente.
          } finally {
            mirando = false
          }
        }, CADA_CUANTO_MIRA)
      } catch {
        if (!cerrado) setFallo('No pudimos abrir la cámara. Revisa el permiso del navegador o escribe el código.')
      }
    }
    arrancar()
    return () => {
      cerrado = true
      clearInterval(temporizador)
      flujo?.getTracks().forEach((t) => t.stop())
    }
  }, [])

  return (
    <Dialogo titulo="Escanear con la cámara" alCerrar={alCerrar}>
      {fallo
        ? <p role="alert" className="caja-error-texto">{fallo}</p>
        : <p className="nota">Apunta al código de barras. Se lee solo.</p>}
      <video ref={video} className="caja-video" autoPlay playsInline muted aria-hidden="true" />
      <div className="caja-acciones">
        <button type="button" className="boton secundario" onClick={alCerrar}>Cerrar</button>
      </div>
    </Dialogo>
  )
}

/**
 * El campo de la caja: un lector de código de barras es un teclado que escribe
 * el código y pulsa Enter, así que Enter busca por código. Si no existe, se
 * ofrece buscar por nombre o, con un administrador, crear el producto.
 */
export default function BuscadorProducto({ alAgregar, alCrearProducto, entradaRef, alCambiarPendientes }) {
  const propia = useRef(null)
  const campo = entradaRef ?? propia
  const [texto, setTexto] = useState('')
  const [pendientes, setPendientes] = useState(0)
  // Los códigos que no existen se quedan a la vista hasta que alguien los atienda o los descarte.
  const [desconocidos, setDesconocidos] = useState([])
  const [resultados, setResultados] = useState(null)
  const [resultadosDe, setResultadosDe] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [fallo, setFallo] = useState(null)
  const [camara, setCamara] = useState(false)
  const camaraUsada = useRef(false)
  // Los códigos se procesan de uno en uno y en el orden en que se leyeron: un lector puede
  // mandar el segundo antes de que el servidor responda el primero, y ninguno se pierde.
  const cola = useRef(Promise.resolve())

  // Al cerrar la cámara el foco vuelve al buscador, como con cualquier diálogo.
  useEffect(() => {
    if (camara) camaraUsada.current = true
    else if (camaraUsada.current) campo.current?.focus()
  }, [camara, campo])

  // Avisa a la pantalla si quedan escaneos sin atender: no se cobra con códigos a medio buscar.
  const avisar = useRef(alCambiarPendientes)
  avisar.current = alCambiarPendientes
  useEffect(() => { avisar.current?.(pendientes) }, [pendientes])

  const olvidar = (codigo) => setDesconocidos((lista) => lista.filter((c) => c !== codigo))

  /** Desde la lista de resultados: agrega y da por atendido el código que se buscó por nombre. */
  function agregarDeResultados(producto) {
    alAgregar(producto)
    olvidar(resultadosDe)
    setResultados(null)
    setResultadosDe(null)
    campo.current?.focus()
  }

  async function procesar(codigo) {
    setAviso(null)
    setFallo(null)
    try {
      const producto = await productoPorCodigo(codigo)
      if (!producto) {
        setDesconocidos((lista) => (lista.includes(codigo) ? lista : [...lista, codigo]))
      } else if (!producto.vendible) {
        setAviso(producto.motivoNoVendible || 'Este producto no se puede vender.')
        campo.current?.focus()
      } else {
        alAgregar(producto)
        campo.current?.focus()
      }
    } catch (error) {
      setFallo(error.message || 'No se pudo buscar. Inténtalo otra vez.')
    }
  }

  /** Lee el código y deja el campo vacío al instante; la búsqueda se hace en la cola. */
  function encolar(codigo) {
    const limpio = codigo.trim()
    if (!limpio) return
    setPendientes((n) => n + 1)
    cola.current = cola.current
      .then(() => procesar(limpio))
      .finally(() => setPendientes((n) => n - 1))
  }

  async function buscarPorNombre(codigo) {
    setFallo(null)
    try {
      setResultados(await productosPorTexto(codigo))
      setResultadosDe(codigo)
    } catch (error) {
      setFallo(error.message || 'No se pudo buscar. Inténtalo otra vez.')
    }
  }

  function enviar(evento) {
    evento.preventDefault()
    const codigo = texto
    setTexto('')
    encolar(codigo)
  }

  return (
    <section className="caja-buscador" aria-label="Búsqueda de productos" aria-busy={pendientes > 0}>
      <form onSubmit={enviar} className="caja-buscador-fila">
        <div className="campo">
          <label htmlFor="caja-buscar"><span>Buscar producto o escanear código</span></label>
          <input
            id="caja-buscar" ref={campo} type="text" inputMode="search" autoComplete="off"
            enterKeyHint="search" value={texto} onChange={(e) => setTexto(e.target.value)}
          />
        </div>
        <button type="submit" className="boton">Buscar</button>
        {haySoporteDeCamara() && (
          <button type="button" className="boton secundario" onClick={() => setCamara(true)}>
            Escanear con la cámara
          </button>
        )}
      </form>

      {aviso && <Aviso tipo="aviso">{aviso}</Aviso>}
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}

      {desconocidos.map((codigo) => (
        <div className="caja-no-encontrado" key={codigo}>
          <Aviso tipo="aviso">No encontramos ese código: {codigo}.</Aviso>
          <div className="caja-acciones">
            <button
              type="button" className="boton secundario" aria-label={`Buscar por nombre el código ${codigo}`}
              onClick={() => buscarPorNombre(codigo)}
            >
              Buscar por nombre
            </button>
            <button
              type="button" className="boton secundario"
              aria-label={`Crear producto (requiere administrador) para el código ${codigo}`}
              onClick={() => { olvidar(codigo); alCrearProducto(codigo) }}
            >
              Crear producto (requiere administrador)
            </button>
            <button type="button" className="enlace" aria-label={`Descartar el código ${codigo}`} onClick={() => olvidar(codigo)}>
              Descartar
            </button>
          </div>
        </div>
      ))}

      {resultados && (
        resultados.length === 0
          ? <p className="nota">No hay productos con ese nombre.</p>
          : (
            <ul className="caja-resultados" aria-label="Resultados de la búsqueda">
              {resultados.map((p) => (
                <li key={p.id}>
                  <span>
                    <strong>{p.nombre}</strong>
                    <span className="sub-linea">{p.codigo}{p.presentacion ? ` · ${p.presentacion}` : ''} · {p.existencia} en existencia</span>
                    {!p.vendible && <span className="sub-linea">{p.motivoNoVendible}</span>}
                  </span>
                  <button
                    type="button" className="boton secundario" disabled={!p.vendible}
                    aria-label={`Agregar ${p.nombre}`} onClick={() => agregarDeResultados(p)}
                  >
                    Agregar
                  </button>
                </li>
              ))}
            </ul>
          )
      )}

      {camara && (
        <DialogoCamara
          alCerrar={() => setCamara(false)}
          alLeer={(codigo) => { setCamara(false); encolar(codigo) }}
        />
      )}
    </section>
  )
}
