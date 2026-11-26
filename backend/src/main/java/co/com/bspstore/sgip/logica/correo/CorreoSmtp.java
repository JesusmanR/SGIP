package co.com.bspstore.sgip.logica.correo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * El modo {@code smtp}: envía de verdad con el servidor de {@code SPRING_MAIL_*}.
 *
 * El log nunca lleva el texto (trae el código de recuperación) ni el
 * destinatario completo (j***@dominio.com), y tampoco el mensaje de la
 * excepción: un servidor puede repetir en él lo que recibió. De un fallo solo se
 * anotan las clases de la excepción y de su causa raíz, que bastan para
 * distinguir un tiempo agotado de una autenticación rechazada.
 *
 * Un fallo no se propaga: el envío corre fuera de la petición y la respuesta al
 * usuario ya salió.
 */
public class CorreoSmtp implements EnviadorDeCorreo {

    private static final Logger log = LoggerFactory.getLogger(CorreoSmtp.class);

    private final JavaMailSender emisor;
    private final String remitente;

    public CorreoSmtp(JavaMailSender emisor, String remitente) {
        this.emisor = emisor;
        this.remitente = remitente;
    }

    @Override
    public void enviar(String destino, String asunto, String texto) {
        try {
            SimpleMailMessage mensaje = new SimpleMailMessage();
            mensaje.setFrom(remitente);
            mensaje.setTo(destino);
            mensaje.setSubject(asunto);
            mensaje.setText(texto);
            emisor.send(mensaje);
        } catch (RuntimeException e) {
            log.error("No se pudo enviar el correo a {}: {} (causa: {}).",
                    enmascarar(destino), e.getClass().getSimpleName(), causaRaiz(e).getClass().getSimpleName());
        }
    }

    /** {@code "juan@dominio.com"} pasa a {@code "j***@dominio.com"}; lo que no parezca un correo, a {@code "***"}. */
    public static String enmascarar(String correo) {
        if (correo == null) {
            return "***";
        }
        int arroba = correo.indexOf('@');
        if (arroba < 1) {
            return "***";
        }
        return correo.charAt(0) + "***" + correo.substring(arroba);
    }

    private static Throwable causaRaiz(Throwable e) {
        Throwable actual = e;
        while (actual.getCause() != null && actual.getCause() != actual) {
            actual = actual.getCause();
        }
        return actual;
    }
}
