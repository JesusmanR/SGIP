package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** Firma vacía: la implementación llega con el commit de la tarea. */
@Service
public class ServicioVentas {

    public ServicioVentas(ServicioAutorizacion autorizacion, RegistradorDeVenta registrador, Clock reloj) {
    }

    public VentaRegistrada registrar(SolicitudVenta solicitud) {
        throw new UnsupportedOperationException("pendiente");
    }

    public ResultadoDeVenta procesar(SolicitudVenta solicitud) {
        throw new UnsupportedOperationException("pendiente");
    }
}
