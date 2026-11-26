package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.logica.correo.CorreoEnRegistro;
import co.com.bspstore.sgip.logica.correo.CorreoSmtp;
import co.com.bspstore.sgip.logica.correo.EnviadorDeCorreo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Locale;

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
 * El modo se lee con {@code trim} y en minúsculas, de modo que «SMTP» y « smtp »
 * valen como {@code smtp}. Cualquier otro valor, también el vacío, impide arrancar:
 * una errata no puede dejar la aplicación sin correo ni caer en silencio al
 * registro. Un único método elige la implementación para que la normalización no
 * pueda contradecirse con una condición aparte.
 *
 * En modo {@code smtp} tampoco se arranca a medias: sin remitente, con un emisor
 * que no sea el {@link JavaMailSenderImpl} de Spring Boot o con un protocolo que no
 * sea {@code smtp} (las propiedades de STARTTLS y de tiempos de espera son
 * {@code mail.smtp.*} y no regirían), la aplicación falla al arrancar.
 */
@Configuration
public class ConfiguracionCorreo {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionCorreo.class);

    private static final String TIEMPO_DE_ESPERA_MS = "10000";

    @Bean
    EnviadorDeCorreo enviadorDeCorreo(Environment entorno, ObjectProvider<JavaMailSender> emisor) {
        String recibido = entorno.getProperty("sgip.correo.modo", "registro");
        return switch (recibido.trim().toLowerCase(Locale.ROOT)) {
            case "registro" -> correoEnRegistro(entorno);
            case "smtp" -> correoSmtp(entorno, emisor.getIfAvailable());
            default -> throw new IllegalStateException(
                    "sgip.correo.modo debe ser «registro» o «smtp»; se recibió «" + recibido + "».");
        };
    }

    private EnviadorDeCorreo correoEnRegistro(Environment entorno) {
        if (entorno.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("El modo de correo «registro» no se permite en producción.");
        }
        log.warn("Los correos no salen: modo registro.");
        return new CorreoEnRegistro();
    }

    private EnviadorDeCorreo correoSmtp(Environment entorno, JavaMailSender emisor) {
        String protocolo = entorno.getProperty("spring.mail.protocol", "smtp");
        if (!"smtp".equals(protocolo)) {
            throw new IllegalStateException("El modo de correo «smtp» solo admite spring.mail.protocol=smtp"
                    + " (con STARTTLS); se recibió «" + protocolo + "».");
        }
        String remitente = entorno.getProperty("SGIP_CORREO_REMITENTE");
        if (remitente == null || remitente.isBlank()) {
            throw new IllegalStateException("El modo de correo «smtp» exige la variable SGIP_CORREO_REMITENTE.");
        }
        if (!(emisor instanceof JavaMailSenderImpl impl)) {
            throw new IllegalStateException("El modo de correo «smtp» necesita el JavaMailSenderImpl de Spring Boot"
                    + " (defina SPRING_MAIL_HOST); no hay uno.");
        }
        var propiedades = impl.getJavaMailProperties();
        propiedades.put("mail.smtp.starttls.enable", "true");
        propiedades.put("mail.smtp.starttls.required", "true");
        propiedades.put("mail.smtp.connectiontimeout", TIEMPO_DE_ESPERA_MS);
        propiedades.put("mail.smtp.timeout", TIEMPO_DE_ESPERA_MS);
        propiedades.put("mail.smtp.writetimeout", TIEMPO_DE_ESPERA_MS);
        return new CorreoSmtp(impl, remitente.trim());
    }
}
