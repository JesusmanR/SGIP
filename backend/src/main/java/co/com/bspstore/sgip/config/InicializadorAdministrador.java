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

@Component
public class InicializadorAdministrador implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(InicializadorAdministrador.class);
    private static final int LONGITUD_MINIMA = 8;

    private final UsuarioRepositorio usuarios;
    private final RolRepositorio roles;
    private final String correo;
    private final String clave;

    public InicializadorAdministrador(UsuarioRepositorio usuarios,
                                      RolRepositorio roles,
                                      @Value("${sgip.admin.correo:}") String correo,
                                      @Value("${sgip.admin.clave:}") String clave) {
        this.usuarios = usuarios;
        this.roles = roles;
        this.correo = correo;
        this.clave = clave;
    }

    @Override
    public void run(String... args) {
        if (usuarios.count() > 0) {
            log.debug("Ya existen usuarios; no se crea el administrador inicial.");
            return;
        }
        if (correo.isBlank() || clave.isBlank()) {
            log.warn("No hay usuarios y no se definieron SGIP_ADMIN_CORREO ni SGIP_ADMIN_CLAVE. "
                    + "Nadie podrá ingresar al sistema.");
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
        usuarios.save(nuevo);
        log.info("Administrador inicial creado: {}", nuevo.getCorreo());
    }
}