package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La prueba gratuita (RF-04, RF-73, ADR-0031): cuándo vence, si todavía escribe
 * y cuánto le queda, siempre en la hora de Bogotá.
 *
 * Los instantes se escriben con su desplazamiento (-05:00) para que se lean como
 * los lee el requisito, y no dependen de la zona de la máquina que corre la prueba.
 */
class VigenciaDePruebaTest {

    private static final LocalDate FIN = LocalDate.of(2026, 2, 28);

    private static Instant en(String bogota) {
        return OffsetDateTime.parse(bogota).toInstant();
    }

    // ------------------------------------------------------------ RF-04

    /** Los siete casos del RF-04, tal como los escribe el requisito. */
    @ParameterizedTest(name = "alta el {0}, vence el {1}")
    @CsvSource({
            "2026-01-29, 2026-02-28",
            "2026-01-30, 2026-03-01",
            "2026-01-31, 2026-03-02",
            "2028-01-30, 2028-02-29",
            "2028-01-31, 2028-03-01",
            "2026-03-15, 2026-04-14",
            "2026-03-31, 2026-04-30"})
    void treintaDiasContadosDesdeElDiaSiguienteAlAlta(LocalDate alta, LocalDate vence) {
        assertEquals(vence, VigenciaDePrueba.finDePrueba(alta, 30));
    }

    @Test
    void venceALas235959DeBogotaDelUltimoDia() {
        assertEquals(OffsetDateTime.parse("2026-02-28T23:59:59-05:00"), VigenciaDePrueba.venceEn(FIN));
    }

    // ------------------------------------------------- vigencia efectiva

    @Test
    void elUltimoDiaALas235959TodaviaEscribe() {
        assertTrue(VigenciaDePrueba.permiteEscritura(EstadoEmpresa.PRUEBA, FIN,
                en("2026-02-28T23:59:59-05:00")));
    }

    @Test
    void unSegundoDespuesYaNoEscribe() {
        assertFalse(VigenciaDePrueba.permiteEscritura(EstadoEmpresa.PRUEBA, FIN,
                en("2026-03-01T00:00:00-05:00")));
    }

    /** En UTC ya es 1 de marzo, pero en Bogotá sigue siendo el último día. */
    @Test
    void decideConElDiaDeBogotaYNoConElDeUtc() {
        assertTrue(VigenciaDePrueba.permiteEscritura(EstadoEmpresa.PRUEBA, FIN,
                Instant.parse("2026-03-01T04:59:59Z")));
        assertFalse(VigenciaDePrueba.permiteEscritura(EstadoEmpresa.PRUEBA, FIN,
                Instant.parse("2026-03-01T05:00:00Z")));
    }

    @Test
    void unaPruebaSinFechaDeFinNoEscribe() {
        assertFalse(VigenciaDePrueba.permiteEscritura(EstadoEmpresa.PRUEBA, null,
                en("2026-02-01T10:00:00-05:00")));
    }

    @Test
    void laActivaEscribeYLaDesactivadaNoSinMirarLaFecha() {
        Instant cualquiera = en("2030-01-01T10:00:00-05:00");
        assertTrue(VigenciaDePrueba.permiteEscritura(EstadoEmpresa.ACTIVA, null, cualquiera));
        assertTrue(VigenciaDePrueba.permiteEscritura(EstadoEmpresa.ACTIVA, FIN, cualquiera));
        assertFalse(VigenciaDePrueba.permiteEscritura(EstadoEmpresa.DESACTIVADA, null, cualquiera));
    }

    // --------------------------------------------------- tiempo restante

    @Test
    void aLas235959TodaviaQuedaUnSegundo() {
        assertEquals(1, VigenciaDePrueba.segundosRestantes(FIN, en("2026-02-28T23:59:59-05:00")));
    }

    @Test
    void losSegundosSeCuentanEnterosHaciaAbajo() {
        assertEquals(0, VigenciaDePrueba.segundosRestantes(FIN,
                en("2026-02-28T23:59:59.999-05:00")));
        assertEquals(18_000, VigenciaDePrueba.segundosRestantes(FIN,
                en("2026-02-28T19:00:00-05:00")));
    }

    @Test
    void despuesDelVencimientoNoHaySegundosNegativos() {
        assertEquals(0, VigenciaDePrueba.segundosRestantes(FIN, en("2026-03-05T10:00:00-05:00")));
    }

    // -------------------------------------------- la situación completa

    @Test
    void enPruebaVigenteDiceCuandoVenceYCuantoQueda() {
        SituacionDePrueba s = VigenciaDePrueba.situacion(EstadoEmpresa.PRUEBA, FIN,
                en("2026-02-18T00:00:00-05:00"));

        assertEquals(SituacionDePrueba.Estado.PRUEBA, s.estado());
        assertEquals(OffsetDateTime.parse("2026-02-28T23:59:59-05:00"), s.venceEn());
        assertEquals(11L * 86_400, s.segundosRestantes());
    }

    @Test
    void vencidaConservaLaFechaYQuedanCeroSegundos() {
        SituacionDePrueba s = VigenciaDePrueba.situacion(EstadoEmpresa.PRUEBA, FIN,
                en("2026-03-01T00:00:01-05:00"));

        assertEquals(SituacionDePrueba.Estado.PRUEBA_VENCIDA, s.estado());
        assertEquals(OffsetDateTime.parse("2026-02-28T23:59:59-05:00"), s.venceEn());
        assertEquals(0L, s.segundosRestantes());
    }

    @Test
    void activaYDesactivadaNoTienenVencimiento() {
        Instant ahora = en("2026-02-18T00:00:00-05:00");
        for (EstadoEmpresa estado : new EstadoEmpresa[] {EstadoEmpresa.ACTIVA, EstadoEmpresa.DESACTIVADA}) {
            SituacionDePrueba s = VigenciaDePrueba.situacion(estado, FIN, ahora);
            assertEquals(estado.name(), s.estado().name());
            assertNull(s.venceEn());
            assertNull(s.segundosRestantes());
        }
    }
}
