package co.com.bspstore.sgip.logica.correo;

/**
 * Sale de aquí un correo de texto plano (ADR-0036).
 *
 * Hay dos implementaciones, elegidas con {@code sgip.correo.modo}:
 * {@link CorreoEnRegistro} (por omisión, no envía nada) y {@link CorreoSmtp}.
 *
 * Quien llama no debe esperar una excepción por un fallo del envío: se envía
 * fuera de la petición del usuario y un error se anota en el log, no se
 * propaga.
 */
public interface EnviadorDeCorreo {

    void enviar(String destino, String asunto, String texto);
}
