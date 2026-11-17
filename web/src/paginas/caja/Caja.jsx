import { useEffect, useMemo, useRef, useState } from 'react'
import Aviso from '../../componentes/comun/Aviso.jsx'
import AvisoConexion from '../../componentes/comun/AvisoConexion.jsx'
import Campo from '../../componentes/comun/Campo.jsx'
import EncabezadoPagina from '../../componentes/comun/EncabezadoPagina.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import { ErrorApi } from '../../servicios/api.js'
import {
  abrirTurno, datosDeLaEmpresa, mediosDePago, registrarVenta, saldoDeCliente, turnoActual,
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
import ProductoNuevo from './ProductoNuevo.jsx'
import ReciboImprimible from './ReciboImprimible.jsx'
import {
  agregarProducto, armarRecibo, armarSolicitud, bloqueoDeVenta, descuentoDe, lineaDeProductoNuevo,
  lineasParaVista, motivosDeAutorizacion, nuevaClaveDeVenta,
} from './venta.js'

const SIN_DESCUENTO = { tipo: 'PORCENTAJE', valor: '' }
const BASE = /^\d{1,12}$/
const MENSAJE_SIN_RED = 'Sin conexión con el servidor. No se cobró nada; puedes reintentar el cobro.'

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

function useEnLinea() {
  const [enLinea, setEnLinea] = useState(() => navigator.onLine !== false)
  useEffect(() => {
    const alPerder = () => setEnLinea(false)
    const alVolver = () => setEnLinea(true)
    window.addEventListener('offline', alPerder)
    window.addEventListener('online', alVolver)
    return () => {
      window.removeEventListener('offline', alPerder)
      window.removeEventListener('online', alVolver)
    }
  }, [])
  return enLinea
}

/** La venta en curso. Todo su estado vive aquí, en memoria: nada va al almacenamiento del navegador. */
function Venta({ turno }) {
  const sesion = leerSesion()
  const enLinea = useEnLinea()
  const empresa = useDatos(() => datosDeLaEmpresa())
  const medios = useDatos(() => mediosDePago())

  const [lineas, setLineas] = useState([])
  const [cliente, setCliente] = useState(null)
  const [domicilio, setDomicilio] = useState(null)
  const [descuentoTotal, setDescuentoTotal] = useState(SIN_DESCUENTO)
  const [motivoEncargo, setMotivoEncargo] = useState('')
  const [pagos, setPagos] = useState([])
  const [saldo, setSaldo] = useState(0)
  // Una clave por venta en curso: se repite en cada reintento y solo cambia cuando el
  // servidor confirma la venta o el cajero vacía el carrito.
  const [clave, setClave] = useState(nuevaClaveDeVenta)
  const [dialogo, setDialogo] = useState(null) // 'producto' | 'pin' | null
  const [codigoNuevo, setCodigoNuevo] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [sinRed, setSinRed] = useState(false)
  const [error, setError] = useState(null)
  const [recibo, setRecibo] = useState(null)
  const [impreso, setImpreso] = useState(false)
  const buscador = useRef(null)

  // El foco vuelve al buscador al cargar, al cerrar un diálogo y al empezar otra venta.
  useEffect(() => {
    if (!dialogo && !recibo) buscador.current?.focus()
  }, [dialogo, recibo])

  useEffect(() => { if (enLinea) setSinRed(false) }, [enLinea])

  useEffect(() => {
    if (!cliente) {
      setSaldo(0)
      return undefined
    }
    let vigente = true
    saldoDeCliente(cliente.id)
      .then((r) => { if (vigente) setSaldo(Number(r?.saldo) || 0) })
      .catch(() => { if (vigente) setSaldo(0) })
    return () => { vigente = false }
  }, [cliente])

  const vista = useMemo(
    () => calcularVistaPrevia(lineasParaVista(lineas), descuentoDe(descuentoTotal)),
    [lineas, descuentoTotal],
  )
  const bloqueo = bloqueoDeVenta({ lineas, vista, cliente, domicilio, motivoEncargo })
  const motivos = motivosDeAutorizacion(lineas, descuentoTotal)
  const necesitaPin = motivos.length > 0 && !esAdministrador(sesion)

  function cambiarLinea(claveLinea, cambios) {
    setLineas((actuales) => actuales.map((l) => (l.clave === claveLinea ? { ...l, ...cambios } : l)))
    setPagos([])
  }

  function quitarLinea(claveLinea) {
    const quedan = lineas.filter((l) => l.clave !== claveLinea)
    setLineas(quedan)
    setPagos([])
    // Un carrito vacío es otra venta: la clave se renueva.
    if (quedan.length === 0) vaciar()
  }

  function vaciar() {
    setLineas([])
    setCliente(null)
    setDomicilio(null)
    setDescuentoTotal(SIN_DESCUENTO)
    setMotivoEncargo('')
    setPagos([])
    setError(null)
    setSinRed(false)
    setClave(nuevaClaveDeVenta())
  }

  function agregarDelCatalogo(producto) {
    setLineas((actuales) => agregarProducto(actuales, producto))
    setPagos([])
  }

  function agregarNuevo(datos) {
    setLineas((actuales) => [...actuales, lineaDeProductoNuevo(datos)])
    if (datos.motivo) setMotivoEncargo(datos.motivo)
    setPagos([])
    setDialogo(null)
  }

  /** Envía la venta. Devuelve el texto del error, o null si quedó registrada. */
  async function enviar(autorizacion) {
    setEnviando(true)
    setError(null)
    setSinRed(false)
    try {
      const venta = await registrarVenta(armarSolicitud({
        claveIdempotencia: clave, cliente, lineas, descuentoTotal, pagos, motivoEncargo, autorizacion, domicilio,
      }))
      setRecibo(armarRecibo({
        empresa: empresa.datos ?? { razonSocial: '' }, venta, lineas, pagos, cliente,
        cajero: sesion?.nombre ?? turno.cajero, domicilio,
      }))
      setDialogo(null)
      vaciar()
      return null
    } catch (fallo) {
      if (fallo instanceof ErrorApi) {
        const mensaje = fallo.estado === 409
          ? `${fallo.message} Si esta venta ya se había registrado, no se cobró otra vez.`
          : fallo.message
        setError(mensaje)
        return mensaje
      }
      setSinRed(true)
      return MENSAJE_SIN_RED
    } finally {
      setEnviando(false)
    }
  }

  function iniciarCobro() {
    if (necesitaPin) setDialogo('pin')
    else enviar(null)
  }

  const sinConexion = !enLinea || sinRed

  if (recibo) {
    return (
      <div className="caja-recibo-pantalla">
        <div className="caja-recibo-acciones">
          <h2>Venta registrada</h2>
          <div className="caja-acciones">
            <button type="button" className="boton" onClick={() => window.print()}>Imprimir</button>
            <button type="button" className="boton secundario" onClick={() => { setImpreso(true); window.print() }}>
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
        <div className="caja-area-buscador">
          <BuscadorProducto
            entradaRef={buscador} alAgregar={agregarDelCatalogo}
            alCrearProducto={(codigo) => { setCodigoNuevo(codigo); setDialogo('producto') }}
          />
        </div>

        <div className="caja-area-carrito">
          <Carrito
            lineas={lineas} vista={vista} alCambiar={cambiarLinea} alQuitar={quitarLinea}
            descuentoTotal={descuentoTotal}
            alCambiarDescuentoTotal={(d) => { setDescuentoTotal(d); setPagos([]) }}
            motivoEncargo={motivoEncargo} alCambiarMotivo={setMotivoEncargo}
          />
          <DatosCliente
            cliente={cliente} alCambiarCliente={setCliente} domicilio={domicilio} alCambiarDomicilio={setDomicilio}
          />
        </div>

        <div className="caja-area-cobro">
          {sinRed && (
            <Aviso tipo="aviso">
              Sin conexión con el servidor. No se cobró nada.{' '}
              <button type="button" className="enlace" disabled={enviando} onClick={iniciarCobro}>
                Reintentar el cobro
              </button>
            </Aviso>
          )}
          {error && !dialogo && <Aviso tipo="peligro">{error}</Aviso>}
          <Cobro
            vista={vista} medios={medios.datos ?? []} saldo={saldo} pagos={pagos} alCambiarPagos={setPagos}
            bloqueo={bloqueo} contraEntrega={Boolean(domicilio?.contraEntrega)} sinConexion={sinConexion}
            enviando={enviando} alCobrar={iniciarCobro}
          />
        </div>
      </div>

      {dialogo === 'producto' && (
        <ProductoNuevo
          codigoInicial={codigoNuevo} motivoInicial={motivoEncargo}
          alAgregar={agregarNuevo} alCerrar={() => setDialogo(null)}
        />
      )}
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
      <EncabezadoPagina
        titulo="Caja"
        descripcion={actual?.cajero ? `Turno de ${actual.cajero} · base ${PESOS.format(actual.baseEfectivo)}` : 'Vender y cobrar en el mostrador.'}
      />
      <AvisoConexion />
      {cuerpo}
    </div>
  )
}
