import { api } from './api.js'
import { prepararImagen } from './imagenes.js'

/** El RF-63 admite cinco por producto, y el esquema lo hace cumplir. */
export const MAXIMO_POR_PRODUCTO = 5

/**
 * Sube varias imágenes a un producto, pero de una en una.
 *
 * Vive aquí y no dentro de una pantalla porque se usa desde dos: el
 * recuadro de la tabla y la galería. Tenerlo en un sitio evita que una
 * de las dos acabe subiendo en paralelo por descuido.
 *
 * La subida es SECUENCIAL a propósito, y no por prudencia: en paralelo
 * se pisarían. Dos peticiones simultáneas sobre un producto sin fotos
 * leen las dos que no hay ninguna, las dos se declaran principal y el
 * índice único rechaza a la segunda; y las dos piden el mayor orden en
 * uso, obtienen el mismo número y chocan contra
 * uq_producto_imagenes_orden. El servidor hace bien en impedirlo: el
 * que no debe provocarlo es el cliente.
 *
 * Un archivo que falle no detiene a los demás. Subir cinco y perderlas
 * todas porque la tercera era un HEIC sería peor que decir cuál falló.
 *
 * @param yaHay cuántas tiene ya el producto, si quien llama lo sabe. La
 *        galería lo sabe y puede avisar antes de enviar nada; la tabla
 *        no, y entonces el que corta es el servidor, con su mensaje. El
 *        tope lo hace cumplir el esquema en cualquier caso: esto solo
 *        ahorra un viaje.
 */
export async function subirImagenes(productoId, archivos, yaHay = 0) {
  const creadas = []
  const problemas = []

  for (const archivo of archivos) {
    if (yaHay + creadas.length >= MAXIMO_POR_PRODUCTO) {
      problemas.push(`${archivo.name}: ya hay ${MAXIMO_POR_PRODUCTO} imágenes, que es el máximo.`)
      continue
    }
    try {
      // Se prepara antes de enviar: convierte lo que el servidor no sabe
      // leer y reduce lo que no hace falta mandar entero.
      creadas.push(await api.subirImagen(productoId, await prepararImagen(archivo)))
    } catch (e) {
      problemas.push(`${archivo.name}: ${e.message}`)
    }
  }
  return { creadas, problemas }
}
