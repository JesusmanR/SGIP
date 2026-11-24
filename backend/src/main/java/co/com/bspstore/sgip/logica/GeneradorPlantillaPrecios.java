package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.datos.RepositorioImportacion;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;

/** Esqueleto: lo escribe el paso verde. */
@Component
public class GeneradorPlantillaPrecios {

    private final RepositorioImportacion importacion;

    public GeneradorPlantillaPrecios(RepositorioImportacion importacion) {
        this.importacion = importacion;
    }

    public String nombreDeArchivo(Integer empresaId, Integer proveedorId) {
        throw new UnsupportedOperationException("pendiente");
    }

    public void escribir(Integer empresaId, Integer proveedorId, OutputStream salida) throws IOException {
        throw new UnsupportedOperationException("pendiente");
    }
}
