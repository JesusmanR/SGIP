/**
 * Vista previa del total de una venta, antes de cobrar. Es solo para que el
 * cajero vea cuánto va a cobrar: el importe que vale es el que devuelve el
 * servidor al registrar la venta (y el recibo usa ese).
 *
 * Repite las reglas de `CalculoDeVenta` del backend:
 * - el precio es SIN IVA;
 * - el descuento de la línea va antes que el de toda la compra;
 * - un porcentaje se redondea a pesos con HALF_UP;
 * - el descuento de toda la compra se reparte por la base de cada línea y el
 *   residuo va a la de mayor base (a igual base, la primera);
 * - el IVA de cada línea es base × tarifa, a pesos con HALF_UP;
 * - el servidor fusiona y ordena las líneas por productoId, y los productos nuevos (que nacen al
 *   cobrar, con ids mayores) van después en el orden en que se agregaron. El reparto del residuo
 *   desempata por menor productoId, así que aquí se ordena igual: el orden de escaneo no cuenta.
 *
 * Todo se hace con enteros (BigInt) para que 1 005 × 10 % no dependa del
 * punto flotante. Las tarifas y los porcentajes se llevan a 4 decimales.
 */
const ESCALA = 10000n

/** Un número o texto con hasta 4 decimales, como entero escalado a 10 000. */
function escalado(valor) {
  return BigInt(Math.round(Number(valor) * 10000))
}

/** a / b redondeado al entero, con la mitad hacia arriba (todo no negativo). */
function mitadArriba(a, b) {
  return (2n * a + b) / (2n * b)
}

const MOTIVOS = {
  mayor: 'El descuento no puede ser mayor que el valor al que se aplica.',
  porcentaje: 'Un descuento en porcentaje no puede pasar de 100.',
  centavos: 'Un descuento en pesos debe ser un monto entero.',
  decimales: 'El descuento admite hasta 2 decimales.',
}

/** Cuántos pesos descuenta `descuento` de `valor`; o `{ motivo }` si no vale. */
function importeDelDescuento(descuento, valor) {
  if (!descuento || !(Number(descuento.valor) > 0)) return { importe: 0n }
  const pedido = escalado(descuento.valor)
  if (pedido % 100n !== 0n) return { motivo: MOTIVOS.decimales }
  let importe
  if (descuento.tipo === 'PORCENTAJE') {
    if (pedido > 100n * ESCALA) return { motivo: MOTIVOS.porcentaje }
    importe = mitadArriba(valor * pedido, 100n * ESCALA)
  } else {
    if (pedido % ESCALA !== 0n) return { motivo: MOTIVOS.centavos }
    importe = pedido / ESCALA
  }
  if (importe > valor) return { motivo: MOTIVOS.mayor }
  return { importe }
}

/** Reparte `total` entre las bases; el residuo va a la de mayor base y, si empatan, a la primera. */
function repartir(bases, suma, total) {
  const partes = bases.map((b) => (suma === 0n ? 0n : mitadArriba(total * b, suma)))
  let residuo = total - partes.reduce((a, p) => a + p, 0n)
  const orden = bases.map((_, i) => i).sort((i, j) => {
    if (bases[j] !== bases[i]) return bases[j] > bases[i] ? 1 : -1
    return i - j
  })
  for (const i of orden) {
    if (residuo === 0n) break
    let cabe
    if (residuo > 0n) cabe = bases[i] - partes[i] < residuo ? bases[i] - partes[i] : residuo
    else cabe = -partes[i] > residuo ? -partes[i] : residuo
    partes[i] += cabe
    residuo -= cabe
  }
  return partes
}

const CERO = {
  lineas: [], subtotal: 0, descuento: 0, iva: 0, total: 0, tieneDescuento: false, motivo: null,
}

/**
 * `lineas`: `[{ clave, precio, tarifaIva, cantidad, descuento: { tipo, valor } | null }]`, con
 * `tipo` `PORCENTAJE` o `PESOS`. Devuelve los importes en pesos (números enteros) y `motivo`, que
 * es null salvo que un descuento no valga: entonces los importes no se deben mostrar.
 */
/** Catálogo por productoId ascendente y, después, los productos nuevos (sin id) en su orden; estable. */
function comoLasOrdenaElServidor(lineas) {
  return [...lineas].sort((a, b) => {
    if (a.productoId == null || b.productoId == null) return (a.productoId == null) - (b.productoId == null)
    return a.productoId - b.productoId
  })
}

export function calcularVistaPrevia(lineasEscaneadas, descuentoTotal = null) {
  if (lineasEscaneadas.length === 0) return { ...CERO }
  const lineas = comoLasOrdenaElServidor(lineasEscaneadas)
  let motivo = null
  const subtotales = []
  const propios = []
  const bases = []
  let tieneDescuento = false
  for (const l of lineas) {
    const subtotal = BigInt(Math.round(Number(l.precio))) * BigInt(l.cantidad)
    const d = importeDelDescuento(l.descuento, subtotal)
    if (d.motivo) motivo ??= d.motivo
    const propio = d.importe ?? 0n
    subtotales.push(subtotal)
    propios.push(propio)
    bases.push(subtotal - propio)
    tieneDescuento ||= Number(l.descuento?.valor) > 0
  }
  const sumaDeBases = bases.reduce((a, b) => a + b, 0n)
  const t = importeDelDescuento(descuentoTotal, sumaDeBases)
  if (t.motivo) motivo ??= t.motivo
  tieneDescuento ||= Number(descuentoTotal?.valor) > 0
  if (motivo) return { ...CERO, tieneDescuento, motivo }

  const repartido = repartir(bases, sumaDeBases, t.importe ?? 0n)
  const salida = []
  let subtotal = 0n
  let descuento = 0n
  let iva = 0n
  lineas.forEach((l, i) => {
    const descuentoDeLinea = propios[i] + repartido[i]
    const base = subtotales[i] - descuentoDeLinea
    const ivaDeLinea = mitadArriba(base * escalado(l.tarifaIva), ESCALA)
    salida.push({
      clave: l.clave,
      subtotal: Number(subtotales[i]),
      descuento: Number(descuentoDeLinea),
      base: Number(base),
      iva: Number(ivaDeLinea),
      total: Number(base + ivaDeLinea),
    })
    subtotal += subtotales[i]
    descuento += descuentoDeLinea
    iva += ivaDeLinea
  })
  return {
    lineas: salida,
    subtotal: Number(subtotal),
    descuento: Number(descuento),
    iva: Number(iva),
    total: Number(subtotal - descuento + iva),
    tieneDescuento,
    motivo: null,
  }
}
