import { porcentajeConDecimales } from '../../servicios/formato.js'

/**
 * Lo que la pantalla sabe de los impuestos: cómo se llaman las cosas en
 * palabras de tienda y cómo se pasa entre el porcentaje que se escribe y la
 * fracción que guarda el backend (0,19 es 19 %).
 */

const TRATAMIENTOS = {
  GRAVADO: 'Con IVA',
  EXCLUIDO: 'Excluido de IVA',
  EXENTO: 'Exento de IVA',
  NO_GRAVADO: 'Sin IVA',
  EXCLUIDO_HASTA_UMBRAL: 'Excluido hasta el tope',
}
export const etiquetaDeTratamiento = (valor) => TRATAMIENTOS[valor] ?? 'Sin definir'

/** La tarifa del catálogo es una fracción: 0,19 se lee 19,00 %. */
export const tarifaComoPorcentaje = (fraccion) => porcentajeConDecimales(Math.round(Number(fraccion) * 1e6) / 1e4)

/**
 * Qué cobra de verdad un tipo de impuesto, dicho con palabras. La exclusión
 * por tope lleva la tarifa 19 % en el catálogo, pero no se cobra hasta el
 * tope: mostrar solo «19 %» diría lo contrario de lo que pasa.
 */
export function descripcionDeImpuesto(tratamiento, tarifa) {
  if (tratamiento === 'EXCLUIDO_HASTA_UMBRAL') {
    return `Sin IVA hasta el tope; sobre el tope, ${tarifaComoPorcentaje(tarifa)}`
  }
  if (tratamiento === 'GRAVADO') return `Con IVA, ${tarifaComoPorcentaje(tarifa)}`
  return etiquetaDeTratamiento(tratamiento)
}

/** A qué productos apunta una regla de IVA. */
export function textoDeAlcance(regla) {
  if (regla.productoId != null) return `Producto ${regla.producto ?? regla.productoId}`
  if (regla.categoriaId != null) return `Categoría ${regla.categoria ?? regla.categoriaId}`
  return 'Todos los productos'
}

/**
 * La tarifa de ICA (fracción con seis decimales) como el porcentaje que se
 * edita, sin perder nada: se parte la cadena, no se multiplica. 0,00414 es
 * «0,414»; con dos decimales se vería «0,41» y guardarlo cambiaría la tarifa.
 */
export function fraccionAPorcentajeTexto(fraccion) {
  if (fraccion === null || fraccion === undefined) return ''
  const [entero, decimales] = Number(fraccion).toFixed(6).split('.')
  const porcentaje = String(Number(`${entero}${decimales.slice(0, 2)}`))
  const resto = decimales.slice(2).replace(/0+$/, '')
  return resto ? `${porcentaje},${resto}` : porcentaje
}

/**
 * El porcentaje escrito («0,966») a fracción (0,00966), con enteros: en coma
 * flotante 0,966 / 100 no da exactamente 0,00966. Vacío es «sin ICA».
 */
export function porcentajeTextoAFraccion(texto) {
  const t = (texto ?? '').trim()
  if (t === '') return { fraccion: null }
  if (/^[\d.,]+$/.test(t) && t.includes('.')) {
    return { error: 'Usa coma para los decimales, por ejemplo 0,966.' }
  }
  if (!/^\d+(,\d*)?$/.test(t)) {
    return { error: 'Escribe solo números, con coma para los decimales, por ejemplo 0,966.' }
  }
  const [entero, decimales = ''] = t.split(',')
  if (decimales.length > 4) return { error: 'El ICA admite hasta 4 decimales.' }
  const diezmilesimas = Number(entero) * 10000 + Number(decimales.padEnd(4, '0'))
  if (diezmilesimas > 1000000) return { error: 'El ICA tiene que estar entre 0 y 100 %.' }
  const millonesimas = String(diezmilesimas % 1000000).padStart(6, '0')
  return { fraccion: Number(`${Math.floor(diezmilesimas / 1000000)}.${millonesimas}`) }
}
