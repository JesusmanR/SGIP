package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.modelo.Producto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/productos")
public class ProductoControlador {

    private final ServicioProducto servicio;

    public ProductoControlador(ServicioProducto servicio) {
        this.servicio = servicio;
    }

    /** Pantalla P-03. Devuelve precios, no solo datos del producto. */
    @GetMapping
    public List<ProductoEnCatalogo> listar(@RequestParam(required = false) String busqueda,
                                           @RequestParam(required = false) Integer limite) {
        return servicio.listar(busqueda, limite);
    }

    /** Pantalla P-04. */
    @GetMapping("/{productoId}")
    public ResponseEntity<ProductoEnCatalogo> ver(@PathVariable Integer productoId) {
        return servicio.buscar(productoId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Pantalla P-10, tras leer el código de barras con la cámara. */
    @GetMapping("/barras/{codigoBarras}")
    public ResponseEntity<ProductoEnCatalogo> porBarras(@PathVariable String codigoBarras) {
        return servicio.porCodigoBarras(codigoBarras)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Pantalla P-05. Crear catálogo es del administrador (matriz de la P-17). */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping
    public ResponseEntity<ProductoEnCatalogo> crear(@Valid @RequestBody SolicitudProducto solicitud) {
        Producto creado = servicio.crear(solicitud);
        // Se devuelve leyendo de la vista: así la respuesta ya trae el
        // precio que la regla de margen calculó, que es lo que la pantalla
        // necesita mostrar y el producto recién guardado no sabe.
        return servicio.buscar(creado.getId())
                .map(p -> ResponseEntity
                        .created(URI.create("/api/v1/productos/" + creado.getId()))
                        .body(p))
                .orElseGet(() -> ResponseEntity.created(
                        URI.create("/api/v1/productos/" + creado.getId())).build());
    }
}
