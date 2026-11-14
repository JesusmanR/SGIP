package co.com.bspstore.sgip.datos;

import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Firma vacía: la implementación llega con el commit de la tarea. */
@Repository
public class RepositorioVentas {

    public record VentaPorClave(Long ventaId, String huella) {}

    public Optional<VentaPorClave> buscarPorClave(Integer empresaId, String clave) {
        throw new UnsupportedOperationException("pendiente");
    }
}
