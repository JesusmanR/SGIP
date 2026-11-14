package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioVentas;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registrar una venta en el mostrador (RF-41). Los usan el administrador y el vendedor; el cliente y
 * el super administrador reciben 403. 201 con la venta nueva, o 200 con la misma venta si la clave
 * de idempotencia ya la había registrado.
 */
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
        ResultadoDeVenta resultado = servicio.procesar(solicitud);
        return ResponseEntity.status(resultado.yaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(resultado.venta());
    }
}
