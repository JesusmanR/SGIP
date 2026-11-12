package co.com.bspstore.sgip.util;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HoyTest {

    /** Las 10 de la noche del 19 de octubre en Bogotá son las 3 de la madrugada del 20 en UTC. */
    private static final Instant DIEZ_DE_LA_NOCHE_EN_BOGOTA = Instant.parse("2026-10-20T03:00:00Z");

    @Test
    void hoyEsElDiaDeBogotaAunqueElRelojVayaEnUtc() {
        Clock utc = Clock.fixed(DIEZ_DE_LA_NOCHE_EN_BOGOTA, ZoneOffset.UTC);

        assertEquals(LocalDate.of(2026, 10, 19), Hoy.de(utc));
    }

    @Test
    void hoyEsElDiaDeBogotaAunqueElRelojVayaEnUnaZonaAdelantada() {
        Clock tokio = Clock.fixed(DIEZ_DE_LA_NOCHE_EN_BOGOTA, ZoneId.of("Asia/Tokyo"));

        assertEquals(LocalDate.of(2026, 10, 19), Hoy.de(tokio));
    }

    @Test
    void despuesDeLaMedianocheDeBogotaYaEsElDiaSiguiente() {
        Clock utc = Clock.fixed(Instant.parse("2026-10-20T05:00:00Z"), ZoneOffset.UTC);

        assertEquals(LocalDate.of(2026, 10, 20), Hoy.de(utc));
    }

    @Test
    void ahoraEsLaHoraDePareDeBogotaAunqueElRelojVayaEnUtc() {
        Clock utc = Clock.fixed(DIEZ_DE_LA_NOCHE_EN_BOGOTA, ZoneOffset.UTC);

        assertEquals(LocalDateTime.of(2026, 10, 19, 22, 0, 0), Hoy.ahora(utc));
    }

    @Test
    void ahoraEsLaHoraDePareDeBogotaAunqueElRelojVayaEnUnaZonaAdelantada() {
        Clock tokio = Clock.fixed(DIEZ_DE_LA_NOCHE_EN_BOGOTA, ZoneId.of("Asia/Tokyo"));

        assertEquals(LocalDateTime.of(2026, 10, 19, 22, 0, 0), Hoy.ahora(tokio));
    }

    @Test
    void ahoraSeTruncaAlSegundoPorqueLaColumnaNoGuardaFraccion() {
        Clock conFraccion = Clock.fixed(Instant.parse("2026-10-20T03:00:00.987654321Z"), ZoneOffset.UTC);

        assertEquals(LocalDateTime.of(2026, 10, 19, 22, 0, 0), Hoy.ahora(conFraccion));
    }

    @Test
    void ahoraYHoyCaenEnElMismoDia() {
        Clock casiMedianoche = Clock.fixed(Instant.parse("2026-10-20T04:59:59Z"), ZoneOffset.UTC);

        assertEquals(Hoy.de(casiMedianoche), Hoy.ahora(casiMedianoche).toLocalDate());
        assertEquals(LocalDateTime.of(2026, 10, 19, 23, 59, 59), Hoy.ahora(casiMedianoche));
    }
}
