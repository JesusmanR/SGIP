package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.EncargoPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Firma mínima del par rojo/verde de la tarea 8: el cuerpo llega con el verde.
 */
@Service
public class ServicioEncargos {

    public List<EncargoPendiente> listar(String estado) {
        throw new UnsupportedOperationException();
    }

    public EntregaRegistrada entregar(Long itemId) {
        throw new UnsupportedOperationException();
    }
}
