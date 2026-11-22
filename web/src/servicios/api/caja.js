import { ErrorApi, pedir } from '../api.js'

/**
 * Llamadas de la caja (Sprint 9). Una función por ruta: si el backend cambia
 * una ruta, se ajusta aquí y en ningún otro sitio.
 *
 * Supuestos que el backend de esta rama todavía no cumple (se simulan en las
 * pruebas y se ajustan aquí cuando existan):
 * - `saldoDeCliente`: GET /caja/clientes/{id}/saldo responde `{ saldo, medioId }`, con el
 *   medio de sistema SALDO_A_FAVOR con el que se paga con el saldo.
 * - Tareas 8 a 11 (domicilios, encargos, devoluciones, cierre): las formas de respuesta de las
 *   listas y de `GET /caja/ventas?consecutivo=` están descritas en las pantallas que las usan.
 * - Una venta con `domicilio` o con un `productoNuevo` hoy responde 400
 *   «aún no disponible»; el cuerpo que se arma ya es el definitivo.
 * - El medio de pago «Saldo a favor» (de sistema) llega en `mediosDePago`
 *   para poder pagar con él.
 */

/** Un 404 quiere decir «no hay»; es una respuesta, no un fallo. */
async function olvidandoElNoEncontrado(promesa) {
  try {
    return await promesa
  } catch (error) {
    if (error instanceof ErrorApi && error.estado === 404) return null
    throw error
  }
}

export const turnoActual = () => pedir('/caja/turnos/actual')
export const abrirTurno = (baseEfectivo) =>
  pedir('/caja/turnos', { method: 'POST', body: { baseEfectivo } })
export const resumenDelTurno = () => pedir('/caja/turnos/actual/resumen')
export const cerrarTurno = (solicitud) =>
  pedir('/caja/turnos/actual/cierre', { method: 'POST', body: solicitud })
/** El administrador recibe todos los turnos de la empresa; el vendedor, solo los suyos (lo decide el servidor). */
export const listarTurnos = ({ desde, hasta }) =>
  pedir(`/caja/turnos?desde=${encodeURIComponent(desde)}&hasta=${encodeURIComponent(hasta)}`)

export const listarDomicilios = (estado) => pedir(`/caja/domicilios?estado=${encodeURIComponent(estado)}`)
export const entregarDomicilio = (ventaId) => pedir(`/caja/domicilios/${ventaId}/entrega`, { method: 'POST' })
/** El cuerpo es la lista de pagos, por el saldo por cobrar actual de la venta. */
export const cobrarDomicilio = (ventaId, pagos) =>
  pedir(`/caja/domicilios/${ventaId}/cobro`, { method: 'POST', body: pagos })

export const listarEncargos = (estado) => pedir(`/caja/encargos?estado=${encodeURIComponent(estado)}`)
export const entregarEncargo = (itemId) => pedir(`/caja/encargos/${itemId}/entrega`, { method: 'POST' })

/** La consulta por código no respondió a tiempo: no se sabe si el producto existe. */
export class BusquedaAgotada extends Error {
  constructor() {
    super('La consulta del código tardó demasiado.')
    this.name = 'BusquedaAgotada'
  }
}

/** Lo que se espera a que responda la consulta por código antes de darla por perdida. */
export const PLAZO_DE_BUSQUEDA_MS = 10000

/**
 * Solo esta consulta tiene plazo: un lector manda códigos sin parar y una consulta colgada
 * dejaría la cola de escaneos (y «Cobrar») esperando para siempre.
 */
export async function productoPorCodigo(codigo) {
  const control = new AbortController()
  const plazo = setTimeout(() => control.abort(), PLAZO_DE_BUSQUEDA_MS)
  try {
    return await olvidandoElNoEncontrado(
      pedir(`/caja/productos?codigo=${encodeURIComponent(codigo)}`, { signal: control.signal }),
    )
  } catch (error) {
    if (error?.name === 'AbortError') throw new BusquedaAgotada()
    throw error
  } finally {
    clearTimeout(plazo)
  }
}
export const productosPorTexto = (texto) =>
  pedir(`/caja/productos?q=${encodeURIComponent(texto)}`)

export const clientePorIdentificacion = (tipo, identificacion) =>
  olvidandoElNoEncontrado(pedir(
    `/caja/clientes?tipo=${encodeURIComponent(tipo)}&identificacion=${encodeURIComponent(identificacion)}`,
  ))
export const crearCliente = (datos) => pedir('/caja/clientes', { method: 'POST', body: datos })
export const actualizarCliente = (id, datos) =>
  pedir(`/caja/clientes/${id}`, { method: 'PUT', body: datos })
export const saldoDeCliente = (id) => pedir(`/caja/clientes/${id}/saldo`)

export const mediosDePago = () => pedir('/caja/medios-pago')
export const administradores = () => pedir('/caja/administradores')
export const datosDeLaEmpresa = () => pedir('/empresa')

/** La venta que se va a devolver, con lo ya devuelto de cada línea; un 404 es «no hay una con ese consecutivo». */
export const ventaPorConsecutivo = (consecutivo) =>
  olvidandoElNoEncontrado(pedir(`/caja/ventas?consecutivo=${encodeURIComponent(consecutivo)}`))
export const registrarDevolucion = (solicitud) => pedir('/caja/devoluciones', { method: 'POST', body: solicitud })

/** Los medios de pago del administrador: todos, también los inactivos y el de sistema. */
export const listarMedios = () => pedir('/medios-pago')
export const cambiarActivoMedio = (id, activo) => pedir(`/medios-pago/${id}`, { method: 'PATCH', body: { activo } })

export const registrarVenta = (solicitud) => pedir('/caja/ventas', { method: 'POST', body: solicitud })

/**
 * El `type` del ProblemDetail del 409 que responde el servidor cuando la clave de idempotencia
 * ya se usó con otra carga. Supuesto de contrato: hoy no existe; el backend lo añadirá al integrar.
 */
export const CLAVE_EN_USO = 'urn:sgip:caja:clave-en-uso'
