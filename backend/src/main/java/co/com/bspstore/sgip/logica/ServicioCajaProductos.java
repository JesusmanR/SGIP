package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ProductoParaCaja;
import co.com.bspstore.sgip.datos.ConsultaCatalogo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Esqueleto para el rojo: solo las firmas. */
@Service
public class ServicioCajaProductos {

    public ServicioCajaProductos(ConsultaCatalogo catalogo) {
    }

    public Optional<ProductoParaCaja> porCodigo(String codigo) {
        throw new UnsupportedOperationException();
    }

    public List<ProductoParaCaja> buscar(String texto, int limite) {
        throw new UnsupportedOperationException();
    }
}
