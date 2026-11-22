/**
 * Cálculos de la devolución. Puros: la pantalla guarda el estado y el servidor tiene la última palabra;
 * esto es la vista previa para que el cajero vea cuánto va a devolver.
 */

/** Las unidades que todavía se pueden devolver de una línea. */
export const restantes = (linea) => linea.cantidad - (linea.cantidadDevuelta ?? 0)

/** La cantidad escrita como entero de 0 a lo que queda por devolver; NaN si no vale. */
export function cantidadAdevolver(linea, texto) {
  const t = String(texto ?? '').trim()
  if (t === '') return 0
  if (!/^\d{1,5}$/.test(t)) return Number.NaN
  const n = Number(t)
  return n <= restantes(linea) ? n : Number.NaN
}

/**
 * Lo que vale devolver `k` unidades de una línea (con su descuento e IVA ya incluidos): el total de la
 * línea por k entre n, al peso (HALF_UP). La devolución que completa la línea toma el resto, de modo
 * que todas las devoluciones de una línea suman exactamente su total.
 */
export function valorDeUnidades(linea, k) {
  if (!(k > 0)) return 0
  const queda = Number(linea.total) - Number(linea.valorDevuelto ?? 0)
  if (k === restantes(linea)) return Math.max(0, queda)
  // Como el servidor: lo proporcional, sin pasar de lo que queda por devolver de la línea.
  const proporcional = Math.floor((2 * Number(linea.total) * k + linea.cantidad) / (2 * linea.cantidad))
  return Math.max(0, Math.min(proporcional, queda))
}

/**
 * `unidades` de la solicitud: vacía quiere decir «anular toda la venta», y solo se usa cuando se
 * devuelve todo y nada se había devuelto antes; si no, se listan las unidades de cada producto.
 */
export function unidadesParaApi(lineas, cantidades) {
  const elegidas = lineas
    .map((l) => ({ linea: l, k: cantidadAdevolver(l, cantidades[l.productoId]) }))
    .filter((x) => x.k > 0)
  const todo = lineas.every((l) => !(l.cantidadDevuelta > 0)
    && cantidadAdevolver(l, cantidades[l.productoId]) === l.cantidad)
  if (todo) return []
  return elegidas.map(({ linea, k }) => ({ productoId: linea.productoId, cantidad: k }))
}
