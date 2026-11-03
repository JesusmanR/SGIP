package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CuadreDeInventario;
import co.com.bspstore.sgip.logica.ServicioInventario;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventarioControladorTest {

    private final ServicioInventario servicio = mock(ServicioInventario.class);
    private final InventarioControlador controlador = new InventarioControlador(servicio);

    @Test
    void elCuadreDeUnProductoPropioSeEntregaTalCual() {
        var cuadre = new CuadreDeInventario(42, 40, 30, false);
        when(servicio.cuadre(42)).thenReturn(Optional.of(cuadre));

        ResponseEntity<CuadreDeInventario> respuesta = controlador.cuadre(42);

        assertEquals(200, respuesta.getStatusCode().value());
        assertEquals(cuadre, respuesta.getBody());
    }

    @Test
    void elCuadreDeUnProductoAjenoEsUn404() {
        when(servicio.cuadre(42)).thenReturn(Optional.empty());

        assertEquals(404, controlador.cuadre(42).getStatusCode().value());
    }

    /**
     * Un producto propio, recién creado y sin movimientos, también tiene
     * cuadre: ya no se responde 404 «por no tener historia» como antes.
     */
    @Test
    void unProductoEnCeroSinMovimientosTieneCuadreYNoUn404() {
        when(servicio.cuadre(42)).thenReturn(Optional.of(new CuadreDeInventario(42, 0, 0, true)));

        assertEquals(200, controlador.cuadre(42).getStatusCode().value());
    }
}
