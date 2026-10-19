/**
 * Referencias de ejemplo para las pruebas de pantalla.
 *
 * Las cifras no están inventadas: son las que devolvió
 * `v_precios_producto` sobre MySQL 8 con la cadena V1 a V25 aplicada, de
 * modo que una prueba que pase aquí describe un caso que la base produce
 * de verdad. Cada una dispara una anotación distinta del catálogo:
 *
 *   PC-001   excluido al contado y gravado a crédito, y cerca del umbral
 *   CEL-002  excluido en las dos modalidades: sin ninguna anotación
 *   MON-001  gravado en las dos, con margen sobre el precio de venta
 */
export const PC = {
  productoId: 1, codigo: 'PC-001', sku: 'PC-001', nombre: 'Portátil Lenovo V15',
  presentacion: '8 GB / 256 GB', descripcion: 'Equipo de oficina',
  codigoBarras: '7701234567890',
  categoriaId: 1, categoria: 'Computadores',
  proveedorId: 1, proveedor: 'Distribuidora La Esperanza',
  costo: 2100000, existencia: 12, existenciaMinima: 3, estadoStock: 'ACTIVO',
  precioContado: 2520000, precioCredito: 2625000,
  tratamientoIva: 'EXCLUIDO_HASTA_UMBRAL', tarifaContado: 0,
  totalContado: 2520000, totalCredito: 3123750,
  baseContado: 'SOBRE_COSTO', baseCredito: 'SOBRE_COSTO',
  tributacionSinConfigurar: 0, cercaUmbral: 1, cercaUmbralCredito: 0,
  cruzaUmbralEnCredito: 1, imagenPrincipalId: null, tieneImagen: 0,
}

export const CEL = {
  productoId: 2, codigo: 'CEL-002', sku: 'CEL-002', nombre: 'Celular Samsung A15',
  presentacion: '128 GB', descripcion: null, codigoBarras: null,
  categoriaId: 2, categoria: 'Dispositivos móviles',
  proveedorId: 2, proveedor: 'Tecnopartes S.A.S.',
  costo: 620000, existencia: 2, existenciaMinima: 5, estadoStock: 'CRITICO',
  precioContado: 744000, precioCredito: 775000,
  tratamientoIva: 'EXCLUIDO_HASTA_UMBRAL', tarifaContado: 0,
  totalContado: 744000, totalCredito: 775000,
  baseContado: 'SOBRE_COSTO', baseCredito: 'SOBRE_COSTO',
  tributacionSinConfigurar: 0, cercaUmbral: 0, cercaUmbralCredito: 0,
  cruzaUmbralEnCredito: 0, imagenPrincipalId: null, tieneImagen: 0,
}

export const MON = {
  productoId: 3, codigo: 'MON-001', sku: 'MON-001', nombre: 'Monitor LG 24"',
  presentacion: 'IPS 75 Hz', descripcion: null, codigoBarras: null,
  categoriaId: 3, categoria: 'Monitores',
  proveedorId: 2, proveedor: 'Tecnopartes S.A.S.',
  costo: 410000, existencia: 0, existenciaMinima: 2, estadoStock: 'AGOTADO',
  precioContado: 512500, precioCredito: 533000,
  tratamientoIva: 'GRAVADO', tarifaContado: 0.19,
  totalContado: 609875, totalCredito: 634270,
  baseContado: 'SOBRE_PRECIO', baseCredito: 'SOBRE_PRECIO',
  tributacionSinConfigurar: 0, cercaUmbral: 0, cercaUmbralCredito: 0,
  cruzaUmbralEnCredito: 0, imagenPrincipalId: null, tieneImagen: 0,
}

export const CATEGORIAS = [
  { id: 1, nombre: 'Computadores', descripcion: null },
  { id: 2, nombre: 'Dispositivos móviles', descripcion: null },
  { id: 3, nombre: 'Monitores', descripcion: null },
]

export const PROVEEDORES = [
  { id: 1, razonSocial: 'Distribuidora La Esperanza' },
  { id: 2, razonSocial: 'Tecnopartes S.A.S.' },
]

/** La fila de la tabla cuyo texto contiene el código dado. */
export function fila(pantalla, codigo) {
  return pantalla.getAllByRole('row')
    .find((f) => f.textContent.includes(codigo))
}
