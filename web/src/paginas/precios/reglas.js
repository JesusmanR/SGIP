/**
 * Lo que la pantalla sabe de una regla de ganancia: cómo se llaman sus
 * valores y cómo se pasa del porcentaje que escribe la persona al factor que
 * recibe el backend.
 *
 * El front no calcula precios. Esta conversión no lo es: el backend guarda
 * «uno más el porcentaje» y la persona piensa en porcentaje.
 */

export const FORMAS_PAGO = [
  { valor: 'CONTADO', etiqueta: 'Contado' },
  { valor: 'CREDITO', etiqueta: 'Crédito' },
]

export const FORMAS_CALCULO = [
  {
    valor: 'SOBRE_COSTO',
    etiqueta: 'Recargo sobre el costo',
    ayuda: 'El porcentaje se suma al costo del producto.',
  },
  {
    valor: 'SOBRE_PRECIO',
    etiqueta: 'Margen sobre el precio de venta',
    ayuda: 'El porcentaje es la parte del precio de venta que queda de ganancia.',
  },
]

const etiquetaDe = (lista, valor) => lista.find((x) => x.valor === valor)?.etiqueta ?? valor
export const etiquetaDePago = (valor) => etiquetaDe(FORMAS_PAGO, valor)
export const etiquetaDeCalculo = (valor) => etiquetaDe(FORMAS_CALCULO, valor)

/** Cómo se llama el tratamiento de IVA de un producto, en palabras de tienda. */
const TRATAMIENTOS = {
  GRAVADO: 'Con IVA',
  EXCLUIDO: 'Excluido de IVA',
  EXENTO: 'Exento de IVA',
  NO_GRAVADO: 'Sin IVA',
  EXCLUIDO_HASTA_UMBRAL: 'Excluido hasta el tope',
}
export const etiquetaDeTratamiento = (valor) => TRATAMIENTOS[valor] ?? 'Sin definir'

/** A qué productos apunta una regla, tal como lo escribiría una persona. */
export function textoDeAlcance(regla) {
  const partes = []
  if (regla.categoriaId != null) partes.push(`Categoría ${regla.categoria ?? regla.categoriaId}`)
  if (regla.proveedorId != null) partes.push(`Proveedor ${regla.proveedor ?? regla.proveedorId}`)
  return partes.length === 0 ? 'Todos los productos' : partes.join(' · ')
}

const POR_COSTO_MAXIMO = 989999 // 9.899,99 %: el factor admite dos enteros y cuatro decimales
const POR_PRECIO_TOPE = 10000 // 100 %: con él el precio sería infinito

/**
 * Pasa «20,5» a `{ factor: 1.205 }`, o a `{ error }` con lo que hay que
 * arreglar. Se trabaja con centésimas de punto en enteros: restar o sumar uno
 * en coma flotante da cosas como 0,20250000000000004.
 */
export function porcentajeAFactor(texto, formaCalculo) {
  const t = (texto ?? '').trim()
  if (t === '') return { error: 'Escribe el porcentaje de ganancia.' }
  if (/^[\d.,]+$/.test(t) && t.includes('.')) {
    return { error: 'Usa coma para los decimales, por ejemplo 12,5.' }
  }
  if (!/^\d+(,\d*)?$/.test(t)) {
    return { error: 'Escribe solo números, con coma para los decimales, por ejemplo 12,5.' }
  }
  const [entero, decimales = ''] = t.split(',')
  if (decimales.length > 2) return { error: 'El porcentaje admite hasta dos decimales.' }
  const centesimas = Number(entero) * 100 + Number(decimales.padEnd(2, '0'))
  if (centesimas <= 0) return { error: 'El porcentaje tiene que ser mayor que cero.' }
  if (formaCalculo === 'SOBRE_PRECIO') {
    if (centesimas >= POR_PRECIO_TOPE) {
      return { error: 'El margen sobre el precio tiene que ser menor que 100 %.' }
    }
  } else if (centesimas > POR_COSTO_MAXIMO) {
    return { error: 'El recargo sobre el costo no puede pasar de 9.899,99 %.' }
  }
  const total = 10000 + centesimas
  const fraccion = String(total % 10000).padStart(4, '0')
  return { factor: Number(`${Math.floor(total / 10000)}.${fraccion}`) }
}

/** Pasa el factor del backend (1,2) al texto que se edita («20,00»). */
export function factorAPorcentaje(factor) {
  const centesimas = Math.round((Number(factor) - 1) * 10000)
  return `${Math.floor(centesimas / 100)},${String(centesimas % 100).padStart(2, '0')}`
}
