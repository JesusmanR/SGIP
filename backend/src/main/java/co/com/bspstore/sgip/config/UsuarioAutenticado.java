package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.modelo.Usuario;

/**
 * Quién hizo la petición. Es lo que FiltroSesion deja como principal en
 * el contexto de seguridad, en lugar del correo suelto que había antes.
 *
 * Lleva la empresa porque sin ella ninguna consulta del catálogo, del
 * inventario ni de las ventas se puede acotar, y acotarla a mano en cada
 * servicio es exactamente como se filtra información entre comercios.
 *
 * {@code empresaId} admite nulo: el usuario de plataforma no pertenece a
 * ninguna empresa (RN-17). Quien necesite una debe pedirla a
 * {@link ContextoEmpresa}, que convierte ese nulo en un rechazo claro en
 * lugar de en una consulta sin filtro.
 */
public record UsuarioAutenticado(Integer usuarioId, String correo, String rol, Integer empresaId) {

    public static UsuarioAutenticado de(Usuario u) {
        return new UsuarioAutenticado(u.getId(), u.getCorreo(),
                u.getRol().getNombre(), u.getEmpresaId());
    }

    public boolean esDePlataforma() {
        return empresaId == null;
    }

    @Override
    public String toString() {
        // Lo que acabe en un log debe identificar sin exponer de más.
        return correo;
    }
}
