package co.com.bspstore.sgip.logica.informes;

import co.com.bspstore.sgip.api.dto.InformeInventario;
import co.com.bspstore.sgip.datos.RepositorioInformeInventario;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;

/** Provisional (rojo): lo implementa el commit siguiente. */
@Service
public class ServicioInformeInventario {

    private final RepositorioInformeInventario repositorio;
    private final Clock reloj;

    public ServicioInformeInventario(RepositorioInformeInventario repositorio, Clock reloj) {
        this.repositorio = repositorio;
        this.reloj = reloj;
    }

    public InformeInventario pagina(Integer categoriaId, String q, int pagina, int tamano) {
        throw new UnsupportedOperationException("pendiente");
    }

    public void escribirExcel(Integer categoriaId, String q, OutputStream salida) throws IOException {
        throw new UnsupportedOperationException("pendiente");
    }

    public String nombreDeArchivo() {
        throw new UnsupportedOperationException("pendiente");
    }
}
