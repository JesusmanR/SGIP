package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * El inventario valorizado (RF-57): los totales de lo que hay en bodega, la sección aparte de lo vendido sin
 * existencia y una página de la tabla.
 *
 * Los totales y la tabla salen de la misma lectura (una sola transacción), de modo que la pantalla no
 * muestra dos verdades. Los totales cubren todo lo que pasa el filtro y no solo la página.
 *
 * @param totales    las cifras de arriba
 * @param filas      la página pedida, de mayor a menor valor al costo
 * @param totalFilas cuántas referencias tiene la tabla completa (existencia distinta de cero)
 */
public record InformeInventario(Totales totales, List<Fila> filas, long totalFilas) {

    /**
     * Las cifras de arriba, en dos grupos que no se mezclan.
     *
     * <b>En bodega</b> (existencia mayor que cero): {@code referencias}, {@code unidades},
     * {@code valorAlCosto}, {@code valorAPrecio} (de contado, solo de las referencias que tienen precio) y
     * {@code sinPrecio}, cuántas referencias en bodega no tienen precio y por eso no entran en
     * {@code valorAPrecio}: las que no tienen regla y las inactivas.
     *
     * <b>Vendido sin existencia</b> (existencia menor que cero): {@code referenciasNegativas},
     * {@code unidadesBajoCero} (la suma de −existencia) y {@code valorPendienteAlCosto}. Es la misma cifra
     * que muestra el Panel y nunca se resta de lo de bodega.
     */
    public record Totales(long referencias, long unidades, BigDecimal valorAlCosto, BigDecimal valorAPrecio,
                          long sinPrecio, long referenciasNegativas, long unidadesBajoCero,
                          BigDecimal valorPendienteAlCosto) {
    }

    /**
     * Una referencia de la tabla. {@code precio} y {@code valorAPrecio} son nulos si no tiene regla de precio
     * o si está inactiva. Con existencia negativa {@code valorAlCosto} es cero: la deuda va en los totales,
     * no en el valor (ADR-0019).
     */
    public record Fila(Integer productoId, String codigo, String nombre, String categoria, boolean activo,
                       int existencia, BigDecimal costo, BigDecimal precio, BigDecimal valorAlCosto,
                       BigDecimal valorAPrecio) {
    }
}
