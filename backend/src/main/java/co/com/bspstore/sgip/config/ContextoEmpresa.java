package co.com.bspstore.sgip.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Único punto desde el que se obtiene la empresa de la petición en curso.
 *
 * Existe para que ningún servicio reciba el identificador de empresa como
 * parámetro desde la capa web. Si lo recibiera, bastaría con que un
 * cliente enviara otro número para leer el catálogo de un comercio
 * ajeno, y el aislamiento quedaría en manos de que nadie se equivoque al
 * escribir cada controlador. Aquí la empresa sale de la sesión, que el
 * cliente no elige.
 */
public final class ContextoEmpresa {

    private ContextoEmpresa() {
        // Clase de utilidad: no se instancia
    }

    public static Optional<UsuarioAutenticado> actual() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof UsuarioAutenticado u)) {
            return Optional.empty();
        }
        return Optional.of(u);
    }

    /**
     * La empresa de quien hizo la petición.
     *
     * Falla en lugar de devolver nulo, y falla distinto según el caso:
     * sin autenticación es un error de configuración de la cadena de
     * seguridad —el endpoint debería haber exigido autenticación—, y con
     * un usuario de plataforma es que alguien expuso una operación de
     * empresa a quien no pertenece a ninguna.
     */
    public static Integer empresaActual() {
        UsuarioAutenticado u = actual().orElseThrow(() -> new IllegalStateException(
                "No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
        if (u.esDePlataforma()) {
            throw new SinEmpresaException();
        }
        return u.empresaId();
    }
}
