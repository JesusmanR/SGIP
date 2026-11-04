package co.com.bspstore.sgip.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.com.bspstore.sgip.api.dto.AlertaUvt;
import co.com.bspstore.sgip.logica.ServicioTributario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * El aviso del arranque (arreglo 10).
 *
 * Aún no hay pantalla de plataforma donde mostrar la alerta de la UVT, así
 * que el operador la ve en el log. Se captura el log con un
 * {@link ListAppender} sobre el registro de la clase y no con la salida de
 * consola: así se comprueban el nivel y el mensaje, no el formato de la
 * línea.
 *
 * Lo que más importa de esta clase es lo que NO hace: ningún fallo suyo
 * puede impedir que la aplicación arranque.
 */
@ExtendWith(MockitoExtension.class)
class AvisoUvtAlArrancarTest {

    @Mock ServicioTributario tributario;

    private final Logger registro = (Logger) LoggerFactory.getLogger(AvisoUvtAlArrancar.class);
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
    }

    private List<ILoggingEvent> escritos() {
        return eventos.list;
    }

    private void arrancar() {
        new AvisoUvtAlArrancar(tributario).run(new DefaultApplicationArguments());
    }

    @Test
    void conLaAlertaActivaEscribeUnWarnConElAnioYLosDias() {
        when(tributario.alertaUvt()).thenReturn(new AlertaUvt(true, 2027, 61));

        arrancar();

        assertEquals(1, escritos().size());
        ILoggingEvent aviso = escritos().get(0);
        assertEquals(Level.WARN, aviso.getLevel());
        String mensaje = aviso.getFormattedMessage();
        assertTrue(mensaje.contains("2027"), mensaje);
        assertTrue(mensaje.contains("61"), mensaje);
    }

    @Test
    void sinAlertaNoEscribeNada() {
        when(tributario.alertaUvt()).thenReturn(new AlertaUvt(false, 2027, 0));

        arrancar();

        assertTrue(escritos().isEmpty(), "No debía escribir nada: " + escritos());
    }

    @Test
    void siLaConsultaFallaLoAnotaYLaAplicacionSigueArrancando() {
        // Por ejemplo, el esquema todavía no está listo.
        when(tributario.alertaUvt()).thenThrow(new IllegalStateException("tabla no lista"));

        assertDoesNotThrow(this::arrancar);

        assertEquals(1, escritos().size());
        assertEquals(Level.WARN, escritos().get(0).getLevel());
    }
}
