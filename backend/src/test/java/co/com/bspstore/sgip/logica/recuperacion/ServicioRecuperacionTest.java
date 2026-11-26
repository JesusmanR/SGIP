package co.com.bspstore.sgip.logica.recuperacion;

import co.com.bspstore.sgip.api.dto.MensajeRecuperacion;
import co.com.bspstore.sgip.config.ConfiguracionAsincrona;
import co.com.bspstore.sgip.datos.AuditoriaAccesoRepositorio;
import co.com.bspstore.sgip.datos.RepositorioCodigosRecuperacion;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.logica.correo.EnviadorDeCorreo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Lo que hace el hilo de la petición al pedir el código (§5.3 de la spec): normalizar,
 * entregar la tarea al ejecutor y responder el mensaje fijo, sin esperar a la base.
 */
class ServicioRecuperacionTest {

    private static final String MENSAJE =
            "Si el correo está registrado, te enviamos un código. Revisa también la carpeta de spam.";

    // ----------------------------------------------------------- normalizar

    @Test
    void normalizaConTrimYMinusculas() {
        assertThat(ServicioRecuperacion.normalizar(" Ana@Tienda.COM ")).isEqualTo("ana@tienda.com");
    }

    @Test
    void recortaA120Caracteres() {
        String normalizado = ServicioRecuperacion.normalizar("A".repeat(300) + "@tienda.com");

        assertThat(normalizado).hasSize(120).isEqualTo("a".repeat(120));
    }

    @Test
    void elVacioElBlancoYElNuloSeRegistranComoVacio() {
        assertThat(ServicioRecuperacion.normalizar(null)).isEqualTo("(vacío)");
        assertThat(ServicioRecuperacion.normalizar("")).isEqualTo("(vacío)");
        assertThat(ServicioRecuperacion.normalizar("   \t ")).isEqualTo("(vacío)");
    }

    @Test
    void laMayusculaQueSeAlargaAlPasarAMinusculaNoPasaDe120() {
        // «İ» (i mayúscula con punto) pasa a dos caracteres al bajarla: el tope se aplica al final.
        assertThat(ServicioRecuperacion.normalizar("İ".repeat(100)).length()).isLessThanOrEqualTo(120);
    }

    // -------------------------------------------------------------- solicitar

    @Test
    void devuelveElMensajeFijoYEntregaLaTareaConElCorreoNormalizado() {
        TareaDeRecuperacion tarea = mock(TareaDeRecuperacion.class);

        MensajeRecuperacion respuesta = new ServicioRecuperacion(tarea).solicitar(" Ana@Tienda.COM ", "10.0.0.5");

        assertThat(respuesta.mensaje()).isEqualTo(MENSAJE);
        verify(tarea).procesar("ana@tienda.com", "10.0.0.5");
    }

    @Test
    void elMensajeEsElMismoParaCualquierEntrada() {
        TareaDeRecuperacion tarea = mock(TareaDeRecuperacion.class);
        var servicio = new ServicioRecuperacion(tarea);

        assertThat(servicio.solicitar(null, "10.0.0.5")).isEqualTo(servicio.solicitar("x".repeat(500), "10.0.0.5"));
        assertThat(servicio.solicitar("", "10.0.0.5").mensaje()).isEqualTo(MENSAJE);
    }

    @Test
    void elOrigenSeRecortaA60UnaSolaVezParaGuardarloYParaContarlo() {
        TareaDeRecuperacion tarea = mock(TareaDeRecuperacion.class);

        new ServicioRecuperacion(tarea).solicitar("ana@tienda.com", "x".repeat(100));

        // La tarea recibe ya el valor de 60: el mismo que se guarda en la bitácora y con el que se
        // cuenta el tope por IP. Si se recortara solo al guardar, el conteo no encontraría las filas.
        verify(tarea).procesar("ana@tienda.com", "x".repeat(60));
    }

    @Test
    void sinOrigenSeRegistraConUnTextoYNoConNulo() {
        TareaDeRecuperacion tarea = mock(TareaDeRecuperacion.class);

        new ServicioRecuperacion(tarea).solicitar("ana@tienda.com", null);

        verify(tarea).procesar("ana@tienda.com", "(desconocido)");
    }

    // ------------------------------------------- el hilo de la petición no toca la base

    /**
     * Un ejecutor que no corre nada hasta que se le ordena: así se ve qué pasó mientras la tarea
     * esperaba, que es el momento en que la petición ya respondió.
     */
    static final class EjecutorEnEspera implements Executor {
        final CountDownLatch puerta = new CountDownLatch(1);
        final AtomicReference<String> hilo = new AtomicReference<>();

        @Override
        public void execute(Runnable tarea) {
            Thread t = new Thread(() -> {
                try {
                    puerta.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                hilo.set(Thread.currentThread().getName());
                tarea.run();
            }, "ejecutor-de-prueba");
            t.start();
        }
    }

    @Configuration
    @EnableAsync
    static class ConfiguracionDePrueba {
        static final EjecutorEnEspera EJECUTOR = new EjecutorEnEspera();
        static final UsuarioRepositorio USUARIOS = mock(UsuarioRepositorio.class);
        static final AuditoriaAccesoRepositorio AUDITORIA = mock(AuditoriaAccesoRepositorio.class);
        static final RepositorioCodigosRecuperacion CODIGOS = mock(RepositorioCodigosRecuperacion.class);

        @Bean(name = "ejecutorDeCorreo")
        Executor ejecutorDeCorreo() {
            return EJECUTOR;
        }

        @Bean
        UsuarioRepositorio usuarios() {
            return USUARIOS;
        }

        @Bean
        AuditoriaAccesoRepositorio auditoria() {
            return AUDITORIA;
        }

        @Bean
        RepositorioCodigosRecuperacion codigos() {
            return CODIGOS;
        }

        @Bean
        EnviadorDeCorreo enviador() {
            return mock(EnviadorDeCorreo.class);
        }

        @Bean
        Clock reloj() {
            return Clock.fixed(Instant.parse("2026-11-26T15:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        TareaDeRecuperacion tarea(UsuarioRepositorio usuarios, AuditoriaAccesoRepositorio auditoria,
                                  RepositorioCodigosRecuperacion codigos, EnviadorDeCorreo enviador, Clock reloj) {
            return new TareaDeRecuperacion(usuarios, auditoria, codigos, enviador, reloj);
        }

        @Bean
        ServicioRecuperacion servicio(TareaDeRecuperacion tarea) {
            return new ServicioRecuperacion(tarea);
        }
    }

    @Test
    void respondeSinTocarLaBaseYLaTareaCorreEnElEjecutorDeCorreo() {
        when(ConfiguracionDePrueba.USUARIOS.findByCorreo(any())).thenReturn(Optional.empty());

        new ApplicationContextRunner().withUserConfiguration(ConfiguracionDePrueba.class).run(contexto -> {
            ServicioRecuperacion servicio = contexto.getBean(ServicioRecuperacion.class);

            MensajeRecuperacion respuesta = servicio.solicitar("ana@tienda.com", "10.0.0.5");

            // La tarea sigue retenida en el ejecutor: la petición ya respondió y no consultó nada.
            assertThat(respuesta.mensaje()).isEqualTo(MENSAJE);
            verifyNoInteractions(ConfiguracionDePrueba.USUARIOS, ConfiguracionDePrueba.CODIGOS);

            ConfiguracionDePrueba.EJECUTOR.puerta.countDown();

            // Al soltarla, la tarea corre en otro hilo y entonces sí consulta al usuario.
            verify(ConfiguracionDePrueba.USUARIOS, timeout(5000)).findByCorreo(eq("ana@tienda.com"));
            assertThat(ConfiguracionDePrueba.EJECUTOR.hilo.get()).isEqualTo("ejecutor-de-prueba");
        });
    }

    // ------------------------------------------------------- la cola llena

    /**
     * Con el ejecutor real de la aplicación ({@link ConfiguracionAsincrona}: 2 hilos y una cola de
     * 50) y una tarea que se queda esperando dentro de la base simulada.
     */
    @Configuration
    static class ConfiguracionConColaLlena {
        static final CountDownLatch RETENIDA = new CountDownLatch(1);

        @Bean
        UsuarioRepositorio usuarios() {
            return mock(UsuarioRepositorio.class);
        }

        @Bean
        AuditoriaAccesoRepositorio auditoria() {
            return mock(AuditoriaAccesoRepositorio.class);
        }

        @Bean
        RepositorioCodigosRecuperacion codigos() throws InterruptedException {
            RepositorioCodigosRecuperacion codigos = mock(RepositorioCodigosRecuperacion.class);
            when(codigos.contarSolicitudesDesdeOrigen(any(), any())).thenAnswer(invocacion -> {
                RETENIDA.await(20, TimeUnit.SECONDS);
                return 0;
            });
            return codigos;
        }

        @Bean
        EnviadorDeCorreo enviador() {
            return mock(EnviadorDeCorreo.class);
        }

        @Bean
        Clock reloj() {
            return Clock.fixed(Instant.parse("2026-11-26T15:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        TareaDeRecuperacion tarea(UsuarioRepositorio usuarios, AuditoriaAccesoRepositorio auditoria,
                                  RepositorioCodigosRecuperacion codigos, EnviadorDeCorreo enviador, Clock reloj) {
            return new TareaDeRecuperacion(usuarios, auditoria, codigos, enviador, reloj);
        }

        @Bean
        ServicioRecuperacion servicio(TareaDeRecuperacion tarea) {
            return new ServicioRecuperacion(tarea);
        }
    }

    @Test
    void conLaColaLlenaLaRespuestaSigueSiendo200YNingunaPeticionSeBloquea() {
        // Los dos hilos quedan retenidos y la cola admite 50: de 60 solicitudes, 8 se descartan. Ninguna
        // debe esperar (nada de CallerRuns, que haría la tarea en el hilo de la petición) ni fallar.
        new ApplicationContextRunner()
                .withUserConfiguration(ConfiguracionAsincrona.class, ConfiguracionConColaLlena.class)
                .run(contexto -> {
                    ServicioRecuperacion servicio = contexto.getBean(ServicioRecuperacion.class);
                    try {
                        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                            for (int i = 0; i < 60; i++) {
                                assertThat(servicio.solicitar("ana" + i + "@tienda.com", "10.0.0.5").mensaje())
                                        .isEqualTo(MENSAJE);
                            }
                        });
                    } finally {
                        ConfiguracionConColaLlena.RETENIDA.countDown();
                    }
                });
    }
}
