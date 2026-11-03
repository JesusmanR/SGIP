package co.com.bspstore.sgip.api.dto;

/**
 * La existencia de una referencia frente a la suma de sus movimientos
 * (RF-49): si coinciden, el kárdex explica todo el saldo.
 *
 * `existencia` es la de la tabla de productos y no la del último
 * movimiento: la comprobación sirve justamente para descubrir que esas
 * dos cifras discrepan.
 */
public record CuadreDeInventario(Integer productoId, int existencia, int sumaDeMovimientos,
                                 boolean cuadra) {}
