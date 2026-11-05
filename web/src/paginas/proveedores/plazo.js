/** El plazo de pago en palabras: cero días es de contado. */
export const textoDePlazo = (dias) => (dias === 0 ? 'De contado' : `${dias} días`)
