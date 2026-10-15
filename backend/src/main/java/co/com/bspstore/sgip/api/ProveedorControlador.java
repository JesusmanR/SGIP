package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ProveedorResumen;
import co.com.bspstore.sgip.api.dto.SolicitudProveedor;
import co.com.bspstore.sgip.logica.ServicioProveedor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** Pantalla P-06. */
@RestController
@RequestMapping("/api/v1/proveedores")
public class ProveedorControlador {

    private final ServicioProveedor servicio;

    public ProveedorControlador(ServicioProveedor servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<ProveedorResumen> listar() {
        return servicio.listar().stream().map(ProveedorResumen::de).toList();
    }

    @GetMapping("/{proveedorId}")
    public ResponseEntity<ProveedorResumen> ver(@PathVariable Integer proveedorId) {
        return servicio.buscar(proveedorId)
                .map(ProveedorResumen::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping
    public ResponseEntity<ProveedorResumen> crear(@Valid @RequestBody SolicitudProveedor solicitud) {
        ProveedorResumen creado = ProveedorResumen.de(servicio.crear(solicitud));
        return ResponseEntity
                .created(URI.create("/api/v1/proveedores/" + creado.id()))
                .body(creado);
    }
}
