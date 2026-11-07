import { useEffect, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import { recibirOrden } from '../../servicios/api/compras.js'
import Campo from '../../componentes/comun/Campo.jsx'

const ENTERO = /^\d+$/

/**
 * Registra una entrega contra una orden.
 *
 * Las cantidades son lo que llegó ahora, no el acumulado: así las pide el
 * servidor. El pendiente de cada línea, y el estado en que queda la orden,
 * los calcula el servidor; aquí solo se comparan las cantidades escritas con
 * el pendiente que él mandó, para avisar antes de enviar.
 */
export default function FormularioRecepcion({ orden, alRecibir, alRechazar }) {
  const formulario = useRef(null)
  const aviso = useRef(null)
  const tituloRevision = useRef(null)
  const tituloFormulario = useRef(null)
  // Solo al volver de la revisión el foco va al formulario; al montar no.
  const volvio = useRef(false)
  const [paso, setPaso] = useState('editar')
  const [cantidades, setCantidades] = useState({})
  const [documento, setDocumento] = useState('')
  const [observacion, setObservacion] = useState('')
  const [errores, setErrores] = useState({})
  const [errorServidor, setErrorServidor] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [intentos, setIntentos] = useState(0)

  const pendientes = orden.items.filter((l) => l.pendiente > 0)
  const llegaAhora = (l) => {
    const texto = (cantidades[l.productoId] ?? '').trim()
    return ENTERO.test(texto) ? Number(texto) : 0
  }
  const entregadas = pendientes.filter((l) => llegaAhora(l) > 0)

  useEffect(() => {
    if (intentos > 0) formulario.current?.querySelector('[aria-invalid="true"]')?.focus()
  }, [intentos])
  useEffect(() => { if (errorServidor) aviso.current?.focus() }, [errorServidor])
  // El botón que tenía el foco se desmonta al cambiar de paso: el foco se lleva al encabezado nuevo.
  useEffect(() => {
    if (paso === 'revisar') tituloRevision.current?.focus()
    else if (volvio.current) { volvio.current = false; tituloFormulario.current?.focus() }
  }, [paso])

  function revisar(evento) {
    evento.preventDefault()
    const encontrados = {}
    const porLinea = {}
    for (const l of pendientes) {
      const texto = (cantidades[l.productoId] ?? '').trim()
      if (texto === '') continue
      if (!ENTERO.test(texto)) porLinea[l.productoId] = 'Escribe un número entero, sin decimales ni signos.'
      else if (Number(texto) > l.pendiente) porLinea[l.productoId] = `Solo faltan ${l.pendiente} por recibir de este producto.`
    }
    if (Object.keys(porLinea).length === 0 && entregadas.length === 0 && pendientes.length > 0) {
      porLinea[pendientes[0].productoId] = 'Escribe lo que llegó de al menos un producto.'
    }
    if (Object.keys(porLinea).length > 0) encontrados.porLinea = porLinea
    if (documento.trim().length > 60) encontrados.documento = 'El documento no puede pasar de 60 caracteres.'
    if (observacion.trim().length > 200) encontrados.observacion = 'La observación no puede pasar de 200 caracteres.'

    setErrorServidor(null)
    setErrores(encontrados)
    if (Object.keys(encontrados).length > 0) { setIntentos((n) => n + 1); return }
    setPaso('revisar')
  }

  async function confirmar() {
    if (enviando) return
    setEnviando(true)
    setErrorServidor(null)
    try {
      const actualizada = await recibirOrden(orden.ordenId, {
        items: entregadas.map((l) => ({ productoId: l.productoId, cantidadRecibida: llegaAhora(l) })),
        observacion: observacion.trim() === '' ? null : observacion.trim(),
        documento: documento.trim() === '' ? null : documento.trim(),
      })
      setCantidades({})
      setDocumento('')
      setObservacion('')
      setPaso('editar')
      alRecibir(actualizada)
    } catch (error) {
      setPaso('editar')
      if (error.estado === 400 || error.estado === 409) alRechazar?.()
      setErrorServidor(
        error.estado === 404
          ? 'Esa orden ya no existe. Vuelve a la lista de órdenes de compra.'
          : error.message || 'No se pudo registrar la recepción. Inténtalo otra vez.',
      )
    } finally {
      setEnviando(false)
    }
  }

  if (paso === 'revisar') {
    return (
      <section className="recepcion tarjeta" aria-labelledby="titulo-confirmar">
        <h2 id="titulo-confirmar" ref={tituloRevision} tabIndex={-1} className="subtitulo-seccion">Confirma la recepción</h2>
        <ul className="lista-recepcion">
          {entregadas.map((l) => {
            const n = llegaAhora(l)
            return <li key={l.productoId}>{l.producto}: {n === 1 ? 'entra 1 unidad' : `entran ${n} unidades`}</li>
          })}
        </ul>
        {documento.trim() && <p>Documento: {documento.trim()}</p>}
        <p>Al confirmar, la existencia de cada producto sube en la cantidad que ves arriba.</p>
        <p>El costo de los productos no cambia con la recepción. Para cambiar costos usa «Importar precios».</p>
        <p>Esto no se puede deshacer aquí. Si te equivocas, corrígelo con un ajuste de inventario.</p>
        <div className="acciones-pie">
          <button type="button" className="boton secundario" onClick={() => { volvio.current = true; setPaso('editar') }} disabled={enviando}>
            Volver a editar
          </button>
          <button type="button" className="boton" onClick={confirmar} disabled={enviando}>
            {enviando ? 'Registrando…' : 'Confirmar recepción'}
          </button>
        </div>
      </section>
    )
  }

  return (
    <form ref={formulario} className="recepcion tarjeta" onSubmit={revisar} noValidate>
      <h2 ref={tituloFormulario} tabIndex={-1} className="subtitulo-seccion">Registrar una recepción</h2>
      <p className="leyenda">
        Escribe solo lo que llegó. Lo que falte seguirá pendiente para otra entrega.
      </p>
      {errorServidor && <div ref={aviso} tabIndex={-1}><Aviso tipo="peligro">{errorServidor}</Aviso></div>}
      {pendientes.map((l) => (
        <Campo
          key={l.productoId} etiqueta={`Llegan ahora: ${l.producto}`}
          motivo={errores.porLinea?.[l.productoId]} ayuda={`Pendiente: ${l.pendiente}`}
        >
          <input
            type="text" inputMode="numeric" value={cantidades[l.productoId] ?? ''}
            onChange={(e) => setCantidades((c) => ({ ...c, [l.productoId]: e.target.value }))}
          />
        </Campo>
      ))}
      <button
        type="button" className="boton secundario"
        onClick={() => setCantidades(Object.fromEntries(pendientes.map((l) => [l.productoId, String(l.pendiente)])))}
      >
        Recibir todo lo pendiente
      </button>
      <div className="rejilla recepcion-datos">
        <Campo etiqueta="Documento del proveedor (opcional)" motivo={errores.documento} ayuda="Remisión o factura.">
          <input type="text" value={documento} onChange={(e) => setDocumento(e.target.value)} />
        </Campo>
        <Campo etiqueta="Observación (opcional)" motivo={errores.observacion}>
          <input type="text" value={observacion} onChange={(e) => setObservacion(e.target.value)} />
        </Campo>
      </div>
      <div className="acciones-pie">
        <button type="submit" className="boton">Revisar recepción</button>
      </div>
    </form>
  )
}
