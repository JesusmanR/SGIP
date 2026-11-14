package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ServicioVentas;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Firma vacía: la implementación llega con el commit de la tarea. */
@RestController
@RequestMapping("/api/v1/caja/ventas")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class VentasControlador {

    private final ServicioVentas servicio;

    public VentasControlador(ServicioVentas servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<VentaRegistrada> registrar(@Valid @RequestBody SolicitudVenta solicitud) {
        throw new UnsupportedOperationException("pendiente");
    }
}
