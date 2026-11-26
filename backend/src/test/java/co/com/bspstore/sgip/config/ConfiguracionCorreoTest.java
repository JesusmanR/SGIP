package co.com.bspstore.sgip.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.com.bspstore.sgip.logica.correo.CorreoEnRegistro;
import co.com.bspstore.sgip.logica.correo.CorreoSmtp;
import co.com.bspstore.sgip.logica.correo.EnviadorDeCorreo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La elección del modo de correo (ADR-0036 y §5.7 de la spec).
 *
 * Se usa un {@link ApplicationContextRunner}, sin base de datos: las
 * propiedades van en cada prueba y nunca en un archivo.
 */
class ConfiguracionCorreoTest {

    /** Hace de {@code JavaMailSender}, que en la aplicación real crea Spring Boot con SPRING_MAIL_*. */
    @Configuration
    static class ConEmisor {
        @Bean
        JavaMailSenderImpl emisor() {
            return new JavaMailSenderImpl();
        }
    }

    private static final String REMITENTE = "SGIP_CORREO_REMITENTE=no-responder@bspstore.com.co";
    private static final String NO_EN_PRODUCCION =
            "El modo de correo «registro» no se permite en producción.";

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(ConfiguracionCorreo.class);

    private final Logger registro = (Logger) LoggerFactory.getLogger(ConfiguracionCorreo.class);
    private final ListAppender<ILoggingEvent> eventos = new ListAppender<>();
    private Level nivelAnterior;

    @BeforeEach
    void capturarElLog() {
        nivelAnterior = registro.getLevel();
        registro.setLevel(Level.ALL);
        eventos.start();
        registro.addAppender(eventos);
    }

    @AfterEach
    void soltarElLog() {
        registro.detachAppender(eventos);
        registro.setLevel(nivelAnterior);
        eventos.stop();
    }

    @Test
    void sinPropiedadElBeanEsElRegistro() {
        contexto.run(c -> {
            assertThat(c).hasSingleBean(EnviadorDeCorreo.class);
            assertThat(c.getBean(EnviadorDeCorreo.class)).isInstanceOf(CorreoEnRegistro.class);
        });
    }

    @Test
    void conModoRegistroTambienEsElRegistro() {
        contexto.withPropertyValues("sgip.correo.modo=registro").run(c ->
                assertThat(c.getBean(EnviadorDeCorreo.class)).isInstanceOf(CorreoEnRegistro.class));
    }

    @Test
    void elModoRegistroAvisaUnaVezAlArrancar() {
        contexto.run(c -> assertThat(c).hasNotFailed());

        long avisos = eventos.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().equals("Los correos no salen: modo registro."))
                .count();
        assertThat(avisos).isEqualTo(1);
    }

    @Test
    void conModoSmtpElBeanEsSmtpYNoAvisaDeRegistro() {
        contexto.withUserConfiguration(ConEmisor.class)
                .withPropertyValues("sgip.correo.modo=smtp", REMITENTE)
                .run(c -> {
                    assertThat(c).hasSingleBean(EnviadorDeCorreo.class);
                    assertThat(c.getBean(EnviadorDeCorreo.class)).isInstanceOf(CorreoSmtp.class);
                });

        assertThat(eventos.list).noneMatch(e -> e.getFormattedMessage().contains("modo registro"));
    }

    @Test
    void conModoSmtpExigeStarttlsYFijaLosTiemposDeEspera() {
        contexto.withUserConfiguration(ConEmisor.class)
                .withPropertyValues("sgip.correo.modo=smtp", REMITENTE)
                .run(c -> {
                    Properties p = ((JavaMailSenderImpl) c.getBean(JavaMailSender.class)).getJavaMailProperties();
                    assertThat(p.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
                    assertThat(p.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
                    assertThat(p.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
                    assertThat(p.getProperty("mail.smtp.timeout")).isEqualTo("10000");
                    assertThat(p.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
                });
    }

    @Test
    void sinModoSmtpNoSeTocaElEmisor() {
        contexto.withUserConfiguration(ConEmisor.class).run(c -> {
            Properties p = ((JavaMailSenderImpl) c.getBean(JavaMailSender.class)).getJavaMailProperties();
            assertThat(p).isEmpty();
        });
    }

    @Test
    void registroEnProduccionNoArranca() {
        contexto.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("sgip.correo.modo=registro")
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasRootCauseMessage(NO_EN_PRODUCCION);
                });
    }

    @Test
    void sinPropiedadEnProduccionTampocoArranca() {
        contexto.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasRootCauseMessage(NO_EN_PRODUCCION);
                });
    }

    @Test
    void smtpEnProduccionArranca() {
        contexto.withUserConfiguration(ConEmisor.class)
                .withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("sgip.correo.modo=smtp", REMITENTE)
                .run(c -> assertThat(c).hasNotFailed());
    }

    // --- Ronda 1 -----------------------------------------------------------

    private static final String MODO_INVALIDO = "sgip.correo.modo debe ser «registro» o «smtp»; se recibió «%s».";

    @Test
    void unModoConErrataNoArranca() {
        contexto.withPropertyValues("sgip.correo.modo=smpt").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasRootCauseMessage(MODO_INVALIDO.formatted("smpt"));
        });
    }

    @Test
    void unModoVacioNoArranca() {
        contexto.withPropertyValues("sgip.correo.modo=").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasRootCauseMessage(MODO_INVALIDO.formatted(""));
        });
    }

    @Test
    void elModoSeNormalizaConMayusculasYEspacios() {
        contexto.withUserConfiguration(ConEmisor.class)
                .withPropertyValues("sgip.correo.modo=SMTP", REMITENTE)
                .run(c -> assertThat(c.getBean(EnviadorDeCorreo.class)).isInstanceOf(CorreoSmtp.class));
        contexto.withUserConfiguration(ConEmisor.class)
                .withPropertyValues("sgip.correo.modo= smtp ", REMITENTE)
                .run(c -> assertThat(c.getBean(EnviadorDeCorreo.class)).isInstanceOf(CorreoSmtp.class));
        contexto.withPropertyValues("sgip.correo.modo= Registro ")
                .run(c -> assertThat(c.getBean(EnviadorDeCorreo.class)).isInstanceOf(CorreoEnRegistro.class));
    }

    @Test
    void registroConVariosPerfilesIncluidoProdNoArranca() {
        contexto.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod", "otro"))
                .run(c -> assertThat(c.getStartupFailure()).hasRootCauseMessage(NO_EN_PRODUCCION));
        contexto.withInitializer(c -> c.getEnvironment().setActiveProfiles("otro", "prod"))
                .run(c -> assertThat(c.getStartupFailure()).hasRootCauseMessage(NO_EN_PRODUCCION));
    }

    @Configuration
    static class ConEmisorQueNoEsImpl {
        @Bean
        JavaMailSender emisor() {
            return org.mockito.Mockito.mock(JavaMailSender.class);
        }
    }

    @Test
    void smtpConUnEmisorQueNoEsImplNoArranca() {
        contexto.withUserConfiguration(ConEmisorQueNoEsImpl.class)
                .withPropertyValues("sgip.correo.modo=smtp", REMITENTE)
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(c.getStartupFailure().getMessage() + rootMessage(c.getStartupFailure()))
                            .contains("JavaMailSenderImpl");
                });
    }

    @Test
    void smtpConRemitenteEnBlancoNoArranca() {
        contexto.withUserConfiguration(ConEmisor.class)
                .withPropertyValues("sgip.correo.modo=smtp", "SGIP_CORREO_REMITENTE=   ")
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(rootMessage(c.getStartupFailure())).contains("SGIP_CORREO_REMITENTE");
                });
    }

    @Test
    void smtpSinRemitenteNoArranca() {
        contexto.withUserConfiguration(ConEmisor.class)
                .withPropertyValues("sgip.correo.modo=smtp")
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(rootMessage(c.getStartupFailure())).contains("SGIP_CORREO_REMITENTE");
                });
    }

    @Test
    void smtpConOtroProtocoloNoArranca() {
        contexto.withUserConfiguration(ConEmisor.class)
                .withPropertyValues("sgip.correo.modo=smtp", REMITENTE, "spring.mail.protocol=smtps")
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(rootMessage(c.getStartupFailure())).contains("spring.mail.protocol").contains("smtps");
                });
    }

    @Test
    void smtpConElProtocoloSmtpExplicitoArranca() {
        contexto.withUserConfiguration(ConEmisor.class)
                .withPropertyValues("sgip.correo.modo=smtp", REMITENTE, "spring.mail.protocol=smtp")
                .run(c -> assertThat(c).hasNotFailed());
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null) {
            r = r.getCause();
        }
        return String.valueOf(r.getMessage());
    }
}
