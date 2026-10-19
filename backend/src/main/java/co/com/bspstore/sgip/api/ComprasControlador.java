package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.OrdenDeCompra;
import co.com.bspstore.sgip.api.dto.SolicitudOrdenCompra;
import co.com.bspstore.sgip.api.dto.SolicitudRecepcion;
import co.com.bspstore.sgip.logica.ServicioCompras;
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
import java.util.Locale;

/**
 * Órdenes de compra y recepciones (RF-22, RF-23).
 *
 * Todo es del administrador de empresa, por lo mismo que el inventario:
 * el informe le atribuye las compras a ese rol. La lectura también, al
 * contrario que en inventario —donde el vendedor necesita saber qué hay
 * en bodega—: lo que un proveedor cobra por cada referencia es
 * información de negociación, y el requisito no la concede a nadie más.
 *
 * Ningún método recibe la empresa: sale de la sesión.
 */
@RestController
@RequestMapping("/api/v1/compras/ordenes")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ComprasControlador {

    private static final int LIMITE_POR_OMISION = 50;
    private static final int LIMITE_MAXIMO = 200;

    private final ServicioCompras servicio;

    public ComprasControlador(ServicioCompras servicio) {
        this.servicio = servicio;
    }

    /**
     * Las órdenes, de la más reciente a la más antigua.
     *
     * El filtro de estado se normaliza a mayúsculas y se deja pasar tal
     * cual a la consulta, que lo compara contra el ENUM: un valor que no
     * existe no devuelve error sino cero filas, que para un filtro de
     * listado es la respuesta honesta —«no hay órdenes así»— y no obliga
     * a mantener en Java una copia de la lista de estados.
     */
    @GetMapping
    public List<OrdenDeCompra> listar(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) Integer limite) {
        String filtro = (estado == null || estado.isBlank())
                ? null : estado.trim().toUpperCase(Locale.ROOT);
        return servicio.listar(filtro, acotar(limite));
    }

    @GetMapping("/{ordenId}")
    public ResponseEntity<OrdenDeCompra> ver(@PathVariable Long ordenId) {
        return servicio.buscar(ordenId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * RF-22. La orden nace ABIERTA y no toca ninguna existencia; 409 si
     * el consecutivo ya está usado en esta empresa.
     */
    @PostMapping
    public ResponseEntity<OrdenDeCompra> registrar(
            @Valid @RequestBody SolicitudOrdenCompra solicitud) {
        OrdenDeCompra creada = servicio.registrar(solicitud);
        return ResponseEntity
                .created(URI.create("/api/v1/compras/ordenes/" + creada.ordenId()))
                .body(creada);
    }

    /**
     * RF-23. Devuelve la orden con sus líneas actualizadas y su estado
     * nuevo —PARCIAL o RECIBIDA—, que es lo que la pantalla necesita para
     * repintarse sin otra consulta.
     *
     * 404 si la orden no es de esta empresa. 400 si lo que llega no cabe
     * en lo pedido, si el producto no está en la orden, o si la orden ya
     * está recibida o anulada.
     */
    @PostMapping("/{ordenId}/recepciones")
    public ResponseEntity<OrdenDeCompra> recibir(
            @PathVariable Long ordenId,
            @Valid @RequestBody SolicitudRecepcion solicitud) {
        return servicio.recibir(ordenId, solicitud)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static int acotar(Integer limite) {
        if (limite == null || limite <= 0) {
            return LIMITE_POR_OMISION;
        }
        return Math.min(limite, LIMITE_MAXIMO);
    }
}
