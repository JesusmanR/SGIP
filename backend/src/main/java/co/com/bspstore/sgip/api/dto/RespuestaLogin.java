package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.Usuario;

/**
 * El token viaja aquí y en ningún otro sitio. No se registra en la
 * bitácora ni se escribe en el log: quien lo necesita es el cliente que
 * hizo el ingreso.
 */
public record RespuestaLogin(
        String token,
        Integer usuarioId,
        String nombre,
        String correo,
        String rol,
        boolean debeCambiarClave
) {
    public static RespuestaLogin de(Usuario u, String token) {
        return new RespuestaLogin(token, u.getId(), u.getNombre(), u.getCorreo(),
                u.getRol().getNombre(), u.isCambioClaveRequerido());
    }
}
