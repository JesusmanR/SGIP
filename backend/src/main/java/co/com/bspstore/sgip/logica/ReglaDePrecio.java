package co.com.bspstore.sgip.logica;

import java.math.BigDecimal;

/**
 * Lo que una regla dice sobre el precio: un factor o una cifra, nunca los dos
 * (`ck_reglas_margen_factor_o_precio`). Es lo que hay que comparar para saber
 * si un cambio de precio cambió algo.
 */
public record ReglaDePrecio(BigDecimal factor, BigDecimal precioFijo, String formaCalculo) {}
