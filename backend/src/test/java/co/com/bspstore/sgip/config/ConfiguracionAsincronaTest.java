package co.com.bspstore.sgip.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * El ejecutor de los correos (§5.3 de la spec, «Ejecutor»).
 *
 * Lo que importa: con la cola llena la petición NO se bloquea ni falla (nada de
 * {@code CallerRuns}, que devolvería la diferencia de tiempo), y el log de un
 * error asíncrono lleva la clase y el método, nunca los argumentos (el código).
 */
class ConfiguracionAsincronaTest {

    private final Logger registro = (Logger) LoggerFactory.getLogger(ConfiguracionAsincrona.class);
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
    void sePideElAsincronismo() {
        assertThat(ConfiguracionAsincrona.class.isAnnotationPresent(EnableAsync.class)).isTrue();
        assertThat(ConfiguracionAsincrona.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(AsyncConfigurer.class).isAssignableFrom(ConfiguracionAsincrona.class);
    }

    @Test
    void elEjecutorTieneLaFormaDeLaSpec() {
        new ApplicationContextRunner().withUserConfiguration(ConfiguracionAsincrona.class).run(c -> {
            assertThat(c).hasBean("ejecutorDeCorreo");
            ThreadPoolTaskExecutor e = c.getBean("ejecutorDeCorreo", ThreadPoolTaskExecutor.class);
            assertThat(e.getCorePoolSize()).isEqualTo(2);
            assertThat(e.getMaxPoolSize()).isEqualTo(2);
            assertThat(e.getQueueCapacity()).isEqualTo(50);
            RejectedExecutionHandler h = e.getThreadPoolExecutor().getRejectedExecutionHandler();
            assertThat(h).isInstanceOf(ThreadPoolExecutor.DiscardPolicy.class);
        });
    }

    @Test
    void conLaColaLlenaExecuteNoBloqueaNiLanza() throws Exception {
        ThreadPoolTaskExecutor ejecutor = new ConfiguracionAsincrona().ejecutorDeCorreo();
        ejecutor.initialize();
        CountDownLatch soltar = new CountDownLatch(1);
        CountDownLatch ocupados = new CountDownLatch(2);
        AtomicInteger ejecutadas = new AtomicInteger();
        AtomicInteger descartada = new AtomicInteger();
        try {
            // Dos tareas ocupan los dos hilos y 50 llenan la cola.
            for (int i = 0; i < 2; i++) {
                ejecutor.execute(() -> {
                    ocupados.countDown();
                    try {
                        soltar.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ejecutadas.incrementAndGet();
                });
            }
            assertThat(ocupados.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50; i++) {
                ejecutor.execute(ejecutadas::incrementAndGet);
            }

            // La tarea 53 no cabe: se descarta, rápido y sin excepción.
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> ejecutor.execute(descartada::incrementAndGet));

            soltar.countDown();
            ejecutor.shutdown();
            assertThat(ejecutor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            soltar.countDown();
            ejecutor.shutdown();
        }

        assertThat(descartada.get()).isZero();
        assertThat(ejecutadas.get()).isEqualTo(52);
        assertThat(eventos.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
        assertThat(eventos.list.get(0).getFormattedMessage()).contains("descart");
    }

    @Test
    void unErrorAsincronoSeAnotaConClaseYMetodoSinArgumentos() throws Exception {
        Method metodo = String.class.getMethod("concat", String.class);

        new ConfiguracionAsincrona().getAsyncUncaughtExceptionHandler()
                .handleUncaughtException(new IllegalStateException("el código 123456 falló"), metodo, "123456");

        assertThat(eventos.list).hasSize(1);
        ILoggingEvent evento = eventos.list.get(0);
        assertThat(evento.getLevel()).isEqualTo(Level.ERROR);
        String texto = evento.getFormattedMessage()
                + (evento.getThrowableProxy() == null ? "" : evento.getThrowableProxy().getMessage());
        assertThat(texto).contains("IllegalStateException").contains("concat");
        assertThat(texto).doesNotContain("123456");
    }
}
