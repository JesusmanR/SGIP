import { pedir } from '../api.js'

/** Cuántos resultados trae la búsqueda de productos al armar una orden. */
export const PRODUCTOS_POR_BUSQUEDA = 8

/**
 * Llamadas de la sección «Órdenes de compra». El servidor no pagina por
 * desplazamiento: solo acepta un `limite` (50 por omisión, 200 como tope)
 * y un `estado`. No hay ruta para anular ni para editar una orden.
 */
export function listarOrdenes({ estado = '', limite } = {}) {
  const partes = []
  if (estado) partes.push(`estado=${encodeURIComponent(estado)}`)
  if (limite) partes.push(`limite=${limite}`)
  return pedir(`/compras/ordenes${partes.length ? `?${partes.join('&')}` : ''}`)
}

export const verOrden = (ordenId) => pedir(`/compras/ordenes/${ordenId}`)
export const crearOrden = (datos) => pedir('/compras/ordenes', { method: 'POST', body: datos })
export const recibirOrden = (ordenId, datos) =>
  pedir(`/compras/ordenes/${ordenId}/recepciones`, { method: 'POST', body: datos })

/** La búsqueda es del servidor: nunca se trae el catálogo entero. */
export const buscarProductos = (texto) =>
  pedir(`/productos?busqueda=${encodeURIComponent(texto)}&limite=${PRODUCTOS_POR_BUSQUEDA}`)
