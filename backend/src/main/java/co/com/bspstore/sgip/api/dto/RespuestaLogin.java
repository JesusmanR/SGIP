package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.SesionEmitida;

import java.time.LocalDateTime;

public record RespuestaLogin(
        String token,
        LocalDateTime expira,
        Integer usuarioId,
        String nombre,
        String correo,
        String rol,
        boolean debeCambiarClave
) {
    public static RespuestaLogin de(SesionEmitida s) {
        return new RespuestaLogin(
                s.token(), s.expira(),
                s.usuario().getId(), s.usuario().getNombre(), s.usuario().getCorreo(),
                s.usuario().getRol().getNombre(), s.usuario().isCambioClaveRequerido());
    }
}