package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.datos.RepositorioRecibos;
import org.springframework.stereotype.Service;

/** El recibo de una venta de la empresa de la sesión (RF-70), en JSON y en PDF. */
@Service
public class ServicioRecibos {

    private final RepositorioRecibos repositorio;
    private final ReciboPdf pdf = new ReciboPdf();

    public ServicioRecibos(RepositorioRecibos repositorio) {
        this.repositorio = repositorio;
    }

    public Recibo recibo(Long ventaId) {
        throw new UnsupportedOperationException("pendiente");
    }

    public byte[] pdf(Long ventaId) {
        throw new UnsupportedOperationException("pendiente");
    }
}
