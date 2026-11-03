package co.com.bspstore.sgip.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Las V1 a V31 las aplica {@code database/aplicar.sh}; Flyway toma el
 * relevo desde la V32. Esta prueba fija los tres valores de los que
 * depende ese relevo, porque equivocarse en uno no da error al arrancar:
 * o se vuelven a ejecutar las V1 a V31 sobre una base que ya las tiene, o
 * se ignora la carpeta donde viven las nuevas.
 */
class ConfiguracionMigracionesTest {

    private FluentConfiguration configurada() {
        // Se parte de valores distintos de los esperados: si Flyway ya
        // trajera de fábrica «classpath:db/migration», la afirmación
        // sobre las ubicaciones pasaría sin que el customizer hiciera nada.
        FluentConfiguration c = Flyway.configure()
                .baselineOnMigrate(false)
                .baselineVersion("1")
                .locations("classpath:otra/carpeta");
        new ConfiguracionMigraciones().personalizacionDeFlyway().customize(c);
        return c;
    }

    @Test
    void sobreUnaBaseConEsquemaPeroSinHistorialSeTomaComoPuntoDePartida() {
        assertTrue(configurada().isBaselineOnMigrate());
    }

    @Test
    void elPuntoDePartidaEsLaV31PorqueLoAnteriorLoAplicaAplicarSh() {
        assertEquals("31", configurada().getBaselineVersion().getVersion());
    }

    @Test
    void lasMigracionesNuevasSeBuscanEnDbMigration() {
        var ubicaciones = configurada().getLocations();
        assertEquals(1, ubicaciones.length);
        assertEquals("classpath:db/migration", ubicaciones[0].getDescriptor());
    }
}
