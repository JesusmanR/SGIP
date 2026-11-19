package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import org.springframework.stereotype.Service;

/** Firma mínima: la fachada sin transacción de la devolución. */
@Service
public class ServicioDevoluciones {

    private final ServicioAutorizacion autorizacion;
    private final RegistradorDeDevolucion registrador;

    public ServicioDevoluciones(ServicioAutorizacion autorizacion, RegistradorDeDevolucion registrador) {
        this.autorizacion = autorizacion;
        this.registrador = registrador;
    }

    public DevolucionRegistrada registrar(SolicitudDevolucion solicitud) {
        throw new UnsupportedOperationException("pendiente");
    }
}
