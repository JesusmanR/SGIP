package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.CobroRegistrado;
import co.com.bspstore.sgip.api.dto.DomicilioPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Firma mínima del par rojo/verde de la tarea 8: el cuerpo llega con el verde.
 */
@Service
public class ServicioDomicilios {

    static final int TOPE_DE_LA_LISTA = 0;

    public List<DomicilioPendiente> listar(String estado) {
        throw new UnsupportedOperationException();
    }

    public EntregaRegistrada entregar(Long ventaId) {
        throw new UnsupportedOperationException();
    }

    public CobroRegistrado cobrar(Long ventaId, List<PagoSolicitado> pagos) {
        throw new UnsupportedOperationException();
    }

    static BigDecimal saldoPorCobrar(BigDecimal totalDeLaVenta) {
        return null;
    }

    static long antiguedadEnDias(LocalDateTime fecha, Clock reloj) {
        return -1;
    }
}
