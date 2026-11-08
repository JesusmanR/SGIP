package co.com.bspstore.sgip.api.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La columna {@code costo} es DECIMAL(14,2). Sin un tope en la solicitud, un
 * costo fuera de rango llegaba al motor y volvía como un error genérico; con
 * él llega como un 400 que dice el motivo, igual que en la orden de compra.
 */
class CostoDeSolicitudTest {

    private static final Validator VALIDADOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final String MENSAJE = "El costo admite hasta doce enteros y dos decimales";

    private static SolicitudProducto alta(BigDecimal costo) {
        return new SolicitudProducto(1, 1, "COD", null, null, "Tornillo", null, null,
                costo, 0, 0, null, null);
    }

    private static SolicitudEdicionProducto edicion(BigDecimal costo) {
        return new SolicitudEdicionProducto(1, 1, "COD", null, null, "Tornillo", null, null,
                costo, 0, null, null, null, 1);
    }

    private static Set<String> mensajes(Set<? extends ConstraintViolation<?>> violaciones) {
        return violaciones.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void elAltaRechazaUnCostoConMasDeDoceEnteros() {
        var m = mensajes(VALIDADOR.validate(alta(new BigDecimal("1000000000000.00"))));

        assertTrue(m.contains(MENSAJE), m.toString());
    }

    @Test
    void elAltaRechazaUnCostoConMasDeDosDecimales() {
        var m = mensajes(VALIDADOR.validate(alta(new BigDecimal("10.005"))));

        assertTrue(m.contains(MENSAJE), m.toString());
    }

    @Test
    void elAltaAceptaElMayorCostoQueCabeEnLaColumna() {
        assertEquals(Set.of(), mensajes(VALIDADOR.validate(alta(new BigDecimal("999999999999.99")))));
    }

    @Test
    void laEdicionRechazaUnCostoConMasDeDoceEnteros() {
        var m = mensajes(VALIDADOR.validate(edicion(new BigDecimal("1000000000000.00"))));

        assertTrue(m.contains(MENSAJE), m.toString());
    }

    @Test
    void laEdicionRechazaUnCostoConMasDeDosDecimales() {
        var m = mensajes(VALIDADOR.validate(edicion(new BigDecimal("10.005"))));

        assertTrue(m.contains(MENSAJE), m.toString());
    }

    @Test
    void laEdicionAceptaElMayorCostoQueCabeEnLaColumna() {
        assertEquals(Set.of(), mensajes(VALIDADOR.validate(edicion(new BigDecimal("999999999999.99")))));
    }
}
