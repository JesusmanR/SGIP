package co.com.bspstore.sgip.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * El trabajo que no debe hacer esperar a quien lo pide (la recuperación de la
 * contraseña, §5.3 de la spec).
 *
 * {@code ejecutorDeCorreo}: 2 hilos y una cola de 50. Con la cola llena la tarea
 * se DESCARTA, con un aviso en el log; no se usa {@code CallerRunsPolicy}, que
 * haría la tarea en el hilo de la petición y devolvería, en el tiempo de
 * respuesta, la diferencia que la ejecución asíncrona quiere esconder.
 *
 * Un error que se escape de un método {@code @Async} se anota con la clase de la
 * excepción y el método, sin los argumentos: pueden llevar el código.
 */
@Configuration
@EnableAsync
public class ConfiguracionAsincrona implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionAsincrona.class);

    @Bean(name = "ejecutorDeCorreo")
    public ThreadPoolTaskExecutor ejecutorDeCorreo() {
        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(2);
        ejecutor.setMaxPoolSize(2);
        ejecutor.setQueueCapacity(50);
        ejecutor.setThreadNamePrefix("correo-");
        ejecutor.setRejectedExecutionHandler(new DescartarConAviso());
        ejecutor.setWaitForTasksToCompleteOnShutdown(true);
        ejecutor.setAwaitTerminationSeconds(10);
        return ejecutor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (error, metodo, argumentos) -> log.error("Falló la tarea asíncrona {}.{}: {}.",
                metodo.getDeclaringClass().getSimpleName(), metodo.getName(), error.getClass().getName());
    }

    /** Descarta la tarea que no cabe y lo dice en el log, sin nada de la tarea. */
    static final class DescartarConAviso extends ThreadPoolExecutor.DiscardPolicy {
        @Override
        public void rejectedExecution(Runnable tarea, ThreadPoolExecutor ejecutor) {
            log.warn("La cola de correos está llena: se descartó una tarea (el correo no sale).");
            super.rejectedExecution(tarea, ejecutor);
        }
    }
}
