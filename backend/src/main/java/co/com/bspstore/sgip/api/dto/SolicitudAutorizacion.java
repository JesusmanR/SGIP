package co.com.bspstore.sgip.api.dto;

/**
 * La autorización de un administrador que viaja dentro de la petición que confirma
 * una operación (ADR-0032): quién autoriza, elegido de la lista de administradores
 * de la empresa, y su PIN.
 *
 * Sin anotaciones de validación a propósito: una autorización incompleta no es un
 * dato mal escrito sino una autorización que no existe, y responde 403 como
 * cualquier otra que no vale. {@code ServicioAutorizacion} la comprueba.
 *
 * {@code toString} oculta el PIN para que no llegue a un log por accidente, ni
 * suelto ni dentro de la petición que lo contiene.
 */
public record SolicitudAutorizacion(Integer administradorId, String pin) {

    @Override
    public String toString() {
        return "SolicitudAutorizacion[administradorId=" + administradorId + ", pin=***]";
    }
}
