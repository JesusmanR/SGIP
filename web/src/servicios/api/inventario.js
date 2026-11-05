import { pedir } from '../api.js'

/**
 * Llamadas de la sección Inventario (RF-48, RF-49). Solo las rutas que el
 * backend tiene hoy.
 *
 * Los listados de movimientos no paginan ni filtran: aceptan solo `limite`
 * (100 por omisión, 500 como máximo). La búsqueda de producto es la del
 * catálogo, hecha en el servidor, para no traer todo el catálogo al estado.
 *
 * Los cuerpos se arman campo a campo: lo que no se manda no se puede
 * mandar por error. El ajuste lleva el saldo contado y no una diferencia.
 */
export const apiInventario = {
  movimientos: (limite) => pedir(`/inventario/movimientos?limite=${limite}`),
  kardex: (productoId, limite) =>
    pedir(`/inventario/productos/${productoId}/movimientos?limite=${limite}`),
  cuadre: (productoId) => pedir(`/inventario/productos/${productoId}/cuadre`),
  producto: (productoId) => pedir(`/productos/${productoId}`),
  buscarProductos: (texto) =>
    pedir(`/productos?busqueda=${encodeURIComponent(texto)}&limite=10`),
  ingresar: ({ productoId, cantidad, documento, motivo }) =>
    pedir('/inventario/ingresos', { method: 'POST', body: { productoId, cantidad, documento, motivo } }),
  ajustar: ({ productoId, existenciaContada, motivo, documento }) =>
    pedir('/inventario/ajustes', {
      method: 'POST', body: { productoId, existenciaContada, motivo, documento },
    }),
}
