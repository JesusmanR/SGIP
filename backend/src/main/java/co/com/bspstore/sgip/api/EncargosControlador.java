package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.EncargoPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.logica.ServicioEncargos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Los encargos (RF-42, RF-69), <b>solo del administrador</b>: el vendedor, el cliente y el super
 * administrador reciben 403 en las dos rutas.
 *
 * <ul>
 *   <li>{@code GET ?estado=PENDIENTE|LLEGO}: las líneas de encargo sin entregar, con el cliente, la fecha
 *       estimada y el estado, que se calcula al consultar con la existencia del producto. El estado es
 *       obligatorio. Trae datos personales del cliente: son de esta ruta.</li>
 *   <li>{@code POST /{itemId}/entrega}: marca la entrega a mano, con quién y cuándo; la segunda vez es 409.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/caja/encargos")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class EncargosControlador {

    private final ServicioEncargos servicio;

    public EncargosControlador(ServicioEncargos servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<EncargoPendiente> listar(@RequestParam String estado) {
        return servicio.listar(estado);
    }

    @PostMapping("/{itemId}/entrega")
    public EntregaRegistrada entregar(@PathVariable Long itemId) {
        return servicio.entregar(itemId);
    }
}
