package co.com.bspstore.sgip.util;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
}
