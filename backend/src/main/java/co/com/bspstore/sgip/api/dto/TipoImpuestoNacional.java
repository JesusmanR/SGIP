package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un tipo de impuesto del catálogo nacional (RF-05).
 *
 * Es la capa que el ADR-0016 puso en manos de la plataforma: el
 * tratamiento, la tarifa y el umbral son los mismos para todo el país, y
 * lo que cada comercio declara —si es responsable del impuesto, si
 * aplica la exclusión por umbral— vive en `empresa_config`.
 *
 * Trae `vigenteDesde` y `vigenteHasta` porque las columnas existen, y
 * conviene saber que hoy no guardan historia: la clave única es por
 * código, de modo que no caben dos vigencias del mismo tipo. Está
 * explicado en `database/README.md`. Se exponen para que la pantalla
 * pueda mostrar desde cuándo rige lo que está viendo.
 */
public record TipoImpuestoNacional(
        Integer tipoImpuestoId,
        String codigo,
        String nombre,
        String tratamiento,
        BigDecimal tarifa,
        BigDecimal umbralUvt,
        String fundamento,
        LocalDate vigenteDesde,
        LocalDate vigenteHasta,
        boolean activo
) {}
