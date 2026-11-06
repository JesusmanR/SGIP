import { pedir } from '../api.js'

/**
 * Llamadas de la sección Reglas de ganancia (RF-30, RF-32). Solo las rutas
 * que el backend tiene hoy: `/precios/reglas`. No hay lista de precios, ni
 * revisión de una lista, ni informe de precios bajo el costo fuera de la
 * importación, y por eso aquí tampoco.
 *
 * Aquí solo van las reglas generales (por categoría o proveedor). El precio y
 * el porcentaje de un producto se escriben en el producto y esas reglas
 * propias no se listan desde esta ruta.
 *
 * Los cuerpos se arman campo a campo: lo que no se manda no se puede mandar
 * por error. El `factor` es uno más el porcentaje.
 */
const cuerpo = (r) => ({
  categoriaId: r.categoriaId,
  proveedorId: r.proveedorId,
  formaPago: r.formaPago,
  formaCalculo: r.formaCalculo,
  factor: r.factor,
  vigenteDesde: r.vigenteDesde,
  vigenteHasta: r.vigenteHasta,
})

function alcanceConsulta(categoria, proveedor) {
  const partes = []
  if (categoria !== null && categoria !== undefined) partes.push(`categoria=${encodeURIComponent(categoria)}`)
  if (proveedor !== null && proveedor !== undefined) partes.push(`proveedor=${encodeURIComponent(proveedor)}`)
  return partes.length === 0 ? '' : `?${partes.join('&')}`
}

export const apiPrecios = {
  listar: () => pedir('/precios/reglas'),
  ver: (reglaId) => pedir(`/precios/reglas/${reglaId}`),
  alcance: (categoria, proveedor) =>
    pedir(`/precios/reglas/alcance${alcanceConsulta(categoria, proveedor)}`),
  simular: (regla) => pedir('/precios/reglas/simulacion', { method: 'POST', body: cuerpo(regla) }),
  crear: (regla) => pedir('/precios/reglas', { method: 'POST', body: cuerpo(regla) }),
  // Cierra la vigente y abre la nueva en una sola transacción del servidor.
  reemplazar: (reglaId, regla) =>
    pedir(`/precios/reglas/${reglaId}/reemplazar`, { method: 'POST', body: cuerpo(regla) }),
  // Corrige en el sitio: solo se usa con una regla que todavía no empieza.
  corregir: (reglaId, regla) =>
    pedir(`/precios/reglas/${reglaId}`, { method: 'PUT', body: cuerpo(regla) }),
  // Sin fecha cierra ayer: la regla deja de aplicar hoy mismo.
  cerrar: (reglaId, hasta) =>
    pedir(
      `/precios/reglas/${reglaId}/cerrar${hasta ? `?hasta=${encodeURIComponent(hasta)}` : ''}`,
      { method: 'POST' },
    ),
  categorias: () => pedir('/categorias'),
  proveedores: () => pedir('/proveedores'),
}
