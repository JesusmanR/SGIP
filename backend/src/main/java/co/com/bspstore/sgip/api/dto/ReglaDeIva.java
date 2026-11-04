package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una regla de IVA de la empresa.
 *
 * {@code categoria} y {@code producto} traen el nombre además del
 * identificador, y los dos admiten nulo: sin ninguno es la regla general.
 * {@code tratamiento} y {@code tarifa} son los del tipo del catálogo, para
 * que la pantalla diga «IVA tarifa general (19 %)» sin otra consulta; lo que
 * se cobra de verdad lo decide {@code v_regla_iva}, que además mira si la
 * empresa es responsable de IVA.
 *
 * {@code especificidad} ordena como el motor elige (RN-22): 2 para un
 * producto, 1 para una categoría, 0 para la general.
 */
public record ReglaDeIva(
        Integer reglaId,
        Integer categoriaId,
        String categoria,
        Integer productoId,
        String producto,
        String codigoImpuesto,
        String impuesto,
        String tratamiento,
        BigDecimal tarifa,
        LocalDate vigenteDesde,
        LocalDate vigenteHasta,
        String fundamento,
        int especificidad,
        boolean vigente
) {}
