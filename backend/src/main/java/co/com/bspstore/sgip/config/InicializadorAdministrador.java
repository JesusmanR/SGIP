package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.datos.RolRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Crea el administrador configurado si todavía no existe.
 *
 * Antes la condición era que la tabla de usuarios estuviera vacía, y eso
 * dejaba un agujero: una base cargada con los datos de demostración de la
 * V8 ya tiene usuarios —con hashes inventados que no verifican ninguna
 * contraseña— de modo que el administrador real no se creaba nunca y
 * nadie podía entrar. La condición correcta es si existe ESE correo.
 */
@Component
public class InicializadorAdministrador implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(InicializadorAdministrador.class);
    private static final int LONGITUD_MINIMA = 8;

    private final UsuarioRepositorio usuarios;
    private final RolRepositorio roles;
    private final String correo;
    private final String clave;
    private final Integer empresaId;

    public InicializadorAdministrador(UsuarioRepositorio usuarios,
                                      RolRepositorio roles,
                                      @Value("${sgip.admin.correo:}") String correo,
                                      @Value("${sgip.admin.clave:}") String clave,
                                      @Value("${sgip.admin.empresa-id:1}") Integer empresaId) {
        this.usuarios = usuarios;
        this.roles = roles;
        // Se normaliza aquí porque Usuario guarda el correo en minúsculas:
        // comprobar la existencia con otra forma no encontraría la fila y
        // el alta chocaría contra uq_usuarios_correo.
        this.correo = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
        this.clave = clave;
        this.empresaId = empresaId;
    }

    @Override
    public void run(String... args) {
        if (correo.isBlank() || clave.isBlank()) {
            log.debug("No se definieron SGIP_ADMIN_CORREO ni SGIP_ADMIN_CLAVE; "
                    + "no se crea ningún administrador.");
            return;
        }
        if (usuarios.existsByCorreo(correo)) {
            log.debug("El administrador {} ya existe; no se crea de nuevo.", correo);
            return;
        }
        if (clave.length() < LONGITUD_MINIMA) {
            throw new IllegalStateException(
                    "SGIP_ADMIN_CLAVE debe tener al menos " + LONGITUD_MINIMA + " caracteres.");
        }

        Rol administrador = roles.findByNombre("ADMINISTRADOR")
                .orElseThrow(() -> new IllegalStateException(
                        "No existe el rol ADMINISTRADOR. ¿Se aplicó la migración V7?"));

        Usuario nuevo = new Usuario(administrador, "Administrador", correo, Seguridad.derivar(clave));
        // Sin empresa, ContextoEmpresa lo tomaría por usuario de plataforma
        // y le respondería 403 en todo el catálogo. El administrador de un
        // comercio pertenece a su comercio.
        nuevo.asignarEmpresa(empresaId);
        usuarios.save(nuevo);
        log.info("Administrador creado: {} (empresa {})", nuevo.getCorreo(), empresaId);
    }
}
