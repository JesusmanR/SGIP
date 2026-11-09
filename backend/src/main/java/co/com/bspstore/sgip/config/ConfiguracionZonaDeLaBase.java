package co.com.bspstore.sgip.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fija la zona horaria de cada sesión de MySQL en la hora de Bogotá (ADR-0030).
 *
 * Las vistas del esquema deciden la vigencia con {@code CURDATE()} y no admiten
 * parámetros ni variables, así que su «hoy» es el de la sesión. La aplicación
 * calcula el suyo con {@code Hoy.de(reloj)}, el día de Bogotá; para que los dos sean
 * el mismo día, cada conexión del pool arranca con {@code SET time_zone = '-05:00'}.
 * Colombia no tiene horario de verano, de modo que el desplazamiento fijo basta y no
 * exige tener cargadas las tablas de zonas horarias del servidor.
 *
 * Vive en código y no en el application.yml por la razón de
 * {@link ConfiguracionMigraciones}: ese archivo no se versiona, un valor que solo
 * existiera allí no llegaría a las demás máquinas ni a la CI, y equivocarse en él no
 * da error al arrancar. Así la CI prueba exactamente lo mismo que producción.
 */
@Configuration
public class ConfiguracionZonaDeLaBase {

    static final String INICIO_DE_CONEXION = "SET time_zone = '-05:00'";

    /**
     * Estático a propósito: un post-procesador se crea antes que el resto de beans, y
     * uno de instancia obligaría a crear antes de tiempo esta clase de configuración.
     */
    @Bean
    static BeanPostProcessor zonaDeLaSesion() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String nombre) {
                if (bean instanceof HikariDataSource pool) {
                    pool.setConnectionInitSql(INICIO_DE_CONEXION);
                }
                return bean;
            }
        };
    }
}
