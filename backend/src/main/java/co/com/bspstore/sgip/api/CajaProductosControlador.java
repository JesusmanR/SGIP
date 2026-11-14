package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ProductoParaCaja;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioCajaProductos;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Buscar productos desde la caja (RF-31, RF-32): una sola ruta con dos formas.
 *
 * <ul>
 *   <li>{@code ?codigo=} devuelve el producto cuyo código de barras o código interno es ese,
 *       o 404;</li>
 *   <li>{@code ?q=} devuelve hasta 20 coincidencias por texto (la lista puede venir vacía).</li>
 * </ul>
 *
 * Se manda una u otra, no las dos ni ninguna: 400. Los usan el administrador y el vendedor; el
 * cliente y el super administrador reciben 403. Ninguna respuesta lleva costo, margen ni
 * utilidad, tampoco la del administrador.
 */
@RestController
@RequestMapping("/api/v1/caja/productos")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class CajaProductosControlador {

    private static final String UNO_DE_LOS_DOS =
            "Indica el código del producto o el texto a buscar, solo uno de los dos.";

    private final ServicioCajaProductos servicio;

    public CajaProductosControlador(ServicioCajaProductos servicio) {
        this.servicio = servicio;
    }

    /**
     * Un producto con {@code ?codigo=} o una lista con {@code ?q=}. El tipo es comodín porque
     * las dos formas tienen respuestas distintas; el cuerpo es siempre un
     * {@link ProductoParaCaja} o una lista de ellos.
     */
    @GetMapping
    public ResponseEntity<?> buscar(@RequestParam(required = false) String codigo,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) Integer limite) {
        // Presente y vacío cuenta como presente: «?codigo=» vacío es un código inválido (400
        // del servicio), no «ninguno de los dos».
        if ((codigo == null) == (q == null)) {
            throw new FiltroInvalidoException(UNO_DE_LOS_DOS);
        }
        if (codigo != null) {
            ProductoParaCaja producto = servicio.porCodigo(codigo)
                    .orElseThrow(() -> new RecursoNoEncontradoException("No hay ningún producto con ese código."));
            return ResponseEntity.ok(producto);
        }
        List<ProductoParaCaja> encontrados = servicio.buscar(q, limite == null ? 0 : limite);
        return ResponseEntity.ok(encontrados);
    }
}
