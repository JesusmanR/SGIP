import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { ErrorApi } from '../../servicios/api.js'
import {
  CLAVE_EN_USO, mediosDePago, registrarDevolucion, saldoDeCliente, ventaPorConsecutivo,
} from '../../servicios/api/caja.js'
import { PESOS } from '../../servicios/formato.js'
import { esAdministrador } from '../../servicios/sesion.js'
import '../../estilos/caja.css'
import DialogoPin from './DialogoPin.jsx'
import {
  cantidadAdevolver, restantes, unidadesParaApi, valorDeUnidades,
} from './devolucion.js'
import SeccionCaja from './SeccionCaja.jsx'
import useEnLinea from './useEnLinea.js'
import { nuevaClaveDeVenta } from './venta.js'

const ENTERO = /^\d{1,12}$/
const MOTIVO_MAXIMO = 200 // el límite del servidor

/** Lo escrito como pesos enteros: vacío es 0, y algo que no vale es NaN. */
function pesosDe(texto) {
  const t = String(texto ?? '').trim()
  if (t === '') return 0
  return ENTERO.test(t) ? Number(t) : Number.NaN
}

const necesitaReferencia = (tipo) => tipo === 'TARJETA' || tipo === 'TRANSFERENCIA'
const textoReferencia = (tipo) => (tipo === 'TARJETA' ? 'N.º de aprobación' : 'Referencia')

/** Los medios distintos, de tarjeta o transferencia, con que se pagó la venta: se devuelve por el mismo. */
function mediosDeLaVenta(venta) {
  const vistos = new Map()
  for (const p of venta.pagos ?? []) {
    if (necesitaReferencia(p.tipo) && !vistos.has(p.medioId)) vistos.set(p.medioId, p)
  }
  return [...vistos.values()]
}

/**
 * Devolver o anular una venta. Se busca por su consecutivo, se eligen las unidades, se escribe el
 * motivo y se reparte el dinero que se devuelve: efectivo, el mismo medio o saldo a favor. Un
 * administrador debe autorizarla (con su PIN, si quien la prepara es un vendedor).
 */
export default function Devolucion() {
  const admin = esAdministrador()
  const enLinea = useEnLinea()
  const medios = useDatos(() => mediosDePago())

  const [consecutivo, setConsecutivo] = useState('')
  const [buscando, setBuscando] = useState(false)
  const [noEncontrada, setNoEncontrada] = useState(false)
  const [venta, setVenta] = useState(null)
  const [cantidades, setCantidades] = useState({})
  const [motivo, setMotivo] = useState('')
  const [dinero, setDinero] = useState({}) // efectivo | saldo | medio-<id> → pesos escritos
  const [referencias, setReferencias] = useState({}) // medioId → referencia
  const [saldoInfo, setSaldoInfo] = useState({ medioId: null })
  const [errores, setErrores] = useState({})
  const [fallo, setFallo] = useState(null)
  const [dialogo, setDialogo] = useState(false)
  const [enviando, setEnviando] = useState(false)
  const [hecho, setHecho] = useState(null)
  const saldoAuto = useRef('')
  // Las claves de idempotencia de esta devolución, una por cuerpo exacto (la firma, sin el PIN). El mismo
  // cuerpo reutiliza su clave (red caída, resultado dudoso, PIN rechazado), también si en medio se mandó otro:
  // A, B, A usa dos claves y no tres, porque el servidor pudo haber registrado A la primera vez. Si el cajero
  // cambia algo, el cuerpo cambia y la clave es otra. Se vacía al registrar, al buscar otra venta y en
  // «Otra devolución»; un 409 de clave en uso suelta solo la de ese cuerpo.
  const clavesPorCuerpo = useRef(new Map())

  const pendiente = venta?.estadoPago === 'PENDIENTE'
  const clienteIdentificado = Boolean(venta?.cliente && !venta.cliente.consumidorFinal)

  // El medio de sistema del saldo a favor lo da el saldo del cliente.
  useEffect(() => {
    setSaldoInfo({ medioId: null })
    if (!venta || pendiente || !clienteIdentificado) return undefined
    let vigente = true
    saldoDeCliente(venta.cliente.id)
      .then((r) => { if (vigente) setSaldoInfo({ medioId: r?.medioId ?? null }) })
      .catch(() => {})
    return () => { vigente = false }
  }, [venta, pendiente, clienteIdentificado])

  const lineas = venta?.lineas ?? []
  const valores = useMemo(() => lineas.map((l) => {
    const k = cantidadAdevolver(l, cantidades[l.productoId])
    return { linea: l, k, valor: Number.isNaN(k) ? 0 : valorDeUnidades(l, k) }
  }), [lineas, cantidades])
  const cantidadInvalida = valores.some((v) => Number.isNaN(v.k))
  const valorTotal = valores.reduce((a, v) => a + v.valor, 0)

  const mismos = venta && !pendiente ? mediosDeLaVenta(venta) : []
  const efectivo = (medios.datos ?? []).find((m) => m.tipo === 'EFECTIVO' && m.activo !== false)
  const claves = ['efectivo', 'saldo', ...mismos.map((m) => `medio-${m.medioId}`)]
  const montos = claves.map((c) => pesosDe(dinero[c]))
  const montoInvalido = montos.some(Number.isNaN)
  const repartido = montoInvalido ? 0 : montos.reduce((a, n) => a + n, 0)
  const diferencia = valorTotal - repartido

  // Lo que se pagó con saldo a favor vuelve SOLO como saldo a favor: nunca se vuelve efectivo ni tarjeta.
  // Lo que se puede devolver por efectivo o medios es lo pagado sin saldo, menos lo ya reembolsado así: el
  // servidor lo dice en `reembolsadoSinSaldo`; si no viene, se estima suponiendo que las devoluciones
  // anteriores gastaron primero eso. Si algún pago no trae su monto no se puede saber y no se limita aquí:
  // el servidor tiene la última palabra.
  const pagos = venta?.pagos ?? []
  const sabePagos = pagos.every((p) => Number.isFinite(Number(p.monto)))
  const pagadoConSaldo = pagos.filter((p) => p.tipo === 'SALDO_A_FAVOR').reduce((a, p) => a + Number(p.monto), 0)
  const pagadoSinSaldo = pagos.filter((p) => p.tipo !== 'SALDO_A_FAVOR').reduce((a, p) => a + Number(p.monto), 0)
  const yaDevuelto = lineas.reduce((a, l) => a + Number(l.valorDevuelto ?? 0), 0)
  const reembolsadoSinSaldo = venta?.reembolsadoSinSaldo
  const yaSinSaldo = reembolsadoSinSaldo != null && Number.isFinite(Number(reembolsadoSinSaldo))
    ? Number(reembolsadoSinSaldo)
    : Math.min(yaDevuelto, pagadoSinSaldo)
  const capacidad = sabePagos && !pendiente
    ? Math.max(0, pagadoSinSaldo - yaSinSaldo)
    : Number.POSITIVE_INFINITY
  const saldoMinimo = Number.isFinite(capacidad) ? Math.max(0, valorTotal - capacidad) : 0
  const sinSaldo = montoInvalido ? 0 : montos.reduce((a, n, i) => (claves[i] === 'saldo' ? a : a + n), 0)
  const pasaDeLoPagadoSinSaldo = sinSaldo > capacidad

  // La parte que debe volver como saldo se deja escrita; si el cajero la cambió a mano, no se le pisa.
  useEffect(() => {
    const nuevo = saldoMinimo > 0 ? String(saldoMinimo) : ''
    // El valor anterior se copia antes: la función de `setDinero` corre después de esta línea.
    const anterior = saldoAuto.current
    saldoAuto.current = nuevo
    setDinero((d) => ((d.saldo ?? '') === anterior ? { ...d, saldo: nuevo } : d))
  }, [saldoMinimo])

  const puede = Boolean(venta) && venta.estado !== 'ANULADA' && valorTotal > 0 && !cantidadInvalida && !enviando && enLinea
    && (pendiente || (!montoInvalido && diferencia === 0 && !pasaDeLoPagadoSinSaldo))

  async function buscar(evento) {
    evento.preventDefault()
    const texto = consecutivo.trim()
    if (!texto) return
    setBuscando(true)
    setNoEncontrada(false)
    setFallo(null)
    try {
      const encontrada = await ventaPorConsecutivo(texto)
      setVenta(encontrada)
      setNoEncontrada(!encontrada)
      setCantidades({})
      setMotivo('')
      setDinero({})
      saldoAuto.current = ''
      clavesPorCuerpo.current.clear()
      setReferencias({})
      setErrores({})
    } catch (error) {
      setVenta(null)
      setFallo(error.message || 'No se pudo buscar la venta.')
    } finally {
      setBuscando(false)
    }
  }

  function anularTodo() {
    setCantidades(Object.fromEntries(lineas.map((l) => [l.productoId, String(restantes(l))])))
  }

  function armarReembolsos() {
    if (pendiente) return []
    const lista = []
    if (pesosDe(dinero.efectivo) > 0) lista.push({ medioId: efectivo.id, monto: pesosDe(dinero.efectivo) })
    for (const m of mismos) {
      const monto = pesosDe(dinero[`medio-${m.medioId}`])
      if (monto > 0) lista.push({ medioId: m.medioId, monto, referencia: referencias[m.medioId].trim() })
    }
    if (pesosDe(dinero.saldo) > 0) lista.push({ medioId: saldoInfo.medioId, monto: pesosDe(dinero.saldo) })
    return lista
  }

  /** Lo que falta antes de enviar; null si todo está. */
  function revisar() {
    const nuevos = {}
    if (motivo.trim() === '') nuevos.motivo = 'Escribe el motivo de la devolución.'
    if (!pendiente) {
      if (pesosDe(dinero.efectivo) > 0 && !efectivo) nuevos.efectivo = 'No hay un medio de efectivo activo.'
      for (const m of mismos) {
        if (pesosDe(dinero[`medio-${m.medioId}`]) > 0 && !(referencias[m.medioId] ?? '').trim()) {
          nuevos[`ref-${m.medioId}`] = m.tipo === 'TARJETA' ? 'Escribe el número de aprobación.' : 'Escribe la referencia.'
        }
      }
      if (pesosDe(dinero.saldo) > 0 && !saldoInfo.medioId) {
        nuevos.saldo = 'No se pudo consultar el saldo a favor del cliente. Inténtalo otra vez.'
      }
    }
    setErrores(nuevos)
    return Object.keys(nuevos).length === 0
  }

  function confirmar() {
    setFallo(null)
    if (!revisar()) return
    if (admin) enviar(undefined)
    else setDialogo(true)
  }

  /** Devuelve el texto del error, o null si quedó registrada (así lo espera `DialogoPin`). */
  async function enviar(autorizacion) {
    setEnviando(true)
    setFallo(null)
    const cuerpo = {
      ventaId: venta.id,
      unidades: unidadesParaApi(lineas, cantidades),
      motivo: motivo.trim(),
      reembolsos: armarReembolsos(),
    }
    const firma = JSON.stringify(cuerpo)
    if (!clavesPorCuerpo.current.has(firma)) clavesPorCuerpo.current.set(firma, nuevaClaveDeVenta())
    const solicitud = { clave: clavesPorCuerpo.current.get(firma), ...cuerpo }
    if (autorizacion) solicitud.autorizacion = autorizacion
    try {
      // Un 200 con lo ya guardado (la misma clave y el mismo cuerpo) llega aquí igual que un 201.
      setHecho(await registrarDevolucion(solicitud))
      clavesPorCuerpo.current.clear()
      setDialogo(false)
      return null
    } catch (error) {
      setEnviando(false)
      let mensaje = error.message || 'No se pudo registrar la devolución.'
      if (error instanceof ErrorApi && error.estado === 409 && error.tipo === CLAVE_EN_USO) {
        clavesPorCuerpo.current.delete(firma)
        mensaje = 'Esta devolución ya se registró con otros datos. Revisa las devoluciones de la venta.'
      } else if (!(error instanceof ErrorApi) || error.estado >= 500 || error.estado === 408) {
        mensaje = 'No sabemos si se registró la devolución. Vuelve a confirmar: no se devolverá dos veces.'
      }
      if (!autorizacion) setFallo(mensaje)
      return mensaje
    }
  }

  function otra() {
    setHecho(null)
    setVenta(null)
    clavesPorCuerpo.current.clear()
    setConsecutivo('')
    setNoEncontrada(false)
    setEnviando(false)
    setDialogo(false)
  }

  const mensajeBoton = !enLinea
    ? 'Sin conexión: no se puede devolver.'
    : valorTotal <= 0 && venta ? 'Elige cuántas unidades se devuelven.' : ''

  return (
    <div className="contenido seccion-caja">
      <header className="caja-cabecera caja-cabecera-fila">
        <div>
          <p className="caja-eyebrow">Caja</p>
          <h1>Devolución</h1>
          <p className="caja-subtitulo">Devolver unidades de una venta o anularla completa.</p>
        </div>
        <div className="caja-cabecera-acciones">
          <Link to="/caja" className="boton secundario enlace-boton">Volver a la caja</Link>
        </div>
      </header>

      {hecho ? (
        <section className="caja-seccion caja-cerrado" aria-labelledby="devolucion-hecha">
          <h2 id="devolucion-hecha" className="caja-seccion-titulo">Devolución registrada</h2>
          <p className="nota">
            La devolución {hecho.consecutivo} por {PESOS.format(hecho.valorDevuelto)} quedó registrada.
            {Number(hecho.reembolsado) > 0 && ` Se devolvieron ${PESOS.format(hecho.reembolsado)} al cliente.`}
            {Number(hecho.saldoPorCobrar) > 0 && ` A esta venta le quedan ${PESOS.format(hecho.saldoPorCobrar)} por cobrar.`}
          </p>
          <button type="button" className="boton" onClick={otra}>Otra devolución</button>
        </section>
      ) : (
        <div className="caja-rejilla">
          <div className="caja-columna-principal caja-secciones">
            <SeccionCaja numero="01" titulo="Venta" descripcion="Escribe el consecutivo que aparece en el recibo.">
              <form className="caja-buscador-fila" onSubmit={buscar} noValidate>
                <Campo etiqueta="Consecutivo de la venta">
                  <input type="text" autoComplete="off" value={consecutivo} onChange={(e) => setConsecutivo(e.target.value)} />
                </Campo>
                <button type="submit" className="boton secundario" disabled={buscando || consecutivo.trim() === ''}>Buscar venta</button>
              </form>
              {noEncontrada && <Aviso tipo="aviso">No hay una venta con ese consecutivo.</Aviso>}
              {fallo && !venta && <Aviso tipo="peligro">{fallo}</Aviso>}
              {venta && (
                <p className="caja-cliente-actual">
                  <strong>{venta.consecutivo}</strong>
                  <span>{venta.cliente?.nombre ?? 'Consumidor final'} · total {PESOS.format(venta.total)}</span>
                </p>
              )}
              {venta?.estado === 'ANULADA' && <Aviso tipo="aviso">Esta venta ya está anulada.</Aviso>}
            </SeccionCaja>

            {venta && venta.estado !== 'ANULADA' && (
              <>
                <SeccionCaja numero="02" titulo="Productos a devolver" descripcion="Escribe cuántas unidades vuelven de cada producto.">
                  <div className="tabla-tarjetas-caja">
                    <table className="tabla-tarjetas" aria-label="Productos de la venta">
                      <thead>
                        <tr>
                          <th scope="col">Producto</th>
                          <th scope="col" className="numerica">Vendidas</th>
                          <th scope="col" className="numerica">Ya devueltas</th>
                          <th scope="col" className="numerica">A devolver</th>
                          <th scope="col" className="numerica">Valor</th>
                        </tr>
                      </thead>
                      <tbody>
                        {valores.map(({ linea, k, valor }) => (
                          <tr key={linea.productoId}>
                            <th scope="row" data-etiqueta="Producto">{linea.nombre}</th>
                            <td className="numerica" data-etiqueta="Vendidas">{linea.cantidad}</td>
                            <td className="numerica" data-etiqueta="Ya devueltas">{linea.cantidadDevuelta ?? 0}</td>
                            <td className="numerica" data-etiqueta="A devolver">
                              <input
                                type="text" inputMode="numeric" autoComplete="off" className="caja-entrada caja-cantidad"
                                aria-label={`Cantidad a devolver de ${linea.nombre}`} aria-invalid={Number.isNaN(k) || undefined}
                                value={cantidades[linea.productoId] ?? ''}
                                onChange={(e) => setCantidades({ ...cantidades, [linea.productoId]: e.target.value })}
                              />
                              {Number.isNaN(k) && (
                                <small className="campo-mensaje error">
                                  {restantes(linea) > 0
                                    ? `Solo se pueden devolver ${restantes(linea)}.`
                                    : 'Ya se devolvió todo de este producto.'}
                                </small>
                              )}
                            </td>
                            <td className="numerica cifra" data-etiqueta="Valor">{PESOS.format(valor)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className="caja-acciones">
                    <button type="button" className="boton secundario" onClick={anularTodo}>Anular toda la venta</button>
                  </div>
                </SeccionCaja>

                <SeccionCaja numero="03" titulo="Motivo" descripcion="Queda guardado junto a la devolución.">
                  <Campo etiqueta="Motivo de la devolución" motivo={errores.motivo}>
                    <textarea rows={3} maxLength={MOTIVO_MAXIMO} value={motivo} onChange={(e) => setMotivo(e.target.value)} />
                  </Campo>
                </SeccionCaja>

                <SeccionCaja
                  numero="04" titulo="Devolución del dinero"
                  descripcion={pendiente ? 'No hay dinero que repartir.' : 'Reparte lo que se devuelve entre las formas de abajo.'}
                >
                  {pendiente ? (
                    <p className="nota">Esta venta todavía no se cobró, así que no hay dinero que devolver.</p>
                  ) : (
                    <div className="caja-reembolso">
                      {saldoMinimo > 0 && (
                        <p className="nota">
                          Se pagaron {PESOS.format(pagadoConSaldo)} con saldo a favor: esa parte solo puede volver como saldo a favor.
                        </p>
                      )}
                      <Campo etiqueta="Efectivo" motivo={errores.efectivo} ayuda={efectivo ? 'Sale del cajón.' : undefined}>
                        <input
                          type="text" inputMode="numeric" autoComplete="off" disabled={!efectivo}
                          value={dinero.efectivo ?? ''} onChange={(e) => setDinero({ ...dinero, efectivo: e.target.value })}
                        />
                      </Campo>
                      {mismos.map((m) => {
                        const clave = `medio-${m.medioId}`
                        const hay = pesosDe(dinero[clave]) > 0
                        return (
                          <div key={clave} className="caja-reembolso-medio">
                            <Campo etiqueta={`Por ${m.medio}`} ayuda="El mismo medio con el que pagó.">
                              <input
                                type="text" inputMode="numeric" autoComplete="off"
                                value={dinero[clave] ?? ''} onChange={(e) => setDinero({ ...dinero, [clave]: e.target.value })}
                              />
                            </Campo>
                            {hay && (
                              <Campo etiqueta={textoReferencia(m.tipo)} motivo={errores[`ref-${m.medioId}`]} ayuda="Del comprobante de la devolución.">
                                <input
                                  type="text" autoComplete="off" maxLength={60} value={referencias[m.medioId] ?? ''}
                                  onChange={(e) => setReferencias({ ...referencias, [m.medioId]: e.target.value })}
                                />
                              </Campo>
                            )}
                          </div>
                        )
                      })}
                      <Campo
                        etiqueta="Saldo a favor" motivo={errores.saldo}
                        ayuda={clienteIdentificado
                          ? 'Se abona al saldo del cliente para su próxima compra.'
                          : 'Necesita un cliente identificado.'}
                      >
                        <input
                          type="text" inputMode="numeric" autoComplete="off" disabled={!clienteIdentificado}
                          value={dinero.saldo ?? ''} onChange={(e) => setDinero({ ...dinero, saldo: e.target.value })}
                        />
                      </Campo>
                    </div>
                  )}
                </SeccionCaja>
              </>
            )}
          </div>

          {venta && venta.estado !== 'ANULADA' && (
            <div className="caja-columna-resumen">
              <section className="caja-cobro caja-resumen" aria-labelledby="resumen-devolucion">
                <p className="caja-eyebrow" aria-hidden="true">Resumen</p>
                <h2 id="resumen-devolucion" className="caja-resumen-titulo">Resumen de la devolución</h2>
                <div className="caja-total">
                  <p className="caja-total-rotulo">Valor a devolver</p>
                  <p className="caja-total-valor cifra" data-testid="valor-a-devolver">{PESOS.format(valorTotal)}</p>
                </div>
                {!pendiente && valorTotal > 0 && (
                  <div role="status" className="caja-reparto">
                    {montoInvalido && <p className="caja-error-texto">Escribe los montos en pesos enteros.</p>}
                    {!montoInvalido && diferencia > 0 && <p className="caja-falta-pagar">Falta repartir {PESOS.format(diferencia)}</p>}
                    {!montoInvalido && diferencia < 0 && <p className="caja-falta-pagar">Te pasas por {PESOS.format(-diferencia)}</p>}
                    {!montoInvalido && pasaDeLoPagadoSinSaldo && (
                      <p className="caja-error-texto">
                        Lo que se pagó con saldo a favor vuelve como saldo: por efectivo o por tarjeta solo se pueden devolver {PESOS.format(capacidad)}.
                      </p>
                    )}
                    {!montoInvalido && diferencia === 0 && !pasaDeLoPagadoSinSaldo && <p className="caja-cambio">Todo repartido</p>}
                  </div>
                )}
                {fallo && venta && <Aviso tipo="peligro">{fallo}</Aviso>}
                <div className="caja-cobrar-bloque">
                  <button type="button" className="boton caja-cobrar" disabled={!puede} onClick={confirmar}>
                    {enviando ? 'Devolviendo…' : 'Confirmar devolución'}
                  </button>
                  <p className="caja-total-mensaje" role="status">{mensajeBoton}</p>
                </div>
              </section>
            </div>
          )}
        </div>
      )}

      {dialogo && (
        <DialogoPin
          motivos={[`Devolución de la venta ${venta.consecutivo}`]}
          descripcion="Un administrador debe autorizar esta devolución con su PIN. Se usa una sola vez."
          textoAccion="Autorizar y devolver" textoEnviando="Devolviendo…"
          alConfirmar={enviar} alCerrar={() => setDialogo(false)}
        />
      )}
    </div>
  )
}
