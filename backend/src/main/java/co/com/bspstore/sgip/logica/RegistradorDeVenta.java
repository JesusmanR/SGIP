package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.datos.RepositorioConsecutivos;
import co.com.bspstore.sgip.datos.RepositorioInventario;
import co.com.bspstore.sgip.datos.RepositorioVentas;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** Firma vacía: la implementación llega con el commit de la tarea. */
@Service
public class RegistradorDeVenta {

    public RegistradorDeVenta(ServicioTurnos turnos, RepositorioVentas ventas, RepositorioConsecutivos consecutivos,
                              RepositorioInventario inventario, RepositorioClientes clientes, Clock reloj) {
    }

    public ResultadoDeVenta registrar(SolicitudVenta solicitud, Autorizacion autorizacion) {
        throw new UnsupportedOperationException("pendiente");
    }

    public ResultadoDeVenta existente(SolicitudVenta solicitud) {
        throw new UnsupportedOperationException("pendiente");
    }
}
