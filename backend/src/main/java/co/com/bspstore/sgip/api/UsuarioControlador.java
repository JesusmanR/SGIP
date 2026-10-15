package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usuarios")
public class UsuarioControlador {

    private final ServicioAutenticacion servicio;

    public UsuarioControlador(ServicioAutenticacion servicio) {
        this.servicio = servicio;
    }

    /**
     * Levanta el bloqueo de una cuenta tras cinco intentos fallidos.
     *
     * La anotación es la primera comprobación de rol del proyecto y
     * recoge la matriz de permisos de la pantalla P-17: un vendedor
     * autenticado recibe 403 aquí, no 401. Funciona porque el filtro de
     * sesión pone la autoridad ROLE_ADMINISTRADOR en el contexto y
     * porque la configuración lleva @EnableMethodSecurity; sin esa
     * anotación en la configuración, @PreAuthorize se ignora en silencio
     * y el endpoint queda abierto a cualquier autenticado.
     */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping("/{usuarioId}/desbloquear")
    public ResponseEntity<Void> desbloquear(@PathVariable Integer usuarioId) {
        return servicio.desbloquear(usuarioId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
