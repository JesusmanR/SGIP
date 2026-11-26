package co.com.bspstore.sgip.logica.correo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Los dos envíos de correo (ADR-0036).
 *
 * El log se captura con un {@link ListAppender} sobre el registro de cada clase,
 * como en {@code AvisoUvtAlArrancarTest}: se comprueban el nivel y el mensaje, no
 * el formato de la línea.
 *
 * Lo que más importa del modo smtp es lo que NO escribe: ni el texto (lleva el
 * código de recuperación) ni el destinatario completo.
 */
class CorreoTest {

    private static final String CODIGO = "987654";
    private static final String TEXTO = "Tu código es " + CODIGO + ". Vence en 15 minutos.";

    private final Logger registroLocal = (Logger) LoggerFactory.getLogger(CorreoEnRegistro.class);
    private final Logger registroSmtp = (Logger) LoggerFactory.getLogger(CorreoSmtp.class);
    private final ListAppender<ILoggingEvent> eventos = new ListAppender<>();
    private Level nivelLocal;
    private Level nivelSmtp;

    @BeforeEach
    void capturarElLog() {
        nivelLocal = registroLocal.getLevel();
        nivelSmtp = registroSmtp.getLevel();
        registroLocal.setLevel(Level.ALL);
        registroSmtp.setLevel(Level.ALL);
        eventos.start();
        registroLocal.addAppender(eventos);
        registroSmtp.addAppender(eventos);
    }

    @AfterEach
    void soltarElLog() {
        registroLocal.detachAppender(eventos);
        registroSmtp.detachAppender(eventos);
        registroLocal.setLevel(nivelLocal);
        registroSmtp.setLevel(nivelSmtp);
        eventos.stop();
    }

    private String todoElLog() {
        StringBuilder todo = new StringBuilder();
        for (ILoggingEvent e : eventos.list) {
            todo.append(e.getLevel()).append(' ').append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                todo.append(e.getThrowableProxy().getClassName()).append(' ')
                        .append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return todo.toString();
    }

    @Test
    void registroEscribeElAviso() {
        new CorreoEnRegistro().enviar("ana@tienda.com", "Tu código", TEXTO);

        assertEquals(1, eventos.list.size());
        assertEquals(Level.INFO, eventos.list.get(0).getLevel());
        String linea = eventos.list.get(0).getFormattedMessage();
        assertTrue(linea.contains("[CORREO LOCAL]"), linea);
        assertTrue(linea.contains("ana@tienda.com"), linea);
        assertTrue(linea.contains("Tu código"), linea);
        assertTrue(linea.contains(TEXTO), linea);
    }

    @Test
    void enmascarar() {
        assertEquals("j***@dominio.com", CorreoSmtp.enmascarar("juan@dominio.com"));
        assertEquals("***@x.co", CorreoSmtp.enmascarar("a@x.co"));
    }

    @Test
    void enmascararNoFallaConEntradasRaras() {
        assertEquals("***", CorreoSmtp.enmascarar(null));
        assertEquals("***", CorreoSmtp.enmascarar(""));
        assertEquals("***", CorreoSmtp.enmascarar("sinarroba"));
        assertEquals("***", CorreoSmtp.enmascarar("@dominio.com"));
    }

    @Test
    void smtpArmaElMensajeConElRemitenteConfigurado() {
        JavaMailSender emisor = mock(JavaMailSender.class);

        new CorreoSmtp(emisor, "no-responder@bspstore.com.co")
                .enviar("ana@tienda.com", "Tu código", TEXTO);

        ArgumentCaptor<SimpleMailMessage> mensaje = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(emisor).send(mensaje.capture());
        assertEquals("no-responder@bspstore.com.co", mensaje.getValue().getFrom());
        assertEquals("ana@tienda.com", mensaje.getValue().getTo()[0]);
        assertEquals("Tu código", mensaje.getValue().getSubject());
        assertEquals(TEXTO, mensaje.getValue().getText());
    }

    @Test
    void smtpNoEscribeNiElTextoNiElDestinoEnElExito() {
        new CorreoSmtp(mock(JavaMailSender.class), "no-responder@bspstore.com.co")
                .enviar("juan@dominio.com", "Tu código", TEXTO);

        String log = todoElLog();
        assertFalse(log.contains(CODIGO), log);
        assertFalse(log.contains("juan@dominio.com"), log);
    }

    @Test
    void smtpNoEscribeElTexto() {
        JavaMailSender emisor = mock(JavaMailSender.class);
        // El servidor responde con un mensaje que, por mala suerte, repite el texto.
        doThrow(new MailSendException("falló el envío de: " + TEXTO))
                .when(emisor).send(any(SimpleMailMessage.class));
        CorreoSmtp correo = new CorreoSmtp(emisor, "no-responder@bspstore.com.co");

        assertDoesNotThrow(() -> correo.enviar("juan@dominio.com", "Tu código", TEXTO));

        String log = todoElLog();
        assertTrue(eventos.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR), log);
        assertTrue(log.contains("j***@dominio.com"), log);
        assertFalse(log.contains(CODIGO), log);
        assertFalse(log.contains("Vence en 15 minutos"), log);
        assertFalse(log.contains("juan@dominio.com"), log);
        assertTrue(log.contains("MailSendException"), log);
    }
}
