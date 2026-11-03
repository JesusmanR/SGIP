package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.ProductoParaVenta;
import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.api.dto.VistaDeProducto;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.modelo.Producto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/productos")
public class ProductoControlador {

    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    private final ServicioProducto servicio;

    public ProductoControlador(ServicioProducto servicio) {
        this.servicio = servicio;
    }

    /**
     * Pantalla P-03. Devuelve precios, no solo datos del producto.
     *
     * Los cuatro filtros son opcionales y se combinan entre sí (RF-16).
     *
     * El costo y el proveedor son del administrador. Filtrar por proveedor
     * también lo es: aunque la respuesta no lo muestre, «los productos de
     * este proveedor» dice a quién se le compra cada cosa.
     */
    @GetMapping
    public List<VistaDeProducto> listar(@RequestParam(required = false) String busqueda,
                                        @RequestParam(required = false) Integer categoria,
                                        @RequestParam(required = false) Integer proveedor,
                                        @RequestParam(required = false) String estado,
                                        @RequestParam(required = false) Integer limite) {
        boolean completa = veTodo();
        if (proveedor != null && !completa) {
            throw new AccessDeniedException("Filtrar por proveedor es del administrador.");
        }
        return servicio.listar(busqueda, categoria, proveedor, estado, limite).stream()
                .map(p -> segunQuienPregunta(p, completa))
                .toList();
    }

    /** Pantalla P-04. */
    @GetMapping("/{productoId}")
    public ResponseEntity<VistaDeProducto> ver(@PathVariable Integer productoId) {
        boolean completa = veTodo();
        return servicio.buscar(productoId)
                .map(p -> segunQuienPregunta(p, completa))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Pantalla P-10, tras leer el código de barras con la cámara. */
    @GetMapping("/barras/{codigoBarras}")
    public ResponseEntity<VistaDeProducto> porBarras(@PathVariable String codigoBarras) {
        boolean completa = veTodo();
        return servicio.porCodigoBarras(codigoBarras)
                .map(p -> segunQuienPregunta(p, completa))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Solo el administrador recibe la forma completa. Cualquier otro rol,
     * y también un rol desconocido o ausente, recibe la de venta: se
     * enumera quién puede ver el costo y no quién no puede, para que un
     * rol que se añada mañana nazca sin verlo.
     */
    private static boolean veTodo() {
        return ContextoEmpresa.actual()
                .map(UsuarioAutenticado::rol)
                .filter(ROL_ADMINISTRADOR::equals)
                .isPresent();
    }

    private static VistaDeProducto segunQuienPregunta(ProductoEnCatalogo p, boolean completa) {
        return completa ? p : ProductoParaVenta.de(p);
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

    /**
     * Pantalla P-05 en modo edición (RF-15).
     *
     * Devuelve el producto leído de la vista, igual que el alta, porque
     * cambiar el costo cambia el precio de venta y la pantalla tiene que
     * poder mostrarlo sin volver a preguntar.
     */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PutMapping("/{productoId}")
    public ResponseEntity<ProductoEnCatalogo> editar(
            @PathVariable Integer productoId,
            @Valid @RequestBody SolicitudEdicionProducto solicitud) {
        return servicio.actualizar(productoId, solicitud)
                .flatMap(p -> servicio.buscar(p.getId()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
