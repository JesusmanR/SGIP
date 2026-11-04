package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Lo que se escribió para el precio de una forma de pago (ADR-0025).
 *
 * Tiene que venir exactamente uno de los dos: el que escribió quien usa la
 * pantalla. No es una formalidad. El que se manda decide qué se conserva
 * cuando el costo suba: con el porcentaje cambia el precio, y con el precio
 * cambia el margen. Mandar los dos dejaría a la lógica adivinando cuál de los
 * dos era el dato y cuál la cuenta.
 */
public record SolicitudPrecio(BigDecimal porcentaje, BigDecimal precio) {}
