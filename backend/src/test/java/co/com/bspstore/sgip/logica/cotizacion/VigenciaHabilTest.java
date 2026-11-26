package co.com.bspstore.sgip.logica.cotizacion;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * La vigencia de una cotización se cuenta en días hábiles (RF-37): desde el día siguiente a la
 * emisión, sin sábados, domingos ni festivos.
 */
class VigenciaHabilTest {

    private static final Set<LocalDate> SIN_FESTIVOS = Set.of();

    @Test
    void elEjemploDeRf37() {
        // Miércoles 1 de abril de 2026; jueves 2 y viernes 3 son Semana Santa.
        var festivos = Set.of(LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 3));

        assertEquals(LocalDate.of(2026, 4, 10), VigenciaHabil.vence(LocalDate.of(2026, 4, 1), 5, festivos));
    }

    @Test
    void emitidaUnViernesSaltaElFinDeSemana() {
        assertEquals(LocalDate.of(2026, 11, 30),
                VigenciaHabil.vence(LocalDate.of(2026, 11, 27), 1, SIN_FESTIVOS));
    }

    @Test
    void emitidaUnSabado() {
        assertEquals(LocalDate.of(2026, 11, 30),
                VigenciaHabil.vence(LocalDate.of(2026, 11, 28), 1, SIN_FESTIVOS));
    }

    @Test
    void emitidaUnDomingo() {
        assertEquals(LocalDate.of(2026, 11, 30),
                VigenciaHabil.vence(LocalDate.of(2026, 11, 29), 1, SIN_FESTIVOS));
    }

    @Test
    void sinFinesDeSemanaNiFestivosCuentaDiaPorDia() {
        // Lunes 23 de noviembre de 2026 más tres hábiles: jueves 26.
        assertEquals(LocalDate.of(2026, 11, 26),
                VigenciaHabil.vence(LocalDate.of(2026, 11, 23), 3, SIN_FESTIVOS));
    }

    @Test
    void cincoDiasHabilesCruzanUnFinDeSemanaCompleto() {
        // Miércoles 25 de noviembre de 2026: jue 26, vie 27, lun 30, mar 1, mié 2 de diciembre.
        assertEquals(LocalDate.of(2026, 12, 2),
                VigenciaHabil.vence(LocalDate.of(2026, 11, 25), 5, SIN_FESTIVOS));
    }

    @Test
    void unFestivoEnUnDiaDeSemanaNoSeCuenta() {
        // Lunes 30 de noviembre festivo: el viernes 27 más un día hábil es el martes 1 de diciembre.
        assertEquals(LocalDate.of(2026, 12, 1),
                VigenciaHabil.vence(LocalDate.of(2026, 11, 27), 1, Set.of(LocalDate.of(2026, 11, 30))));
    }

    @Test
    void unFestivoEnFinDeSemanaNoSaltaUnDiaDeMas() {
        // El sábado 28 aparece como festivo, pero ya estaba fuera de la cuenta.
        assertEquals(LocalDate.of(2026, 11, 30),
                VigenciaHabil.vence(LocalDate.of(2026, 11, 27), 1, Set.of(LocalDate.of(2026, 11, 28))));
    }

    @Test
    void laEmisionEnUnFestivoCuentaDesdeElDiaSiguiente() {
        // Lunes 30 de noviembre festivo y cotización emitida ese día: un hábil es el martes.
        assertEquals(LocalDate.of(2026, 12, 1),
                VigenciaHabil.vence(LocalDate.of(2026, 11, 30), 1, Set.of(LocalDate.of(2026, 11, 30))));
    }

    @Test
    void ceroDiasEsInvalido() {
        assertThrows(IllegalArgumentException.class,
                () -> VigenciaHabil.vence(LocalDate.of(2026, 11, 25), 0, SIN_FESTIVOS));
    }

    @Test
    void unNumeroNegativoDeDiasEsInvalido() {
        assertThrows(IllegalArgumentException.class,
                () -> VigenciaHabil.vence(LocalDate.of(2026, 11, 25), -3, SIN_FESTIVOS));
    }
}
