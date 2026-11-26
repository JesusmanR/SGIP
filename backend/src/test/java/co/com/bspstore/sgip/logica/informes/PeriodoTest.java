package co.com.bspstore.sgip.logica.informes;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** El período de un informe: fechas inclusivas, consultadas como un intervalo semiabierto. */
class PeriodoTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 26);
    private static final LocalDate PRIMERO_DEL_MES = LocalDate.of(2026, 11, 1);

    private static String mensaje(String desde, String hasta) {
        return assertThrows(PeriodoInvalidoException.class,
                () -> Periodo.de(desde, hasta, PRIMERO_DEL_MES, HOY)).getMessage();
    }

    @Test
    void porOmisionVaDelDiaPorOmisionAHoy() {
        Periodo p = Periodo.de(null, null, PRIMERO_DEL_MES, HOY);

        assertEquals(PRIMERO_DEL_MES, p.desde());
        assertEquals(HOY, p.hasta());
    }

    @Test
    void unTextoEnBlancoEsComoNoPonerlo() {
        Periodo p = Periodo.de("  ", "", PRIMERO_DEL_MES, HOY);

        assertEquals(new Periodo(PRIMERO_DEL_MES, HOY), p);
    }

    @Test
    void lasFechasDadasGanan() {
        Periodo p = Periodo.de("2026-10-05", "2026-10-20", PRIMERO_DEL_MES, HOY);

        assertEquals(LocalDate.of(2026, 10, 5), p.desde());
        assertEquals(LocalDate.of(2026, 10, 20), p.hasta());
    }

    @Test
    void invertidoDa400ConSuTexto() {
        assertEquals("La fecha inicial no puede ser posterior a la final.",
                mensaje("2026-11-20", "2026-11-10"));
    }

    @Test
    void unaSolaFechaDadaSeCompletaConLaOtra() {
        // Solo `desde` en el futuro: el `hasta` por omisión es hoy, y queda invertido.
        assertEquals("La fecha inicial no puede ser posterior a la final.",
                mensaje("2026-12-01", null));
    }

    @Test
    void masDe366Dias() {
        assertEquals("Elige un período de máximo un año.", mensaje("2026-01-01", "2027-01-02"));
    }

    @Test
    void trescientosSesentaYSeisDiasSiSePermite() {
        // 366 días contando los dos extremos.
        Periodo p = Periodo.de("2026-01-01", "2027-01-01", PRIMERO_DEL_MES, HOY);

        assertEquals(LocalDate.of(2027, 1, 1), p.hasta());
    }

    @Test
    void unaFechaFuturaSeAcepta() {
        Periodo p = Periodo.de("2026-12-01", "2026-12-31", PRIMERO_DEL_MES, HOY);

        assertEquals(LocalDate.of(2026, 12, 31), p.hasta());
    }

    @Test
    void fechaMalEscrita() {
        for (String mala : new String[] {"26/11/2026", "2026-13-01", "2026-02-30", "ayer", "2026-1-5",
                " 2026-11-01", "2026-11-01 ", "20261101", "+12026-01-01", "-0001-01-01"}) {
            assertEquals("La fecha no es válida.", mensaje(mala, "2026-11-26"), "desde: " + mala);
            assertEquals("La fecha no es válida.", mensaje("2026-11-01", mala), "hasta: " + mala);
        }
    }

    @Test
    void unDiaLlegaHastaLaMedianocheSiguiente() {
        Periodo p = Periodo.de("2026-11-26", "2026-11-26", PRIMERO_DEL_MES, HOY);

        assertEquals(LocalDateTime.of(2026, 11, 26, 0, 0), p.inicio());
        assertEquals(LocalDateTime.of(2026, 11, 27, 0, 0), p.fin());
    }

    @Test
    void elFinDelAnoPasaAlAnoSiguiente() {
        Periodo p = new Periodo(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));

        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), p.fin());
    }
}
