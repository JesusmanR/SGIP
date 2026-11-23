/** Datos y arreglos comunes de las pruebas de la caja. Las llamadas a la API se simulan. */
import { vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import {
  administradores, clientePorIdentificacion, datosDeLaEmpresa, mediosDePago, productoPorCodigo,
  productosPorTexto, registrarVenta, saldoDeCliente, turnoActual,
} from '../../servicios/api/caja.js'
import { PESOS } from '../../servicios/formato.js'
import { guardarSesion } from '../../servicios/sesion.js'

// Lo que el navegador escribe con espacio duro, las pruebas lo comparan con espacio normal.
export const pesos = (n) => PESOS.format(n).replaceAll(String.fromCharCode(160), ' ')

export const TORNILLO = {
  id: 30, codigo: 'TOR-6', nombre: 'Tornillo M6', presentacion: 'Caja x100', codigoBarras: '7701234567890',
  existencia: 50, precio: 10000, tarifaIva: 0.19, tratamientoIva: 'GRAVADO', vendible: true, motivoNoVendible: null,
}
export const PINTURA = {
  id: 31, codigo: 'PIN-1', nombre: 'Pintura blanca', presentacion: 'Galón', codigoBarras: '7709990001112',
  existencia: 3, precio: 20000, tarifaIva: 0.19, tratamientoIva: 'GRAVADO', vendible: true, motivoNoVendible: null,
}
export const SIN_PRECIO = {
  id: 32, codigo: 'SIN-1', nombre: 'Lija sin precio', presentacion: 'Hoja', codigoBarras: '7700000000001',
  existencia: 9, precio: null, tarifaIva: 0, tratamientoIva: 'GRAVADO', vendible: false,
  motivoNoVendible: 'Este producto no tiene precio de venta.',
}
// Dos productos con el mismo precio y distinto IVA, con el id al revés del orden en que se escanean.
export const LLAVE_A = {
  id: 20, codigo: 'LLA-A', nombre: 'Llave A', presentacion: 'Unidad', codigoBarras: '7700000000020',
  existencia: 9, precio: 1003, tarifaIva: 0.19, tratamientoIva: 'GRAVADO', vendible: true, motivoNoVendible: null,
}
export const LLAVE_B = {
  id: 10, codigo: 'LLA-B', nombre: 'Llave B', presentacion: 'Unidad', codigoBarras: '7700000000010',
  existencia: 9, precio: 1003, tarifaIva: 0, tratamientoIva: 'EXENTO', vendible: true, motivoNoVendible: null,
}
export const PRODUCTOS = [TORNILLO, PINTURA, SIN_PRECIO, LLAVE_A, LLAVE_B]

export const MEDIOS = [
  { id: 1, nombre: 'Efectivo', tipo: 'EFECTIVO', activo: true, deSistema: false },
  { id: 2, nombre: 'Tarjeta', tipo: 'TARJETA', activo: true, deSistema: false },
  { id: 3, nombre: 'Transferencia', tipo: 'TRANSFERENCIA', activo: true, deSistema: false },
  { id: 9, nombre: 'Saldo a favor', tipo: 'SALDO_A_FAVOR', activo: false, deSistema: true },
]
export const ADMINS = [{ id: 3, nombre: 'Laura Pérez' }, { id: 4, nombre: 'Mario Gómez' }]
export const TURNO = {
  id: 5, cajeroId: 2, cajero: 'Marta Ruiz', apertura: '2026-11-16T08:00:00-05:00', baseEfectivo: 50000, cierre: null,
}
export const ANA = {
  id: 7, nombre: 'Ana Pérez', tipoDocumento: 'CC', identificacion: '1020304050', correo: 'ana@correo.co',
  telefono: '3001112233', direccion: 'Calle 1 # 2-3', ciudad: 'Cali', consumidorFinal: false,
}

// ── El servidor simulado ─────────────────────────────────────────────
// Repite, escrito aparte y sin usar el código del front, lo que hacen NormalizadorDeVenta y
// CalculoDeVenta: las líneas se ordenan por productoId; el residuo del reparto del
// descuento total va a la línea de mayor base y, a igual base, a la de menor productoId; y los
// pagos tienen que sumar exactamente el total (si no, 400). Así una diferencia de un peso entre
// la vista previa y el servidor se ve en las pruebas.
const mitad = (a, b) => Math.floor((2 * a + b) / (2 * b))

function descontado(descuento, valor) {
  if (!descuento) return 0
  if (descuento.tipo === 'PESOS') return descuento.valor
  return mitad(valor * Math.round(descuento.valor * 100), 10000)
}

export function ventaDelServidor(solicitud) {
  const ordenadas = [...solicitud.lineas].sort((a, b) => a.productoId - b.productoId)
  const lineas = ordenadas.map((l) => {
    const producto = PRODUCTOS.find((p) => p.id === l.productoId)
    const precio = producto.precio
    const tarifa = producto.tarifaIva
    const subtotal = precio * l.cantidad
    const propio = descontado(l.descuento, subtotal)
    return { productoId: l.productoId, cantidad: l.cantidad, precio, tarifa, subtotal, propio, base: subtotal - propio }
  })
  const suma = lineas.reduce((a, l) => a + l.base, 0)
  const aDescontar = descontado(solicitud.descuentoTotal, suma)
  const partes = lineas.map((l) => (suma === 0 ? 0 : mitad(aDescontar * l.base, suma)))
  let residuo = aDescontar - partes.reduce((a, p) => a + p, 0)
  const orden = lineas.map((_, i) => i)
    .sort((i, j) => (lineas[j].base - lineas[i].base) || (lineas[i].productoId - lineas[j].productoId))
  for (const i of orden) {
    if (residuo === 0) break
    const cabe = residuo > 0 ? Math.min(lineas[i].base - partes[i], residuo) : Math.max(-partes[i], residuo)
    partes[i] += cabe
    residuo -= cabe
  }
  const calculadas = lineas.map((l, i) => {
    const descuento = l.propio + partes[i]
    const base = l.subtotal - descuento
    const iva = mitad(base * Math.round(l.tarifa * 10000), 10000)
    return {
      productoId: l.productoId, cantidad: l.cantidad, precioUnitario: l.precio, subtotal: l.subtotal,
      descuento, base, tarifaIva: l.tarifa, tratamientoIva: 'GRAVADO', iva, total: base + iva,
    }
  })
  const total = calculadas.reduce((a, l) => a + l.total, 0)
  const contraEntrega = Boolean(solicitud.domicilio?.contraEntrega)
  const pagado = solicitud.pagos.reduce((a, p) => a + p.monto, 0)
  if (contraEntrega ? solicitud.pagos.length > 0 : pagado !== total) {
    throw new ErrorApi(400, contraEntrega
      ? 'Un domicilio contra entrega no lleva pagos.'
      : 'Los pagos no suman el total de la venta.')
  }
  const cambio = solicitud.pagos.reduce((a, p) => a + ((p.recibido ?? p.monto) - p.monto), 0)
  return { id: 100, consecutivo: 'V-0001', total, cambio, lineas: calculadas }
}

/** Deja la API simulada con respuestas razonables; cada prueba cambia lo que le interesa. */
export function prepararApi({ turno = TURNO } = {}) {
  vi.mocked(turnoActual).mockResolvedValue(turno)
  vi.mocked(mediosDePago).mockResolvedValue(MEDIOS)
  vi.mocked(administradores).mockResolvedValue(ADMINS)
  vi.mocked(datosDeLaEmpresa).mockResolvedValue({ razonSocial: 'Black Steel Parts', nit: '900123456-7' })
  vi.mocked(productoPorCodigo).mockImplementation(async (codigo) =>
    PRODUCTOS.find((p) => p.codigoBarras === codigo || p.codigo === codigo) ?? null)
  vi.mocked(productosPorTexto).mockImplementation(async (texto) =>
    PRODUCTOS.filter((p) => p.nombre.toLowerCase().includes(texto.toLowerCase())))
  vi.mocked(clientePorIdentificacion).mockResolvedValue(ANA)
  vi.mocked(saldoDeCliente).mockResolvedValue({ saldo: 0 })
  vi.mocked(registrarVenta).mockImplementation(async (solicitud) => ventaDelServidor(solicitud))
}

export function entrar(rol) {
  sessionStorage.clear()
  guardarSesion({ token: 't', nombre: 'Marta Ruiz', rol })
}

export const errorApi = (estado, detalle, tipo) => new ErrorApi(estado, detalle, tipo)
