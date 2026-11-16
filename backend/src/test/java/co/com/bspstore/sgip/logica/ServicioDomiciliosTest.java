package co.com.bspstore.sgip.logica;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lo puro de las listas y del cobro de los domicilios (RF-44, RF-69): el saldo que falta por cobrar,
 * la antigüedad en días y el tope de las listas. El resto (cobrar, entregar y listar contra la base)
 * está en {@code DomiciliosConBaseTest}.
 */
class ServicioDomiciliosTest {

    /** 13:30 en UTC son las 08:30 del 12 de noviembre en Bogotá. */
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-12T13:30:45Z"), ZoneOffset.UTC);

    @Test
    void elSaldoPorCobrarEsElTotalDeLaVentaMientrasNoHayaDevoluciones() {
        // La tarea 9 (devoluciones) lo reducirá en este único método: el cobro y las listas lo usan.
        assertEquals(0, new BigDecimal("23800.00").compareTo(ServicioDomicilios.saldoPorCobrar(new BigDecimal("23800.00"))));
    }

    @Test
    void laAntiguedadSeCuentaEnDiasDeBogotaConElRelojDeLaAplicacion() {
        assertEquals(3, ServicioDomicilios.antiguedadEnDias(LocalDateTime.of(2026, 11, 9, 23, 59), RELOJ));
        assertEquals(3, ServicioDomicilios.antiguedadEnDias(LocalDateTime.of(2026, 11, 9, 0, 0), RELOJ));
        assertEquals(0, ServicioDomicilios.antiguedadEnDias(LocalDateTime.of(2026, 11, 12, 7, 0), RELOJ), "hoy");
        assertEquals(1, ServicioDomicilios.antiguedadEnDias(LocalDateTime.of(2026, 11, 11, 23, 59), RELOJ), "ayer");
        assertEquals(0, ServicioDomicilios.antiguedadEnDias(LocalDateTime.of(2026, 11, 13, 1, 0), RELOJ),
                "una fecha futura no da una antigüedad negativa");
    }

    @Test
    void elDiaDeBogotaNoEsElDeLaJvm() {
        // A las 02:00 UTC del 13 todavía es 12 en Bogotá.
        Clock madrugadaUtc = Clock.fixed(Instant.parse("2026-11-13T02:00:00Z"), ZoneOffset.UTC);

        assertEquals(0, ServicioDomicilios.antiguedadEnDias(LocalDateTime.of(2026, 11, 12, 20, 0), madrugadaUtc));
    }

    @Test
    void lasListasTienenUnTopeFijo() {
        assertEquals(200, ServicioDomicilios.TOPE_DE_LA_LISTA);
        assertTrue(ServicioDomicilios.TOPE_DE_LA_LISTA > 0);
    }
}
