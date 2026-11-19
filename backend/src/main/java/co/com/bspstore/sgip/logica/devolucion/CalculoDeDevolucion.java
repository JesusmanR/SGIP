package co.com.bspstore.sgip.logica.devolucion;

import co.com.bspstore.sgip.api.dto.UnidadDevuelta;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que vale devolver unidades de una venta (RF-68), sin base de datos. Firma mínima.
 */
public final class CalculoDeDevolucion {

    /** Una línea de la venta con lo que ya se devolvió de ella. */
    public record LineaVendida(Integer productoId, int cantidad, BigDecimal total, int yaDevueltas,
                               BigDecimal yaDevuelto) {}

    /** Las unidades de un producto que se devuelven ahora y lo que valen. */
    public record UnidadesDevueltas(Integer productoId, int cantidad, BigDecimal valor) {}

    /** Lo que se devuelve ahora, lo que vale y cuántas unidades de la venta siguen sin devolver. */
    public record Resultado(List<UnidadesDevueltas> unidades, BigDecimal valor, int unidadesQueQuedan) {}

    private CalculoDeDevolucion() {}

    public static BigDecimal valorDe(LineaVendida linea, int unidades) {
        throw new UnsupportedOperationException("pendiente");
    }

    public static Resultado calcular(List<LineaVendida> lineas, List<UnidadDevuelta> pedidas) {
        throw new UnsupportedOperationException("pendiente");
    }
}
