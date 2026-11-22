package co.com.bspstore.sgip.modelo;

/**
 * Los documentos que se numeran por empresa (tabla {@code consecutivos}, V44). Coincide con el
 * ENUM de la columna {@code consecutivos.tipo}.
 *
 * {@code PRODUCTO_RAPIDO} quedó sin uso cuando se retiró el producto nuevo dentro de la venta: el valor
 * sigue en el ENUM de la V44 (no se edita una migración) y aquí, para que ambos coincidan.
 */
public enum TipoConsecutivo {
    VENTA,
    DEVOLUCION,
    PRODUCTO_RAPIDO
}
