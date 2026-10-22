package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Una referencia que cambiaría de tratamiento tributario si se aplicara
 * una regla (RF-35).
 *
 * Lleva el precio anterior y el nuevo porque el requisito los pide
 * —«lista las referencias que cambiaron de tratamiento con el precio
 * anterior y el nuevo»— y porque sin ellos el aviso no sirve para
 * decidir: lo que una empresa necesita saber es si el salto compensa.
 *
 * `umbral` viene en pesos y no en unidades de valor tributario: quien
 * mira la pantalla compara precios, no múltiplos de la UVT.
 */
public record CruceDeUmbral(
        Integer productoId,
        String codigo,
        String nombre,
        BigDecimal costo,
        Integer precioAntes,
        Integer precioDespues,
        BigDecimal umbral,
        String tratamientoAntes,
        String tratamientoDespues
) {}
