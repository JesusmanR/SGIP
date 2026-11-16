package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CobroRegistrado;
import co.com.bspstore.sgip.api.dto.DomicilioPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Los domicilios de la caja (RF-44, RF-69): lo que falta por despachar y por cobrar, la marca de entrega
 * y el cobro de un domicilio contra entrega. Los usan el administrador y el vendedor; el cliente y el
 * super administrador reciben 403.
 *
 * <ul>
 *   <li>{@code GET ?estado=POR_DESPACHAR|POR_COBRAR}: la lista, con el valor por cobrar y la antigüedad
 *       en días. El estado es obligatorio. Trae datos personales del cliente: son de esta ruta.</li>
 *   <li>{@code POST /{ventaId}/entrega}: quién y cuándo; la segunda vez es 409.</li>
 *   <li>{@code POST /{ventaId}/cobro}: el cuerpo es la lista de pagos, que tiene que sumar el saldo por
 *       cobrar completo, en el turno abierto de quien cobra; 201 con el cobro.</li>
 * </ul>
 *
 * Una empresa en consulta lee las listas y no entrega ni cobra ({@code FiltroEmpresaActiva}).
 */
@RestController
@RequestMapping("/api/v1/caja/domicilios")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class DomiciliosControlador {

    private final ServicioDomicilios servicio;

    public DomiciliosControlador(ServicioDomicilios servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<DomicilioPendiente> listar(@RequestParam String estado) {
        return servicio.listar(estado);
    }

    @PostMapping("/{ventaId}/entrega")
    public EntregaRegistrada entregar(@PathVariable Long ventaId) {
        return servicio.entregar(ventaId);
    }

    @PostMapping("/{ventaId}/cobro")
    public ResponseEntity<CobroRegistrado> cobrar(@PathVariable Long ventaId, @RequestBody List<PagoSolicitado> pagos) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.cobrar(ventaId, pagos));
    }
}
