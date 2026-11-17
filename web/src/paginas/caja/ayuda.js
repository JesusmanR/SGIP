/** Datos y arreglos comunes de las pruebas de la caja. Las llamadas a la API se simulan. */
import { vi } from 'vitest'

import { ErrorApi } from '../../servicios/api.js'
import {
  administradores, clientePorIdentificacion, datosDeLaEmpresa, mediosDePago, productoPorCodigo,
  productosPorTexto, registrarVenta, saldoDeCliente, turnoActual,
} from '../../servicios/api/caja.js'
import { PESOS } from '../../servicios/formato.js'
import { guardarSesion } from '../../servicios/sesion.js'

export const pesos = (n) => PESOS.format(n)

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
export const PRODUCTOS = [TORNILLO, PINTURA, SIN_PRECIO]

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

/** Lo que el servidor devolvería: total y cambio de los pagos enviados, y una línea por línea pedida. */
export function ventaDelServidor(solicitud) {
  const total = solicitud.pagos.reduce((a, p) => a + p.monto, 0)
  const cambio = solicitud.pagos.reduce((a, p) => a + ((p.recibido ?? p.monto) - p.monto), 0)
  return {
    id: 100,
    consecutivo: 'V-0001',
    total,
    cambio,
    lineas: solicitud.lineas.map((l, i) => ({
      productoId: l.productoId ?? 900 + i, cantidad: l.cantidad, precioUnitario: 10000,
      subtotal: 10000 * l.cantidad, descuento: 0, base: 10000 * l.cantidad, tarifaIva: 0.19,
      tratamientoIva: 'GRAVADO', iva: 0, total: 10000 * l.cantidad,
    })),
  }
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

export const errorApi = (estado, detalle) => new ErrorApi(estado, detalle)
