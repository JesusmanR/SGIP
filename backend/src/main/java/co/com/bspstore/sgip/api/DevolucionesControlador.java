package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioSaldoAFavor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Firma mínima: sin rutas todavía. */
@RestController
@RequestMapping("/api/v1/caja")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class DevolucionesControlador {

    private final ServicioDevoluciones devoluciones;
    private final ServicioSaldoAFavor saldos;

    public DevolucionesControlador(ServicioDevoluciones devoluciones, ServicioSaldoAFavor saldos) {
        this.devoluciones = devoluciones;
        this.saldos = saldos;
    }
}
