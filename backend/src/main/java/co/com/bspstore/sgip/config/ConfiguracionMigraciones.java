package co.com.bspstore.sgip.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway gobierna las migraciones desde la V32 (ADR-0023).
 *
 * Las V1 a V31 quedan fuera: las aplica {@code database/aplicar.sh}, con
 * un orden escrito a mano y con la V8 de datos de demostración en medio
 * de la cadena, algo que Flyway no sabe expresar. Por eso Flyway no
 * parte de cero sino de un punto de partida: sobre una base que ya tiene
 * esquema pero ningún historial, registra la V31 como línea base y aplica
 * solo lo posterior. Sobre una base vacía no hace nada hasta que
 * {@code aplicar.sh} la haya poblado.
 *
 * Esto vive en código y no en el application.yml porque ese archivo no
 * se versiona: un valor que solo existiera allí no llegaría a las demás
 * máquinas con un «git pull», y equivocarse en uno no da error al
 * arrancar. Es la misma razón de {@link ConfiguracionSubidas}. Spring
 * Boot aplica los personalizadores después de leer las propiedades, así
 * que estos valores mandan sobre cualquier yml antiguo.
 */
@Configuration
public class ConfiguracionMigraciones {

    /** Última migración que aplica {@code aplicar.sh}; Flyway empieza después. */
    static final String VERSION_BASE = "31";

    /**
     * La carpeta puede no existir o estar vacía mientras no haya una V32:
     * Flyway lo tolera por omisión ({@code failOnMissingLocations} en
     * falso) y no se cambia aquí, para que arrancar sin migraciones
     * pendientes no sea un error.
     */
    static final String UBICACION = "classpath:db/migration";

    @Bean
    FlywayConfigurationCustomizer personalizacionDeFlyway() {
        return configuracion -> configuracion
                .baselineOnMigrate(true)
                .baselineVersion(VERSION_BASE)
                .locations(UBICACION);
    }
}
