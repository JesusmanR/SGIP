/**
 * Todas las fechas de la aplicación se escriben desde aquí, con los meses
 * escritos a mano (`6 nov 2026`) y no con `Intl`: según el motor, el mes sale
 * con punto, con «de» o como «sept», y la pantalla mostraría la misma fecha
 * escrita de dos formas.
 *
 * Fechas de vigencia: el backend manda y recibe `LocalDate` (`2026-11-06`),
 * un día sin hora ni zona.
 *
 * Dos trampas que esto evita. `new Date('2026-11-06')` es medianoche UTC, que
 * en Colombia es el 5 a las 7 de la noche, y `toISOString()` da el día
 * siguiente desde las 7 de la noche. Aquí «hoy» sale de los componentes
 * locales y un día se interpreta como medianoche local.
 */
const dos = (n) => String(n).padStart(2, '0')

/** Hoy, en hora local, como `AAAA-MM-DD`. */
export function hoyISO(ahora = new Date()) {
  return `${ahora.getFullYear()}-${dos(ahora.getMonth() + 1)}-${dos(ahora.getDate())}`
}

/** El día `n` días después (o antes, si es negativo) de otro, sin pasar por la zona horaria. */
export function sumarDias(iso, n) {
  const [a, m, d] = iso.split('-').map(Number)
  const f = new Date(Date.UTC(a, m - 1, d + n))
  return `${f.getUTCFullYear()}-${dos(f.getUTCMonth() + 1)}-${dos(f.getUTCDate())}`
}

export const MESES = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic']

/** `6 nov 2026`, o `—` si no hay fecha. */
export function fechaDia(iso) {
  const partes = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? '')
  if (!partes) return '—'
  const mes = MESES[Number(partes[2]) - 1]
  if (!mes) return '—'
  return `${Number(partes[3])} ${mes} ${partes[1]}`
}

/** Dónde está una regla en el tiempo: rige, va a empezar o ya terminó. */
export function estadoDeVigencia(regla, hoy) {
  if (regla.vigente) return 'rige'
  return regla.vigenteDesde > hoy ? 'proxima' : 'cerrada'
}

/** «Desde el 1 ene 2026» o «Del 1 ene 2026 al 30 jun 2026». */
export function textoDeVigencia(regla) {
  return regla.vigenteHasta
    ? `Del ${fechaDia(regla.vigenteDesde)} al ${fechaDia(regla.vigenteHasta)}`
    : `Desde el ${fechaDia(regla.vigenteDesde)}`
}
