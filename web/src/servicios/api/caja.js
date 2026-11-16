import { ErrorApi, pedir } from '../api.js'

/**
 * Llamadas de la caja (Sprint 9). Una función por ruta: si el backend cambia
 * una ruta, se ajusta aquí y en ningún otro sitio.
 *
 * Supuestos que el backend de esta rama todavía no cumple (se simulan en las
 * pruebas y se ajustan aquí cuando existan):
 * - `saldoDeCliente`: GET /caja/clientes/{id}/saldo responde `{ saldo }`.
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

export const productoPorCodigo = (codigo) =>
  olvidandoElNoEncontrado(pedir(`/caja/productos?codigo=${encodeURIComponent(codigo)}`))
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

export const registrarVenta = (solicitud) => pedir('/caja/ventas', { method: 'POST', body: solicitud })
