import { pedir } from '../api.js'

/**
 * Llamadas de la sección Impuestos (ADR-0016, ADR-0024). Solo las rutas que
 * el backend tiene hoy: `/empresa/tributacion` y lo que cuelga de ella.
 *
 * Las reglas de IVA se crean y se cierran, no se editan: una venta se explica
 * con la regla que regía el día que se hizo. Por eso aquí no hay `editar`.
 *
 * El PUT de la configuración reemplaza todo y lleva los seis campos; los
 * cuerpos se arman campo a campo para no mandar por error lo que el backend no
 * recibe (por ejemplo `configuracionCompleta`, que él calcula).
 */
export const apiImpuestos = {
  configuracion: () => pedir('/empresa/tributacion'),
  guardar: (c) => pedir('/empresa/tributacion', {
    method: 'PUT',
    body: {
      responsableIva: c.responsableIva,
      aplicaExclusionUmbral: c.aplicaExclusionUmbral,
      practicaRetenciones: c.practicaRetenciones,
      tarifaIca: c.tarifaIca,
      manejaEnvios: c.manejaEnvios,
      cotizacionVigenciaDias: c.cotizacionVigenciaDias,
    },
  }),
  catalogo: () => pedir('/empresa/tributacion/impuestos'),
  reglas: () => pedir('/empresa/tributacion/reglas'),
  crearRegla: (r) => pedir('/empresa/tributacion/reglas', {
    method: 'POST',
    body: {
      categoriaId: r.categoriaId,
      productoId: r.productoId,
      codigoImpuesto: r.codigoImpuesto,
      vigenteDesde: r.vigenteDesde,
      vigenteHasta: r.vigenteHasta,
      fundamento: r.fundamento,
    },
  }),
  // Sin fecha: cierra ayer, o retira (204, `null`) una regla que aún no empieza.
  cerrarRegla: (reglaId, hasta) =>
    pedir(
      `/empresa/tributacion/reglas/${reglaId}/cerrar${hasta ? `?hasta=${encodeURIComponent(hasta)}` : ''}`,
      { method: 'POST' },
    ),
  categorias: () => pedir('/categorias'),
  // La búsqueda de producto es la del catálogo, hecha en el servidor.
  buscarProductos: (texto) =>
    pedir(`/productos?busqueda=${encodeURIComponent(texto)}&limite=10`),
}
