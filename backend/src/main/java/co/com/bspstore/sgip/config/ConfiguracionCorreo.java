package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.logica.correo.CorreoEnRegistro;
import co.com.bspstore.sgip.logica.correo.CorreoSmtp;
import co.com.bspstore.sgip.logica.correo.EnviadorDeCorreo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Elige cómo salen los correos (ADR-0036): {@code sgip.correo.modo}.
 *
 * <ul>
 *   <li>{@code registro} (por omisión): se escribe en el log. Si el perfil activo
 *       incluye {@code prod}, la aplicación no arranca.</li>
 *   <li>{@code smtp}: se envía con el {@link JavaMailSender} que arma Spring Boot
 *       con {@code SPRING_MAIL_*}; el remitente va en {@code SGIP_CORREO_REMITENTE}.
 *       Aquí se exige STARTTLS y se acotan los tiempos de espera a 10 s.</li>
 * </ul>
 *
 * Un modo que no sea ninguno de los dos deja la aplicación sin
 * {@link EnviadorDeCorreo}, de modo que quien lo necesite no arranca, en lugar de
 * caer en silencio al registro.
 */
@Configuration
public class ConfiguracionCorreo {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionCorreo.class);

    private static final String TIEMPO_DE_ESPERA_MS = "10000";

    @Bean
    @ConditionalOnProperty(name = "sgip.correo.modo", havingValue = "registro", matchIfMissing = true)
    EnviadorDeCorreo correoEnRegistro(Environment entorno) {
        if (entorno.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("El modo de correo «registro» no se permite en producción.");
        }
        log.warn("Los correos no salen: modo registro.");
        return new CorreoEnRegistro();
    }

    @Bean
    @ConditionalOnProperty(name = "sgip.correo.modo", havingValue = "smtp")
    EnviadorDeCorreo correoSmtp(JavaMailSender emisor, @Value("${SGIP_CORREO_REMITENTE}") String remitente) {
        if (emisor instanceof JavaMailSenderImpl impl) {
            var propiedades = impl.getJavaMailProperties();
            propiedades.put("mail.smtp.starttls.enable", "true");
            propiedades.put("mail.smtp.starttls.required", "true");
            propiedades.put("mail.smtp.connectiontimeout", TIEMPO_DE_ESPERA_MS);
            propiedades.put("mail.smtp.timeout", TIEMPO_DE_ESPERA_MS);
            propiedades.put("mail.smtp.writetimeout", TIEMPO_DE_ESPERA_MS);
        }
        return new CorreoSmtp(emisor, remitente);
    }
}
