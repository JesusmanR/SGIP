/**
 * Cómo se escribe aquí el valor de la UVT.
 *
 * `PESOS` de servicios/formato.js redondea a pesos enteros, que es lo que
 * conviene a un precio. La UVT se publica con decimales (47.065,5) y mostrarla
 * redondeada sería mostrar otro valor, así que esta sección usa su propio
 * formato: sin decimales si no los tiene y hasta dos si los tiene.
 */
export const UVT_PESOS = new Intl.NumberFormat('es-CO', {
  style: 'currency', currency: 'COP', minimumFractionDigits: 0, maximumFractionDigits: 2,
})
