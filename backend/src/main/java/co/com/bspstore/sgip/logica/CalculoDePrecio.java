package co.com.bspstore.sgip.logica;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * El porcentaje de ganancia visto como cuenta: qué factor le corresponde y a
 * qué precio llega.
 *
 * El precio de verdad lo sigue dando `v_precios_producto` en cada lectura. Esta
 * cuenta existe porque hay que saber antes de guardar si un porcentaje deja el
 * precio en cero pesos, y tiene que dar exactamente lo mismo que la vista:
 * `ROUND` sobre un DECIMAL se aleja del cero en la mitad, igual que `HALF_UP`.
 * Lo vigila la tabla `database/muestras/casos_de_precio.csv`, que leen también
 * `verificar_precios.sh` y `web/src/servicios/precio.prueba.js`.
 */
public final class CalculoDePrecio {

    private static final BigDecimal CIEN = new BigDecimal("100");

    private CalculoDePrecio() {}

    /** Uno más el porcentaje, con los cuatro decimales de `reglas_margen.factor`. */
    public static BigDecimal factorDesdePorcentaje(BigDecimal porcentaje) {
        return BigDecimal.ONE.add(porcentaje.movePointLeft(2)).setScale(4, RoundingMode.UNNECESSARY);
    }

    /** El porcentaje se aplica sobre el costo y el precio se redondea al peso. */
    public static BigDecimal precioDesdePorcentaje(BigDecimal costo, BigDecimal porcentaje) {
        return costo.multiply(CIEN.add(porcentaje))
                .movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP);
    }
}
