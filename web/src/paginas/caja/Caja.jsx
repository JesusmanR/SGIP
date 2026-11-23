import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import AvisoConexion from '../../componentes/comun/AvisoConexion.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import Confirmacion from '../../componentes/comun/Confirmacion.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import {
  abrirTurno, datosDeLaEmpresa, mediosDePago, saldoDeCliente, turnoActual,
} from '../../servicios/api/caja.js'
import { PESOS } from '../../servicios/formato.js'
import { esAdministrador, leerSesion } from '../../servicios/sesion.js'
import '../../estilos/caja.css'
import BuscadorProducto from './BuscadorProducto.jsx'
import { calcularVistaPrevia } from './calculo.js'
import Carrito from './Carrito.jsx'
import Cobro from './Cobro.jsx'
import DatosCliente from './DatosCliente.jsx'
import DialogoPin from './DialogoPin.jsx'
import ReciboImprimible from './ReciboImprimible.jsx'
import SeccionCaja from './SeccionCaja.jsx'
import useEnLinea from './useEnLinea.js'
import { imprimirRecibo } from './imprimir.js'
import useVentaEnCurso, { MENSAJE_AMBIGUO } from './useVentaEnCurso.js'
import {
  agregarProducto, armarRecibo, armarSolicitud, bloqueoDeVenta, descuentoDe,
  lineasParaVista, motivosDeAutorizacion, puedeCobrar,
} from './venta.js'

const SIN_DESCUENTO = { tipo: 'PORCENTAJE', valor: '' }
const BASE = /^\d{1,12}$/

/** Sin turno abierto no se vende: el cajero cuenta lo que hay en el cajón y lo abre. */
function AbrirTurno({ alAbrir }) {
  const [base, setBase] = useState('')
  const [motivo, setMotivo] = useState(null)
  const [fallo, setFallo] = useState(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(evento) {
    evento.preventDefault()
    setFallo(null)
    if (!BASE.test(base.trim())) {
      setMotivo('Escribe la base de efectivo en pesos enteros; si no hay, escribe 0.')
      return
    }
    setMotivo(null)
    setEnviando(true)
    try {
      alAbrir(await abrirTurno(Number(base.trim())))
    } catch (error) {
      setFallo(error.message || 'No se pudo abrir el turno.')
      setEnviando(false)
    }
  }

  return (
    <section className="caja-abrir tarjeta" aria-label="Abrir turno">
      <h2>Abrir turno</h2>
      <p className="nota">Cuenta el efectivo que hay en el cajón. Sin un turno abierto no se puede vender.</p>
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
      <form onSubmit={enviar} noValidate>
        <Campo etiqueta="Base de efectivo" motivo={motivo ?? undefined}>
          <input type="text" inputMode="numeric" autoComplete="off" value={base} onChange={(e) => setBase(e.target.value)} />
        </Campo>
        <button type="submit" className="boton" disabled={enviando}>Abrir turno</button>
      </form>
    </section>
  )
}

/**
 * La venta en curso. Su estado vive aquí, en memoria. Lo único que se guarda en sessionStorage es una
 * venta que quedó sin saber si se registró (clave y cuerpo exacto, nunca el PIN): ver `useVentaEnCurso`.
 */
function Venta({ turno }) {
  const sesion = leerSesion()
  const enLinea = useEnLinea()
  const empresa = useDatos(() => datosDeLaEmpresa())
  const medios = useDatos(() => mediosDePago())
  // La clave de idempotencia, el cuerpo enviado y el resultado ambiguo viven en el hook.
  const envio = useVentaEnCurso()

  const [lineas, setLineas] = useState([])
  const [cliente, setCliente] = useState(null)
  const [domicilio, setDomicilio] = useState(null)
  const [descuentoTotal, setDescuentoTotal] = useState(SIN_DESCUENTO)
  const [motivoEncargo, setMotivoEncargo] = useState('')
  const [pagos, setPagos] = useState([])
  const [saldo, setSaldo] = useState(0)
  const [medioSaldoId, setMedioSaldoId] = useState(null)
  // Una venta restaurada tras recargar: ya no hay carrito, solo se puede reintentar su cuerpo guardado o abandonarla.
  const [registrada, setRegistrada] = useState(null)
  const [dialogo, setDialogo] = useState(null) // 'pin' | null
  const [mensaje, setMensaje] = useState(null)
  const [abandonando, setAbandonando] = useState(false)
  const [recibo, setRecibo] = useState(null)
  const [impreso, setImpreso] = useState(false)
  // Cada venta nueva estrena el formulario del cliente: no queda nada del anterior.
  const [epoca, setEpoca] = useState(0)
  // Cuántos escaneos siguen buscándose: mientras haya, no se cobra (podrían entrar a la venta).
  const [escaneando, setEscaneando] = useState(false)
  const buscador = useRef(null)
  const tituloRecibo = useRef(null)

  // El foco vuelve al buscador al cargar, al cerrar un diálogo y al empezar otra venta.
  useEffect(() => {
    if (!dialogo && !recibo) buscador.current?.focus()
  }, [dialogo, recibo])

  // Tras registrar, el foco va al encabezado del recibo: así se anuncia «Venta registrada».
  useEffect(() => {
    if (recibo) tituloRecibo.current?.focus()
  }, [recibo])

  useEffect(() => {
    if (!cliente) {
      setSaldo(0)
      setMedioSaldoId(null)
      return undefined
    }
    let vigente = true
    saldoDeCliente(cliente.id)
      .then((r) => {
        if (!vigente) return
        setSaldo(Number(r?.saldo) || 0)
        setMedioSaldoId(r?.medioId ?? null)
      })
      .catch(() => { if (vigente) setSaldo(0) })
    return () => { vigente = false }
  }, [cliente])

  const vista = useMemo(
    () => calcularVistaPrevia(lineasParaVista(lineas), descuentoDe(descuentoTotal)),
    [lineas, descuentoTotal],
  )
  const bloqueo = bloqueoDeVenta({ lineas, vista, cliente, domicilio, motivoEncargo })
  // Sin carrito y con una venta ambigua solo puede haber una restaurada: sus motivos vienen de lo guardado.
  const restaurada = envio.restaurada && lineas.length === 0 && envio.estado === 'ambiguo'
  const motivos = restaurada ? envio.motivos : motivosDeAutorizacion(lineas, descuentoTotal)
  const necesitaPin = motivos.length > 0 && !esAdministrador(sesion)
  const contraEntrega = Boolean(domicilio?.contraEntrega)
  // Con un resultado ambiguo o una clave en uso no se edita nada: solo se reintenta o se abandona.
  const congelado = envio.enviando || envio.estado !== 'libre'
  // Los manejadores que llegan de una cola (escaneos) leen esto al ejecutarse, no al crearse.
  const congeladoAhora = useRef(false)
  congeladoAhora.current = congelado
  const enviandoAhora = useRef(false)
  enviandoAhora.current = envio.enviando
  const sinConexion = !enLinea

  function cambiarLinea(claveLinea, cambios) {
    if (congeladoAhora.current) return
    setLineas((actuales) => actuales.map((l) => (l.clave === claveLinea ? { ...l, ...cambios } : l)))
    setPagos([])
  }

  function quitarLinea(claveLinea) {
    if (congeladoAhora.current) return
    const quedan = lineas.filter((l) => l.clave !== claveLinea)
    setLineas(quedan)
    setPagos([])
    // Un carrito vacío es otra venta: la clave se renueva.
    if (quedan.length === 0) vaciar()
  }

  function vaciar() {
    // Con un envío en vuelo no se renueva la clave ni se suelta el cuerpo guardado.
    if (enviandoAhora.current) return
    setLineas([])
    setCliente(null)
    setDomicilio(null)
    setDescuentoTotal(SIN_DESCUENTO)
    setMotivoEncargo('')
    setPagos([])
    setMensaje(null)
    setAbandonando(false)
    setDialogo(null)
    setEpoca((n) => n + 1)
    envio.abandonar()
  }

  function cambiarCliente(nuevo) {
    if (congeladoAhora.current) return
    setCliente(nuevo)
    // El saldo a favor es de un cliente: con otro, esos pagos ya no valen.
    setPagos((actuales) => actuales.filter((p) => p.tipo !== 'SALDO_A_FAVOR'))
  }

  function cambiarDomicilio(nuevo) {
    if (congeladoAhora.current) return
    setDomicilio(nuevo)
  }

  function cambiarMotivo(texto) {
    if (congeladoAhora.current) return
    setMotivoEncargo(texto)
  }

  function agregarDelCatalogo(producto) {
    if (congeladoAhora.current) return
    setLineas((actuales) => agregarProducto(actuales, producto))
    setPagos([])
  }

  /** Envía la venta. Devuelve el texto del error, o null si quedó registrada. */
  async function enviar(autorizacion) {
    setMensaje(null)
    const eraRestaurada = restaurada
    const resultado = await envio.enviar((clave) => armarSolicitud({
      claveIdempotencia: clave, cliente, lineas, descuentoTotal, pagos, motivoEncargo, domicilio,
    }), autorizacion, motivos)
    if (resultado.venta && eraRestaurada) {
      // No hay carrito del que armar el recibo: se confirma la venta y su recibo se consulta aparte.
      setRegistrada({ consecutivo: resultado.venta.consecutivo, total: Number(resultado.venta.total) })
      setDialogo(null)
      setAbandonando(false)
      return null
    }
    if (resultado.venta) {
      setRecibo(armarRecibo({
        empresa: empresa.datos ?? { razonSocial: '' }, venta: resultado.venta, lineas, pagos, cliente,
        cajero: sesion?.nombre ?? turno.cajero, domicilio,
      }))
      setDialogo(null)
      setLineas([])
      setCliente(null)
      setDomicilio(null)
      setDescuentoTotal(SIN_DESCUENTO)
      setMotivoEncargo('')
      setPagos([])
      setAbandonando(false)
      setEpoca((n) => n + 1)
      return null
    }
    if (resultado.ignorado) return { ignorado: true }
    // Una venta restaurada que el servidor rechazó por otra causa que el PIN ya no se puede reintentar: se
    // cierra el diálogo y se dice por qué, en lugar de pedir el PIN una y otra vez.
    if (resultado.descartada) setDialogo(null)
    // Un error definitivo o una clave en uso no son un resultado ambiguo: la confirmación de
    // abandonar ya no corresponde y no debe reaparecer sola ante un ambiguo posterior.
    if (resultado.error !== MENSAJE_AMBIGUO) setAbandonando(false)
    if (resultado.error) setMensaje(resultado.error)
    return resultado.error ?? null
  }

  function iniciarCobro() {
    if (necesitaPin) setDialogo('pin')
    else enviar(undefined)
  }

  // «Reintentar» respeta lo mismo que «Cobrar»: bloqueos, pagos que cuadran y conexión.
  const puedeReintentar = restaurada
    ? !sinConexion && !envio.enviando
    : puedeCobrar({ bloqueo, vista, pagos, contraEntrega, sinConexion, enviando: envio.enviando || escaneando })

  if (registrada) {
    return (
      <div className="caja-recibo-pantalla">
        <div className="caja-recibo-acciones">
          <h2 ref={tituloRecibo} tabIndex={-1}>Venta registrada</h2>
          <p className="nota">
            La venta {registrada.consecutivo} por {PESOS.format(registrada.total)} quedó registrada. Como la página se
            recargó, el recibo se consulta en las ventas del turno.
          </p>
          <button type="button" className="boton" onClick={() => setRegistrada(null)}>Nueva venta</button>
        </div>
      </div>
    )
  }

  if (recibo) {
    return (
      <div className="caja-recibo-pantalla">
        <div className="caja-recibo-acciones">
          <h2 ref={tituloRecibo} tabIndex={-1}>Venta registrada</h2>
          <div className="caja-acciones">
            <button type="button" className="boton" onClick={imprimirRecibo}>Imprimir</button>
            <button type="button" className="boton secundario" onClick={() => { setImpreso(true); imprimirRecibo() }}>
              Descargar PDF
            </button>
            <button type="button" className="boton secundario" onClick={() => { setRecibo(null); setImpreso(false) }}>
              Nueva venta
            </button>
          </div>
          {impreso && <p className="nota">En el cuadro de impresión elige «Guardar como PDF» como destino.</p>}
        </div>
        <ReciboImprimible recibo={recibo} />
      </div>
    )
  }

  return (
    <>
      <div className="caja-rejilla">
        <div className="caja-columna-principal">
          {/* Un fieldset deshabilitado apaga todo lo de dentro mientras la venta está congelada. */}
          <fieldset className="caja-congelable caja-secciones" disabled={congelado}>
            <DatosCliente
              key={epoca} cliente={cliente} alCambiarCliente={cambiarCliente}
              domicilio={domicilio} alCambiarDomicilio={cambiarDomicilio}
            />
            <SeccionCaja numero="02" titulo="Productos" descripcion="Escanea o busca y agrega los artículos de esta venta.">
              <BuscadorProducto
                entradaRef={buscador} alAgregar={agregarDelCatalogo} alCambiarPendientes={(n) => setEscaneando(n > 0)}
              />
              <Carrito
                lineas={lineas} vista={vista} alCambiar={cambiarLinea} alQuitar={quitarLinea}
                descuentoTotal={descuentoTotal}
                alCambiarDescuentoTotal={(d) => { if (congeladoAhora.current) return; setDescuentoTotal(d); setPagos([]) }}
                motivoEncargo={motivoEncargo} alCambiarMotivo={cambiarMotivo}
              />
            </SeccionCaja>
          </fieldset>
        </div>

        <div className="caja-columna-resumen">
          {envio.estado === 'ambiguo' && (
            <Aviso tipo="aviso">
              {MENSAJE_AMBIGUO}
              {restaurada && ' La página se recargó con esta venta a medias: reintenta y quedará registrada una sola vez.'}
              <span className="caja-acciones">
                <button type="button" className="boton" disabled={!puedeReintentar} onClick={iniciarCobro}>
                  Reintentar el cobro
                </button>
                <button type="button" className="boton secundario" disabled={envio.enviando} onClick={() => setAbandonando(true)}>
                  Abandonar esta venta
                </button>
              </span>
            </Aviso>
          )}
          {envio.estado === 'ambiguo' && abandonando && (
            <Confirmacion
              pregunta="¿Abandonar esta venta sin saber si se registró?"
              detalle="Si el cobro sí se registró, el cliente podría quedar cobrado y la venta tendría que revisarse en las ventas del turno. Si no estás segura, reintenta."
              textoConfirmar="Sí, abandonar" textoCancelar="Seguir reintentando" enviando={envio.enviando}
              alConfirmar={vaciar} alCancelar={() => setAbandonando(false)}
            />
          )}
          {envio.estado === 'claveEnUso' && (
            <>
              <Aviso tipo="peligro">{mensaje}</Aviso>
              <button type="button" className="boton" onClick={vaciar}>Empezar otra venta</button>
            </>
          )}
          {envio.estado === 'libre' && mensaje && !dialogo && <Aviso tipo="peligro">{mensaje}</Aviso>}

          <Cobro
            vista={vista} medios={medios.datos ?? []} saldo={saldo} medioSaldoId={medioSaldoId} pagos={pagos} alCambiarPagos={setPagos}
            bloqueo={bloqueo} contraEntrega={contraEntrega} sinConexion={sinConexion}
            enviando={envio.enviando} congelado={congelado} esperando={escaneando} alCobrar={iniciarCobro}
          />
        </div>
      </div>

      {dialogo === 'pin' && (
        <DialogoPin motivos={motivos} alConfirmar={enviar} alCerrar={() => setDialogo(null)} />
      )}
    </>
  )
}

/**
 * La caja: vender y cobrar en el mostrador (RF-41 a RF-45, RF-66). Sin turno abierto
 * pide abrirlo. Necesita internet: sin conexión no cobra nada a medias.
 */
export default function Caja() {
  const turno = useDatos(() => turnoActual())
  const [abierto, setAbierto] = useState(null)
  const actual = abierto ?? turno.datos

  let cuerpo
  if (turno.cargando) cuerpo = <EstadoCarga />
  else if (turno.sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (turno.error) cuerpo = <EstadoError error={turno.error} alReintentar={turno.recargar} />
  else if (!actual) cuerpo = <AbrirTurno alAbrir={setAbierto} />
  else cuerpo = <Venta turno={actual} />

  return (
    <div className="contenido seccion-caja">
      <header className="caja-cabecera caja-cabecera-fila">
        <div>
          <p className="caja-eyebrow">Ventas</p>
          <h1>Caja</h1>
          <p className="caja-subtitulo">
            {actual?.cajero ? `Turno de ${actual.cajero} · base ${PESOS.format(actual.baseEfectivo)}` : 'Vender y cobrar en el mostrador.'}
          </p>
        </div>
        {actual && (
          <div className="caja-cabecera-acciones">
            <Link to="/caja/mis-cierres" className="boton secundario enlace-boton">Mis cierres</Link>
            <Link to="/caja/cierre" className="boton secundario enlace-boton">Cerrar turno</Link>
          </div>
        )}
      </header>
      <AvisoConexion />
      {cuerpo}
    </div>
  )
}
