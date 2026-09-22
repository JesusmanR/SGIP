package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.Usuario;

public record RespuestaLogin(
        Integer usuarioId,
        String nombre,
        String correo,
        String rol,
        boolean debeCambiarClave
) {
    public static RespuestaLogin de(Usuario u) {
        return new RespuestaLogin(u.getId(), u.getNombre(), u.getCorreo(),
                u.getRol().getNombre(), u.isCambioClaveRequerido());
    }
}