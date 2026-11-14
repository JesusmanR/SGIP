package co.com.bspstore.sgip.modelo;

/**
 * Los documentos que se numeran por empresa (tabla {@code consecutivos}, V44). Coincide con el
 * ENUM de la columna {@code consecutivos.tipo}.
 */
public enum TipoConsecutivo {
    VENTA,
    DEVOLUCION,
    PRODUCTO_RAPIDO
}
