/**
 * Cómo se escriben los números en la aplicación.
 *
 * Estaba duplicado en `Catalogo.jsx` y en `Importacion.jsx`, y el panel
 * de indicadores iba a ser el tercero. Tres copias de un
 * `Intl.NumberFormat` no son un problema de líneas repetidas: son tres
 * sitios donde alguien puede cambiar los decimales en uno y no en los
 * otros, y la pantalla mostraría el mismo precio escrito de dos formas
 * según por dónde se llegara.
 *
 * La regla de interfaz que esto cumple la fija la especificación de
 * P-02: «los importes se presentan en pesos colombianos con separador
 * de miles de punto». Con la configuración regional `es-CO` el punto es
 * el separador de miles y la coma el decimal, que es lo que un usuario
 * colombiano lee sin traducir.
 */

/**
 * Un importe exacto: `$ 2.520.000`.
 *
 * Sin decimales a propósito. El sistema trabaja en pesos enteros
 * —ADR-0013— y los céntimos que el motor guarda en `DECIMAL(14,2)` son
 * para que una multiplicación intermedia no pierda precisión, no para
 * mostrarse.
 */
export const PESOS = new Intl.NumberFormat('es-CO', {
  style: 'currency', currency: 'COP', maximumFractionDigits: 0,
})

/**
 * El espacio duro, U+00A0, escrito por su código y no como carácter.
 *
 * Es el mismo que `Intl` pone tras el signo del peso. Va por su código
 * porque un espacio duro suelto en el fuente es indistinguible de uno
 * normal al leerlo, y es justo la clase de carácter que alguien
 * «limpia» sin saber que estaba ahí a propósito. El lint de este
 * proyecto lo prohíbe literal por ese motivo.
 */
const DURO = String.fromCharCode(160)

/**
 * Un importe abreviado: `$ 842,3 M`.
 *
 * Para la cifra de cabecera del panel, donde el mockup pide esta forma.
 * Nunca sustituye al importe exacto: donde se usa, el exacto aparece al
 * lado. Un panel que solo diga «842,3 M» no sirve para comparar con el
 * de la semana pasada, que es para lo que una cifra de capital se mira.
 *
 * Se escribe a mano en lugar de con `notation: 'compact'` porque el
 * compacto de `Intl` para `es-CO` abrevia el millón como «M» y el mil
 * como «k», mezclando un sufijo español con uno inglés en la misma
 * pantalla.
 *
 * El espacio tras el signo es un espacio duro, el mismo carácter que
 * usa `Intl` —U+00A0, no el 32—. No es un detalle tipográfico: los dos
 * importes aparecen uno al lado del otro en la misma tarjeta, y con un
 * espacio normal el abreviado podría partirse entre «$» y «84,3» al
 * final de un renglón mientras el exacto no.
 *
 * Y el redondeo es el de `toFixed`, que trabaja sobre el binario: 84,35
 * millones salen como «84,3 M» y no como «84,4 M», porque 84,35 en
 * coma flotante queda justo por debajo de su valor decimal. Es correcto
 * para una cifra de cabecera que lleva el importe exacto al lado, y es
 * la clase de diferencia que conviene tener escrita antes de que
 * alguien la lea como un error de cálculo.
 */
export function pesosAbreviados(valor) {
  const n = Number(valor)
  if (!Number.isFinite(n)) return '—'
  const signo = n < 0 ? '-' : ''
  const a = Math.abs(n)
  const uno = (divisor, sufijo) =>
    `${signo}$${DURO}${(a / divisor).toFixed(1).replace('.', ',')} ${sufijo}`
  if (a >= 1_000_000_000) return uno(1_000_000_000, 'MM')
  if (a >= 1_000_000) return uno(1_000_000, 'M')
  if (a >= 1_000) return `${signo}$${DURO}${(a / 1_000).toFixed(0)} mil`
  return PESOS.format(n)
}

/** Una cantidad de unidades: `1.248`. */
export const NUMERO = new Intl.NumberFormat('es-CO')

/**
 * Una fecha y hora corta: `18 sep 2026, 9:14`.
 *
 * El backend entrega `LocalDateTime` sin zona, de modo que la cadena
 * llega como `2026-09-18T09:14:00` y el navegador la interpreta en la
 * zona del equipo. Es lo correcto aquí: la fecha de una carga de
 * precios es la hora local del comercio que la subió, y el servidor y
 * el comercio están en la misma.
 */
export function fechaCorta(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleString('es-CO', {
    day: 'numeric', month: 'short', year: 'numeric',
    hour: 'numeric', minute: '2-digit',
  })
}

/** Un día del eje del gráfico: `18 sep`. */
export function diaCorto(iso) {
  if (!iso) return ''
  const d = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleDateString('es-CO', { day: 'numeric', month: 'short' })
}
