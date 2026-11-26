package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.InformeInventario;
import co.com.bspstore.sgip.api.dto.InformeInventario.Fila;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.function.Consumer;

/** Provisional (rojo): lo implementa el commit siguiente. */
@Repository
public class RepositorioInformeInventario {

    private final JdbcClient jdbc;

    public RepositorioInformeInventario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public InformeInventario pagina(Integer empresa, Integer categoriaId, String q, int pagina, int tamano) {
        throw new UnsupportedOperationException("pendiente");
    }

    public InformeInventario.Totales totales(Integer empresa, Integer categoriaId, String q) {
        throw new UnsupportedOperationException("pendiente");
    }

    public void recorrer(Integer empresa, Integer categoriaId, String q, Consumer<Fila> fila) {
        throw new UnsupportedOperationException("pendiente");
    }

    public Optional<String> nombreDeCategoria(Integer empresa, Integer categoriaId) {
        throw new UnsupportedOperationException("pendiente");
    }
}
