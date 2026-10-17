package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudEdicionUsuario;
import co.com.bspstore.sgip.api.dto.SolicitudUsuario;
import co.com.bspstore.sgip.api.dto.UsuarioDeEmpresa;
import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.logica.ServicioUsuarios;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Los usuarios del propio negocio (RF-12).
 *
 * La anotación de rol va en la clase: todo lo que hay aquí es
 * administración de personal y le corresponde al administrador de la
 * empresa. El super administrador no pasa, y no por descuido: no
 * pertenece a ninguna empresa (RN-17) y estos métodos piden la empresa
 * al contexto, que para él está vacío.
 *
 * Ningún método recibe la empresa. Es la garantía del requisito —«el
 * usuario creado queda ligado a la empresa de quien lo creó y no puede
 * cambiarse de empresa»— y se consigue por omisión: no existe un
 * parámetro que mandar.
 *
 * Todo lo ajeno responde 404 y no 403. Un 403 confirmaría que ese
 * usuario existe en alguna parte, que es más de lo que quien pregunta
 * tiene derecho a saber.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class UsuarioControlador {

    private final ServicioUsuarios servicio;
    private final ServicioAutenticacion autenticacion;

    public UsuarioControlador(ServicioUsuarios servicio, ServicioAutenticacion autenticacion) {
        this.servicio = servicio;
        this.autenticacion = autenticacion;
    }

    /** Activos y desactivados: desactivar no es borrar, y hay que poder verlos. */
    @GetMapping
    public List<UsuarioDeEmpresa> listar() {
        return servicio.listar().stream().map(UsuarioDeEmpresa::de).toList();
    }

    @GetMapping("/{usuarioId}")
    public ResponseEntity<UsuarioDeEmpresa> ver(@PathVariable Integer usuarioId) {
        return servicio.buscar(usuarioId)
                .map(UsuarioDeEmpresa::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 409 si el correo ya está en la plataforma; 403 si el rol no le toca repartirlo. */
    @PostMapping
    public ResponseEntity<UsuarioDeEmpresa> crear(@Valid @RequestBody SolicitudUsuario solicitud) {
        UsuarioDeEmpresa creado = UsuarioDeEmpresa.de(servicio.crear(solicitud));
        return ResponseEntity
                .created(URI.create("/api/v1/usuarios/" + creado.usuarioId()))
                .body(creado);
    }

    @PutMapping("/{usuarioId}")
    public ResponseEntity<UsuarioDeEmpresa> editar(
            @PathVariable Integer usuarioId,
            @Valid @RequestBody SolicitudEdicionUsuario solicitud) {
        return servicio.actualizar(usuarioId, solicitud)
                .map(UsuarioDeEmpresa::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Desactivar, no borrar, por lo mismo que con las categorías: la
     * auditoría de accesos y todo lo que esa persona registró siguen
     * apuntando a ella. Es un POST sobre un recurso propio y no un
     * DELETE, porque nada desaparece.
     */
    @PostMapping("/{usuarioId}/desactivar")
    public ResponseEntity<UsuarioDeEmpresa> desactivar(@PathVariable Integer usuarioId) {
        return servicio.desactivar(usuarioId)
                .map(UsuarioDeEmpresa::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{usuarioId}/activar")
    public ResponseEntity<UsuarioDeEmpresa> activar(@PathVariable Integer usuarioId) {
        return servicio.activar(usuarioId)
                .map(UsuarioDeEmpresa::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Levanta el bloqueo tras cinco intentos fallidos.
     *
     * Fue la primera comprobación de rol del proyecto y recoge la matriz
     * de la pantalla P-17: un vendedor autenticado recibe 403 aquí, no
     * 401. Funciona porque el filtro de sesión pone la autoridad
     * ROLE_ADMINISTRADOR en el contexto y porque la configuración lleva
     * @EnableMethodSecurity; sin esa anotación en la configuración,
     * @PreAuthorize se ignora en silencio y el endpoint queda abierto a
     * cualquier autenticado.
     *
     * No cambia la contraseña, y eso es deliberado: un administrador que
     * pudiera fijarla podría entrar como esa persona, y entonces el
     * rastro de auditoría dejaría de identificar a nadie.
     */
    @PostMapping("/{usuarioId}/desbloquear")
    public ResponseEntity<Void> desbloquear(@PathVariable Integer usuarioId) {
        return autenticacion.desbloquear(usuarioId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
