package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una regla de ganancia de la empresa (RF-30).
 *
 * `categoria` y `proveedor` vienen con su nombre además de su
 * identificador, y los dos admiten nulo: una regla sin categoría ni
 * proveedor es la general de la empresa. El nombre se trae para que la
 * pantalla pueda decir «Computadores · Mayorista Tech» sin otra
 * consulta, que es como se lee el alcance de una regla.
 *
 * `especificidad` es cuántos de los dos acota —cero, uno o dos— y es lo
 * que decide cuál gana ante reglas concurrentes (RN-03). Viene calculada
 * para que la pantalla pueda ordenarlas como el motor las ordena, en
 * lugar de deducirlo mirando qué columnas están vacías.
 */
public record ReglaDeMargen(
        Integer reglaId,
        Integer categoriaId,
        String categoria,
        Integer proveedorId,
        String proveedor,
        String formaPago,
        String formaCalculo,
        BigDecimal factor,
        LocalDate vigenteDesde,
        LocalDate vigenteHasta,
        int especificidad,
        boolean vigente
) {}
