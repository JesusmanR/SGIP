package co.com.bspstore.sgip.logica.correo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El modo {@code registro}: en lugar de enviar, escribe el correo en el log.
 *
 * Sirve para desarrollar y probar sin servidor de correo. Escribe el texto
 * completo, código incluido, y por eso la configuración impide arrancar este
 * modo en producción (ver {@code ConfiguracionCorreo}).
 */
public class CorreoEnRegistro implements EnviadorDeCorreo {

    private static final Logger log = LoggerFactory.getLogger(CorreoEnRegistro.class);

    @Override
    public void enviar(String destino, String asunto, String texto) {
        log.info("[CORREO LOCAL] para: {} | asunto: {} | texto: {}", destino, asunto, texto);
    }
}
