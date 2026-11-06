package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.Usuario;

/**
 * Un usuario de una empresa, como lo ve el super administrador al buscar a
 * quién desbloquear.
 *
 * Seis campos y no más. No lleva la clave ni su hash, ni si debe cambiarla, ni
 * la empresa (la ruta ya la nombra): el super administrador administra el
 * ciclo de vida de los comercios y esta lista le sirve para identificar a una
 * persona, no para conocer sus credenciales (RN-17).
 *
 * {@code bloqueado} no es la columna sino «hoy no puede entrar por intentos
 * fallidos»: un bloqueo cuyo plazo ya venció sigue marcado en la base hasta que
 * la persona vuelve a entrar, pero ya no la deja fuera.
 */
public record UsuarioEnPlataforma(
        Integer id,
        String nombre,
        String correo,
        String rol,
        boolean activo,
        boolean bloqueado
) {

    public static UsuarioEnPlataforma de(Usuario u, boolean bloqueadoHoy) {
        return new UsuarioEnPlataforma(u.getId(), u.getNombre(), u.getCorreo(),
                u.getRol().getNombre(), u.isActivo(), bloqueadoHoy);
    }
}
