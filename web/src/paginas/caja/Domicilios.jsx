import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import Aviso from '../../componentes/comun/Aviso.jsx'
import EstadoCarga from '../../componentes/comun/EstadoCarga.jsx'
import EstadoError from '../../componentes/comun/EstadoError.jsx'
import EstadoSinPermiso from '../../componentes/comun/EstadoSinPermiso.jsx'
import EstadoVacio from '../../componentes/comun/EstadoVacio.jsx'
import TablaOTarjetas from '../../componentes/comun/TablaOTarjetas.jsx'
import { useDatos } from '../../hooks/useDatos.js'
import {
  cobrarDomicilio, entregarDomicilio, listarDomicilios, mediosDePago, saldoDeCliente,
} from '../../servicios/api/caja.js'
import { PESOS } from '../../servicios/formato.js'
import '../../estilos/caja.css'
import Cobro from './Cobro.jsx'
import Pestanas from './Pestanas.jsx'
import useEnLinea from './useEnLinea.js'
import { pagoParaApi } from './venta.js'

const PESTANAS = [
  { id: 'POR_DESPACHAR', titulo: 'Por despachar' },
  { id: 'POR_COBRAR', titulo: 'Por cobrar' },
]
const VACIO = {
  POR_DESPACHAR: 'No hay domicilios por despachar.',
  POR_COBRAR: 'No hay domicilios por cobrar.',
}

/** Cuánto lleva esperando el cobro, en palabras. */
export function antiguedad(dias) {
  const n = Number(dias) || 0
  if (n <= 0) return 'De hoy'
  return n === 1 ? 'Hace 1 día' : `Hace ${n} días`
}

/**
 * El cobro de un domicilio que se entregó sin cobrar: el mismo `Cobro` de la venta, por el saldo por
 * cobrar de esa venta. Vive en su propia pieza para pedir los medios y el saldo solo cuando se abre.
 */
function PanelCobro({ venta, alTerminar, alCancelar }) {
  const enLinea = useEnLinea()
  const medios = useDatos(() => mediosDePago())
  const [saldo, setSaldo] = useState({ saldo: 0, medioId: null })
  const [pagos, setPagos] = useState([])
  const [enviando, setEnviando] = useState(false)
  const [fallo, setFallo] = useState(null)

  useEffect(() => {
    if (!venta.clienteId) return undefined
    let vigente = true
    saldoDeCliente(venta.clienteId)
      .then((r) => { if (vigente) setSaldo({ saldo: Number(r?.saldo) || 0, medioId: r?.medioId ?? null }) })
      .catch(() => {})
    return () => { vigente = false }
  }, [venta.clienteId])

  async function cobrar() {
    setFallo(null)
    setEnviando(true)
    try {
      await cobrarDomicilio(venta.ventaId, pagos.map(pagoParaApi))
      alTerminar()
    } catch (error) {
      setFallo(error.message || 'No se pudo registrar el cobro.')
      setEnviando(false)
    }
  }

  const total = Number(venta.valorPorCobrar)
  return (
    <div className="caja-columna-resumen">
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
      {medios.error && <Aviso tipo="peligro">No se pudieron traer los medios de pago.</Aviso>}
      <Cobro
        vista={{ subtotal: total, iva: 0, descuento: 0, total, motivo: null, lineas: [{}] }}
        medios={medios.datos ?? []} saldo={saldo.saldo} medioSaldoId={saldo.medioId}
        pagos={pagos} alCambiarPagos={setPagos} bloqueo={null} contraEntrega={false}
        sinConexion={!enLinea} enviando={enviando} alCobrar={cobrar}
        titulo={`Cobro de ${venta.consecutivo}`} rotulo="Domicilio" desglose={false} totalRotulo="Saldo por cobrar"
      />
      <button type="button" className="boton secundario" disabled={enviando} onClick={alCancelar}>Cancelar el cobro</button>
    </div>
  )
}

/**
 * Domicilios: lo que hay que despachar y lo que se entregó sin cobrar (pago contra entrega).
 * «Marcar entregado» sirve en las dos pestañas; «Registrar cobro» solo en «Por cobrar».
 */
export default function Domicilios() {
  const [estado, setEstado] = useState('POR_DESPACHAR')
  const [cobrando, setCobrando] = useState(null)
  const [entregando, setEntregando] = useState(null)
  const [fallo, setFallo] = useState(null)
  const lista = useDatos(() => listarDomicilios(estado), [estado])
  const porCobrar = estado === 'POR_COBRAR'

  async function entregar(domicilio) {
    setFallo(null)
    setEntregando(domicilio.ventaId)
    try {
      await entregarDomicilio(domicilio.ventaId)
      lista.recargar()
    } catch (error) {
      setFallo(error.message || 'No se pudo marcar el domicilio como entregado.')
    } finally {
      setEntregando(null)
    }
  }

  const columnas = [
    { clave: 'venta', titulo: 'Venta', principal: true, render: (d) => d.consecutivo },
    { clave: 'cliente', titulo: 'Cliente', render: (d) => d.cliente },
    {
      clave: 'direccion', titulo: 'Dirección',
      render: (d) => (
        <span className="caja-producto">
          <span>{d.direccion}, {d.ciudad}</span>
          <span className="caja-seccion-descripcion">Recibe {d.recibe} · {d.telefono}</span>
        </span>
      ),
    },
    ...(porCobrar ? [
      { clave: 'valor', titulo: 'Por cobrar', numerica: true, render: (d) => PESOS.format(d.valorPorCobrar) },
      { clave: 'antiguedad', titulo: 'Antigüedad', render: (d) => antiguedad(d.antiguedadDias) },
    ] : []),
    {
      clave: 'acciones', titulo: 'Acciones',
      render: (d) => (
        <span className="caja-acciones-fila">
          {porCobrar && (
            <button
              type="button" className="boton menudo" aria-label={`Registrar cobro ${d.consecutivo}`}
              onClick={() => { setFallo(null); setCobrando(d) }}
            >
              Registrar cobro
            </button>
          )}
          <button
            type="button" className="boton secundario menudo" disabled={entregando === d.ventaId}
            aria-label={`Marcar entregado ${d.consecutivo}`} onClick={() => entregar(d)}
          >
            Marcar entregado
          </button>
        </span>
      ),
    },
  ]

  let cuerpo
  if (lista.sinPermiso) cuerpo = <EstadoSinPermiso />
  else if (lista.cargando && !lista.datos) cuerpo = <EstadoCarga />
  else if (lista.error) cuerpo = <EstadoError error={lista.error} alReintentar={lista.recargar} />
  else if ((lista.datos ?? []).length === 0) cuerpo = <EstadoVacio titulo={VACIO[estado]} />
  else {
    cuerpo = (
      <TablaOTarjetas
        etiqueta={porCobrar ? 'Domicilios por cobrar' : 'Domicilios por despachar'}
        filas={lista.datos} claveFila={(d) => d.ventaId} columnas={columnas}
      />
    )
  }

  const abierto = porCobrar && cobrando && (lista.datos ?? []).some((d) => d.ventaId === cobrando.ventaId)

  return (
    <div className="contenido seccion-caja">
      <header className="caja-cabecera caja-cabecera-fila">
        <div>
          <p className="caja-eyebrow">Caja</p>
          <h1>Domicilios</h1>
          <p className="caja-subtitulo">Pedidos que salen de la tienda y pagos que se cobran al entregar.</p>
        </div>
        <div className="caja-cabecera-acciones">
          <Link to="/caja" className="boton secundario enlace-boton">Volver a la caja</Link>
        </div>
      </header>
      {fallo && <Aviso tipo="peligro">{fallo}</Aviso>}
      <div className={abierto ? 'caja-rejilla' : undefined}>
        <div className="caja-columna-principal">
          <Pestanas
            etiqueta="Estado del domicilio" pestanas={PESTANAS} activa={estado}
            alCambiar={(id) => { setFallo(null); setCobrando(null); setEstado(id) }}
          >
            {cuerpo}
          </Pestanas>
        </div>
        {abierto && (
          <PanelCobro
            key={cobrando.ventaId} venta={cobrando}
            alTerminar={() => { setCobrando(null); lista.recargar() }} alCancelar={() => setCobrando(null)}
          />
        )}
      </div>
    </div>
  )
}
