package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.SolicitudActivoMedioPago;
import co.com.bspstore.sgip.api.dto.SolicitudMedioPago;
import co.com.bspstore.sgip.logica.ServicioMediosPago;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Los medios de pago de la empresa (RF-43).
 *
 * Dos puertas con el mismo servicio. La de la caja ({@code /caja/medios-pago}) la
 * usan el administrador y el vendedor para elegir con qué se cobra. La de
 * administración ({@code /medios-pago}) es solo del administrador. El cliente y el
 * super administrador reciben 403 en las dos: el anotado de la clase lo exige, y
 * el de cada método de administración lo reemplaza por uno más estricto.
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class MediosPagoControlador {

    private final ServicioMediosPago servicio;

    public MediosPagoControlador(ServicioMediosPago servicio) {
        this.servicio = servicio;
    }

    /** Los medios activos, para cobrar. El de sistema nunca aparece. */
    @GetMapping("/caja/medios-pago")
    public List<MedioDePago> activos() {
        return servicio.activos();
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @GetMapping("/medios-pago")
    public List<MedioDePago> todos() {
        return servicio.todos();
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping("/medios-pago")
    public ResponseEntity<MedioDePago> crear(@Valid @RequestBody SolicitudMedioPago solicitud) {
        MedioDePago creado = servicio.crear(solicitud);
        return ResponseEntity.created(URI.create("/api/v1/medios-pago/" + creado.id())).body(creado);
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PatchMapping("/medios-pago/{medioId}")
    public MedioDePago cambiarActivo(@PathVariable Integer medioId,
                                     @Valid @RequestBody SolicitudActivoMedioPago solicitud) {
        return servicio.cambiarActivo(medioId, solicitud.activo());
    }
}
