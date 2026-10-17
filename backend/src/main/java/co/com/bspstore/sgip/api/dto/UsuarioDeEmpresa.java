package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.Usuario;

import java.time.LocalDateTime;

/**
 * Un usuario del propio negocio, como lo ve su administrador (RF-12).
 *
 * No lleva `claveHash` ni nada derivado de ella. Es obvio y conviene
 * decirlo: el hash es un dato con el que se puede trabajar fuera de
 * línea, y exponerlo en una pantalla de administración regalaría a
 * cualquiera con acceso a esa pantalla el material para atacar las
 * contraseñas del resto.
 *
 * Tampoco lleva `empresaId`. El administrador solo ve usuarios de su
 * empresa, de modo que la columna sería la misma en todas las filas y la
 * única cosa que podría hacer es tentar a alguien a mandarla de vuelta
 * en una edición.
 */
public record UsuarioDeEmpresa(
        Integer usuarioId,
        String nombre,
        String correo,
        String rol,
        boolean activo,
        boolean bloqueado,
        boolean cambioClaveRequerido,
        LocalDateTime creadoEn
) {

    public static UsuarioDeEmpresa de(Usuario u) {
        return new UsuarioDeEmpresa(u.getId(), u.getNombre(), u.getCorreo(),
                u.getRol().getNombre(), u.isActivo(), u.isBloqueado(),
                u.isCambioClaveRequerido(), u.getCreadoEn());
    }
}
