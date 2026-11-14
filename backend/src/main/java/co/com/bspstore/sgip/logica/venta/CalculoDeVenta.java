package co.com.bspstore.sgip.logica.venta;

import java.math.BigDecimal;
import java.util.List;

/** Firmas mínimas para que la prueba compile; la lógica llega en el commit siguiente. */
public final class CalculoDeVenta {

    private CalculoDeVenta() {}

    public static VentaCalculada calcular(List<LineaPedida> lineas, Descuento descuentoTotal) {
        throw new UnsupportedOperationException("pendiente");
    }

    public static BigDecimal cambio(BigDecimal totalEfectivo, BigDecimal recibido) {
        throw new UnsupportedOperationException("pendiente");
    }
}
