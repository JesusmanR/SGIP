package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import org.springframework.stereotype.Service;

/** Firma mínima: la devolución dentro de una transacción. */
@Service
public class RegistradorDeDevolucion {

    public DevolucionRegistrada registrar(SolicitudDevolucion solicitud, Autorizacion autorizacion) {
        throw new UnsupportedOperationException("pendiente");
    }
}
