package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * El valor de la unidad de valor tributario de un año (RF-05).
 *
 * Es el dato del que cuelgan todos los umbrales del Art. 424, y por eso
 * se guarda por año en lugar de como una cifra única: el ADR-0004 decidió
 * que una factura vieja se reconstruye con el valor que regía entonces,
 * y {@code fn_valor_uvt(fecha)} recibe una fecha justamente para eso.
 */
public record ValorUvt(
        Integer anio,
        BigDecimal valor,
        String resolucion
) {}
