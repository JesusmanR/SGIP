package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.util.Hoy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Las vistas del esquema deciden la vigencia con {@code CURDATE()} y no admiten
 * parámetros, de modo que la sesión de la base va en la hora de Bogotá para que su
 * «hoy» sea el de la aplicación (ADR-0030). Lo fija la aplicación al abrir cada
 * conexión, en Java y no en un archivo de configuración que no se versiona, así que
 * la CI prueba exactamente lo mismo que producción.
 */
@Tag("conBase")
@SpringBootTest
class ZonaDeLaSesionConBaseTest {

    @Autowired JdbcClient jdbc;

    @Test
    void laSesionDeLaBaseVaEnLaHoraDeBogota() {
        String zona = jdbc.sql("SELECT @@session.time_zone").query(String.class).single();

        assertEquals("-05:00", zona);
    }

    @Test
    void elCurdateDeLaBaseEsElDiaDeBogota() {
        LocalDate enLaBase = jdbc.sql("SELECT CURDATE()").query(LocalDate.class).single();

        assertEquals(Hoy.de(Clock.systemUTC()), enLaBase);
    }
}
