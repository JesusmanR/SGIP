/**
 * Vista previa del precio y del porcentaje mientras se escribe (ADR-0025).
 *
 * Es solo vista previa: lo que se guarda es lo que el usuario escribió, y
 * el precio lo decide la vista de la base. Pero una vista previa que se
 * equivoca por un peso es peor que ninguna, porque enseña una cifra que
 * luego no se vende. Por eso no usa `Number`: en coma flotante,
 * 1001,25 × 1,20 da 1201 y la base da 1202. Se calcula con enteros exactos,
 * y `casos_de_precio.csv` es la tabla que obliga a este archivo, a Java y a
 * la vista a dar lo mismo.
 *
 * Devuelve texto, listo para un campo: `'1200'`, `'25.00'`. Lo que no se
 * puede calcular devuelve `''`, nunca `NaN` ni `Infinity`, porque un campo
 * vacío se entiende y un «NaN» en un formulario de precios no.
 */

const CIEN = 100n
const DIEZ_MIL = 10000n

/**
 * El valor multiplicado por 100, como entero exacto; `null` si no es un
 * número positivo con hasta dos decimales.
 *
 * Solo admite dígitos y punto a propósito: el `1e3` que deja escribir un
 * campo numérico no es una cifra que nadie quiera poner en un precio.
 */
function aCentesimas(valor) {
  if (valor === null || valor === undefined) return null
  const coincide = /^(\d*)(?:\.(\d*?)0*)?$/.exec(String(valor).trim())
  if (!coincide) return null
  const [, entera, decimales = ''] = coincide
  if (entera === '' && decimales === '') return null
  if (decimales.length > 2) return null
  const centesimas = BigInt(entera || '0') * CIEN + BigInt(decimales.padEnd(2, '0'))
  return centesimas > 0n ? centesimas : null
}

/**
 * División que redondea la mitad alejándose del cero, como `ROUND` de MySQL
 * sobre DECIMAL y `RoundingMode.HALF_UP` de Java.
 */
function dividirRedondeando(numerador, denominador) {
  const negativo = numerador < 0n
  const absoluto = negativo ? -numerador : numerador
  const cociente = (2n * absoluto + denominador) / (2n * denominador)
  return negativo ? -cociente : cociente
}

/**
 * Lo que vale el producto con ese porcentaje sobre el costo, en pesos
 * enteros: costo 1.000 y 20 % dan `'1200'`.
 *
 * Un porcentaje que redondea a cero pesos devuelve `'0'` y no vacío: el
 * cálculo existe, y es el formulario el que explica por qué no se admite.
 */
export function precioDesdePorcentaje(costo, porcentaje) {
  const c = aCentesimas(costo)
  const p = aCentesimas(porcentaje)
  if (c === null || p === null) return ''
  // costo/100 × (1 + porcentaje/10000), en enteros.
  return dividirRedondeando(c * (DIEZ_MIL + p), CIEN * DIEZ_MIL).toString()
}

/**
 * El porcentaje sobre el costo que da ese precio, con dos decimales:
 * costo 1.000 y precio 1.250 dan `'25.00'`; precio 900 da `'-10.00'`.
 */
export function porcentajeDesdePrecio(costo, precio) {
  const c = aCentesimas(costo)
  const p = aCentesimas(precio)
  if (c === null || p === null) return ''
  // En centésimas de punto: (precio − costo) / costo × 100 × 100.
  const puntos = dividirRedondeando((p - c) * DIEZ_MIL, c)
  const signo = puntos < 0n ? '-' : ''
  const absoluto = puntos < 0n ? -puntos : puntos
  return `${signo}${absoluto / CIEN}.${(absoluto % CIEN).toString().padStart(2, '0')}`
}
