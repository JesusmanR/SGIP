/**
 * La venta en curso: funciones puras que arman líneas, avisos y el cuerpo que se envía.
 * La pantalla guarda el estado; aquí no hay estado ni llamadas a la API.
 *
 * Una línea es `{ clave, producto | nuevo, cantidad (texto), descuentoTipo, descuentoValor (texto),
 * encargo, entregaEstimada }`. `producto` es el `ProductoParaCaja` del servidor; `nuevo` es un
 * producto que todavía no existe y se crea al cobrar, dentro de la venta.
 */
import { hoyISO } from '../../servicios/fechas.js'

export const CANTIDAD_MAXIMA = 99999

let contador = 0
const nuevaClave = () => `linea-${++contador}`

function base(extra) {
  return {
    clave: nuevaClave(), cantidad: '1', descuentoTipo: 'PORCENTAJE', descuentoValor: '',
    encargo: false, entregaEstimada: '', ...extra,
  }
}

export const lineaDeProducto = (producto) => base({ producto })

export function lineaDeProductoNuevo({ nombre, precioVenta, codigoBarras, entregaEstimada }) {
  return base({ nuevo: { nombre, precioVenta, codigoBarras: codigoBarras || '' }, encargo: true, entregaEstimada })
}

export const nombreDe = (l) => l.producto?.nombre ?? l.nuevo.nombre
export const precioDe = (l) => Number(l.producto?.precio ?? l.nuevo.precioVenta)
/** El IVA de un producto nuevo lo fija el servidor al crearlo: la vista previa no lo conoce. */
export const tarifaDe = (l) => Number(l.producto?.tarifaIva ?? 0)

/** La cantidad como número entero de 1 a 99 999, o NaN si lo escrito no vale. */
export function cantidadDe(l) {
  const texto = String(l.cantidad).trim()
  if (!/^\d{1,5}$/.test(texto)) return Number.NaN
  const n = Number(texto)
  return n >= 1 && n <= CANTIDAD_MAXIMA ? n : Number.NaN
}

/** Un descuento escrito a mano como `{ tipo, valor }` numérico, o null si está vacío. */
export function descuentoDe(descuento) {
  if (!descuento) return null
  const valor = Number(String(descuento.valor ?? '').trim().replace(',', '.'))
  if (!Number.isFinite(valor) || valor <= 0) return null
  return { tipo: descuento.tipo, valor }
}

/** Lo que mira la vista previa de cada línea; una cantidad que no vale cuenta como cero. */
export function lineasParaVista(lineas) {
  return lineas.map((l) => ({
    clave: l.clave,
    productoId: l.producto?.id ?? null,
    precio: precioDe(l),
    tarifaIva: tarifaDe(l),
    cantidad: Number.isNaN(cantidadDe(l)) ? 0 : cantidadDe(l),
    descuento: descuentoDe({ tipo: l.descuentoTipo, valor: l.descuentoValor }),
  }))
}

/** El mismo producto dos veces suma 1; un producto nuevo siempre es su propia línea. */
export function agregarProducto(lineas, producto) {
  const existente = lineas.find((l) => l.producto?.id === producto.id)
  if (!existente) return [...lineas, lineaDeProducto(producto)]
  return lineas.map((l) => {
    if (l !== existente) return l
    const n = cantidadDe(l)
    return { ...l, cantidad: String(Number.isNaN(n) ? 1 : Math.min(n + 1, CANTIDAD_MAXIMA)) }
  })
}

/** Cuántas unidades piden de más de las que hay; 0 si alcanza o no se sabe. */
export function faltante(l) {
  if (!l.producto) return 0
  const n = cantidadDe(l)
  if (Number.isNaN(n)) return 0
  return Math.max(0, n - Math.max(0, l.producto.existencia))
}

export const esEncargo = (l) => Boolean(l.nuevo) || l.encargo
export const tieneEncargo = (lineas) => lineas.some(esEncargo)

/**
 * Lo que un vendedor no puede hacer solo y necesita el PIN de un administrador
 * (descuento, encargo, producto nuevo). Una sola lista para un solo PIN.
 */
export function motivosDeAutorizacion(lineas, descuentoTotal) {
  const motivos = []
  const conDescuento = lineas.some((l) => descuentoDe({ tipo: l.descuentoTipo, valor: l.descuentoValor }))
  if (conDescuento || descuentoDe(descuentoTotal)) motivos.push('Descuento en la venta')
  for (const l of lineas.filter((x) => x.nuevo)) motivos.push(`Producto nuevo: «${l.nuevo.nombre}»`)
  if (tieneEncargo(lineas)) motivos.push('Venta como encargo (sin existencia)')
  return motivos
}

const DOMICILIO_VACIO = { telefono: '', direccion: '', ciudad: '', recibe: '', contraEntrega: false }
export const domicilioVacio = (cliente) => ({
  ...DOMICILIO_VACIO,
  telefono: cliente?.telefono ?? '',
  direccion: cliente?.direccion ?? '',
  ciudad: cliente?.ciudad ?? '',
})

const domicilioCompleto = (d) => ['telefono', 'direccion', 'ciudad', 'recibe'].every((k) => d[k].trim() !== '')

/**
 * Por qué la venta todavía no se puede cobrar, en palabras; null si nada lo impide.
 * Va en un orden fijo: primero lo que falta en el carrito, luego el cliente.
 */
export function bloqueoDeVenta({ lineas, vista, cliente, domicilio, motivoEncargo }) {
  if (lineas.length === 0) return 'Agrega al menos un producto.'
  for (const l of lineas) {
    if (Number.isNaN(cantidadDe(l))) {
      return `La cantidad de «${nombreDe(l)}» debe ser un número entero de 1 a ${CANTIDAD_MAXIMA}.`
    }
  }
  if (vista.motivo) return vista.motivo
  for (const l of lineas) {
    if (faltante(l) > 0 && !l.encargo) {
      return `No hay existencia suficiente de «${nombreDe(l)}». Véndelo como encargo o baja la cantidad.`
    }
  }
  for (const l of lineas.filter(esEncargo)) {
    if (!l.entregaEstimada) return `Falta la fecha de entrega estimada de «${nombreDe(l)}».`
    if (l.entregaEstimada < hoyISO()) return `La fecha de entrega de «${nombreDe(l)}» no puede ser anterior a hoy.`
  }
  if (tieneEncargo(lineas) && !motivoEncargo.trim()) return 'Escribe el motivo del encargo.'
  if (vista.total <= 0) return 'Una venta no puede quedar en cero.'
  if (domicilio) {
    if (!cliente) return 'Un domicilio necesita un cliente identificado.'
    if (!domicilioCompleto(domicilio)) return 'Completa los datos del domicilio.'
  }
  return null
}

/**
 * Si «Cobrar» (o «Reintentar») puede enviarse: nada lo bloquea, hay conexión, no se está enviando y
 * lo pagado es igual al total (en contra entrega no hay pagos). Una sola regla para los dos botones.
 */
export function puedeCobrar({ bloqueo, vista, pagos, contraEntrega, sinConexion, enviando }) {
  if (sinConexion || enviando || bloqueo) return false
  if (contraEntrega) return vista.total > 0
  return vista.total > 0 && pagos.reduce((a, p) => a + p.monto, 0) === vista.total
}

/** Un pago como lo espera el servidor: solo los datos que su medio usa. */
function pagoParaApi(p) {
  const pago = { medioId: p.medioId, monto: p.monto }
  if (p.tipo === 'EFECTIVO') pago.recibido = p.recibido ?? p.monto
  else if (p.tipo === 'TARJETA' || p.tipo === 'TRANSFERENCIA') pago.referencia = p.referencia
  return pago
}

function lineaParaApi(l) {
  const linea = { cantidad: cantidadDe(l) }
  if (l.nuevo) {
    linea.productoNuevo = { nombre: l.nuevo.nombre.trim(), precioVenta: Number(l.nuevo.precioVenta) }
    if (l.nuevo.codigoBarras) linea.productoNuevo.codigoBarras = l.nuevo.codigoBarras
  } else {
    linea.productoId = l.producto.id
  }
  const descuento = descuentoDe({ tipo: l.descuentoTipo, valor: l.descuentoValor })
  if (descuento) linea.descuento = descuento
  if (esEncargo(l)) linea.entregaEstimada = l.entregaEstimada
  return linea
}

/** El cuerpo de `POST /caja/ventas`. Nunca lleva un precio de un producto registrado. */
export function armarSolicitud({
  claveIdempotencia, cliente, lineas, descuentoTotal, pagos, motivoEncargo, autorizacion, domicilio,
}) {
  const solicitud = {
    claveIdempotencia,
    lineas: lineas.map(lineaParaApi),
    pagos: domicilio?.contraEntrega ? [] : pagos.map(pagoParaApi),
  }
  if (cliente) solicitud.clienteId = cliente.id
  const total = descuentoDe(descuentoTotal)
  if (total) solicitud.descuentoTotal = total
  if (tieneEncargo(lineas)) solicitud.motivoEncargo = motivoEncargo.trim()
  if (autorizacion) solicitud.autorizacion = autorizacion
  if (domicilio) {
    solicitud.domicilio = {
      direccion: domicilio.direccion.trim(), ciudad: domicilio.ciudad.trim(),
      telefono: domicilio.telefono.trim(), recibe: domicilio.recibe.trim(),
      contraEntrega: domicilio.contraEntrega,
    }
  }
  return solicitud
}

const dos = (n) => String(n).padStart(2, '0')

/** El recibo con los importes que devolvió el servidor; de la pantalla solo salen los nombres. */
export function armarRecibo({ empresa, venta, lineas, pagos, cliente, cajero, domicilio, ahora = new Date() }) {
  const delServidor = venta.lineas ?? []
  // El servidor devuelve las líneas ordenadas por producto, no como se escanearon: cada una de
  // catálogo se busca por su productoId, y los productos nuevos toman las que quedan, en orden.
  const porId = new Map(delServidor.map((s) => [s.productoId, s]))
  const deProductosNuevos = delServidor.filter((s) => !lineas.some((l) => l.producto?.id === s.productoId))
  let siguienteNuevo = 0
  const suma = (campo) => delServidor.reduce((a, l) => a + Number(l[campo]), 0)
  return {
    empresa,
    consecutivo: venta.consecutivo,
    fecha: `${hoyISO(ahora)}T${dos(ahora.getHours())}:${dos(ahora.getMinutes())}`,
    cajero,
    cliente: cliente ? { nombre: cliente.nombre, identificacion: cliente.identificacion } : null,
    lineas: lineas.map((l) => {
      const s = (l.producto ? porId.get(l.producto.id) : deProductosNuevos[siguienteNuevo++]) ?? {}
      return {
        nombre: nombreDe(l),
        cantidad: s.cantidad ?? cantidadDe(l),
        precioUnitario: Number(s.precioUnitario ?? precioDe(l)),
        subtotal: Number(s.subtotal ?? 0),
        descuento: Number(s.descuento ?? 0),
        iva: Number(s.iva ?? 0),
        total: Number(s.total ?? 0),
        encargo: esEncargo(l),
        nuevo: Boolean(l.nuevo),
        entregaEstimada: esEncargo(l) ? l.entregaEstimada : null,
      }
    }),
    subtotal: suma('subtotal'),
    descuento: suma('descuento'),
    iva: suma('iva'),
    total: Number(venta.total),
    cambio: Number(venta.cambio ?? 0),
    pagos: domicilio?.contraEntrega ? [] : pagos.map((p) => ({
      medio: p.nombre, monto: p.monto, recibido: p.tipo === 'EFECTIVO' ? p.recibido ?? null : null,
      referencia: p.referencia || null,
    })),
    domicilio: domicilio ? { ...domicilio, direccion: domicilio.direccion.trim() } : null,
    porCobrar: Boolean(domicilio?.contraEntrega),
  }
}

/** Un identificador para la clave de idempotencia; sin crypto.randomUUID (páginas sin https) lo arma a mano. */
export function nuevaClaveDeVenta() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const b = globalThis.crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}
