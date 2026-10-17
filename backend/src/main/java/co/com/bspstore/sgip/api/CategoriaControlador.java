package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CategoriaResumen;
import co.com.bspstore.sgip.api.dto.SolicitudCategoria;
import co.com.bspstore.sgip.logica.ServicioCategoria;
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
 * Ningún método recibe la empresa. Sale de la sesión a través de
 * ContextoEmpresa, de modo que no hay nada que el cliente pueda cambiar
 * para mirar el catálogo de otro comercio.
 */
@RestController
@RequestMapping("/api/v1/categorias")
public class CategoriaControlador {

    private final ServicioCategoria servicio;

    public CategoriaControlador(ServicioCategoria servicio) {
        this.servicio = servicio;
    }

    /** Cualquier usuario de la empresa consulta el catálogo. */
    @GetMapping
    public List<CategoriaResumen> listar() {
        return servicio.listar().stream().map(CategoriaResumen::de).toList();
    }

    @GetMapping("/{categoriaId}")
    public ResponseEntity<CategoriaResumen> ver(@PathVariable Integer categoriaId) {
        return servicio.buscar(categoriaId)
                .map(CategoriaResumen::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Crear sí es del administrador: la matriz de la pantalla P-17 deja
     * la edición del catálogo fuera del alcance del vendedor.
     */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping
    public ResponseEntity<CategoriaResumen> crear(@Valid @RequestBody SolicitudCategoria solicitud) {
        CategoriaResumen creada = CategoriaResumen.de(
                servicio.crear(solicitud.nombre(), solicitud.descripcion()));
        return ResponseEntity.created(URI.create("/api/v1/categorias/" + creada.id())).body(creada);
    }

    /** Pantalla P-17, edición de una categoría del catálogo (RF-19). */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PutMapping("/{categoriaId}")
    public ResponseEntity<CategoriaResumen> editar(
            @PathVariable Integer categoriaId,
            @Valid @RequestBody SolicitudCategoria solicitud) {
        return servicio.actualizar(categoriaId, solicitud.nombre(), solicitud.descripcion())
                .map(CategoriaResumen::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Desactivar, no borrar: el historial de los productos que usaron la
     * categoría tiene que seguir siendo legible. Es un POST sobre un
     * recurso propio y no un DELETE, por lo mismo que el desbloqueo de
     * usuarios: nada desaparece.
     */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping("/{categoriaId}/desactivar")
    public ResponseEntity<CategoriaResumen> desactivar(@PathVariable Integer categoriaId) {
        return servicio.desactivar(categoriaId)
                .map(CategoriaResumen::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
