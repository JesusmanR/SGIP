/**
 * La respuesta de `GET /api/v1/panel` para las pruebas de pantalla.
 *
 * Las cifras no están inventadas. Son las que devolvieron las cinco
 * consultas de `RepositorioPanel` sobre MySQL 8 con la cadena V1 a V31
 * aplicada y los datos de demostración, de modo que una prueba que pase
 * aquí describe un panel que la base produce de verdad. Es la misma
 * disciplina de `productos.js`, y por el mismo motivo: un fixture
 * inventado prueba que el componente dibuja lo que el fixture dice, no
 * que dibuje lo que el sistema produce.
 *
 * El escenario de los cruces de umbral sale de `verificar_panel.sh`:
 * PC-003 sube de 2.150.000 a 2.200.000 y cruza al alza, PC-002 baja de
 * 2.200.000 a 2.100.000 y cruza a la baja. Los precios son los exactos
 * que la consulta devolvió, no redondeos de cabeza.
 */

export const CRITICAS = [
  {
    productoId: 7, codigo: 'SRV-001', nombre: 'Servidor de torre',
    presentacion: null, categoria: 'Servidores',
    proveedor: 'Distribuidora Andina', contactoProveedor: '6076111111',
    existencia: 0, existenciaMinima: 1, estadoStock: 'AGOTADO',
  },
  {
    productoId: 6, codigo: 'MON-001', nombre: 'Monitor de 27 pulgadas',
    presentacion: null, categoria: 'Monitores',
    proveedor: 'Distribuidora Andina', contactoProveedor: '6076111111',
    existencia: 2, existenciaMinima: 5, estadoStock: 'CRITICO',
  },
]

export const CRUCES = [
  {
    productoId: 2, codigo: 'PC-002', nombre: 'Portátil gama media',
    costo: 2100000, precioAntes: 2640000, precioDespues: 2520000,
    umbral: 2618700, tratamientoAntes: 'GRAVADO', tratamientoDespues: 'EXCLUIDO',
  },
  {
    productoId: 3, codigo: 'PC-003', nombre: 'Portátil gama alta',
    costo: 2200000, precioAntes: 2580000, precioDespues: 2640000,
    umbral: 2618700, tratamientoAntes: 'EXCLUIDO', tratamientoDespues: 'GRAVADO',
  },
]

export const CARGA_APLICADA = {
  cargaId: 41, proveedor: 'Mayorista Tech SAS', nombreArchivo: 'lista-septiembre.csv',
  estado: 'APLICADA', fecha: '2026-09-18T09:14:00', fechaAplicacion: '2026-09-18T09:21:00',
  leidos: 412, aceptados: 398, rechazados: 14,
}

export const CIFRAS = {
  referenciasActivas: 7, altasDeLaSemana: 7,
  enExistenciaCritica: 1, agotadas: 1,
  capitalAlCosto: 84350000, capitalAPrecioVenta: 101220000,
  unidadesPendientes: 0,
}

export const MOVIMIENTOS = [
  { fecha: '2026-10-24', ingresos: 60, egresos: 0 },
]

/** El panel completo, tal como la pantalla lo recibe. */
export const PANEL = {
  cifras: CIFRAS,
  criticas: CRITICAS,
  ultimaCarga: CARGA_APLICADA,
  cruces: CRUCES,
  movimientos: MOVIMIENTOS,
}

/** Una empresa recién instalada: el primero de los tres estados. */
export const PANEL_VACIO = {
  cifras: {
    referenciasActivas: 0, altasDeLaSemana: 0,
    enExistenciaCritica: 0, agotadas: 0,
    capitalAlCosto: 0, capitalAPrecioVenta: 0, unidadesPendientes: 0,
  },
  criticas: [],
  ultimaCarga: null,
  cruces: [],
  movimientos: [],
}
