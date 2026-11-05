package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductoControladorTest {

    private final ServicioProducto servicio = mock(ServicioProducto.class);
    private final ProductoControlador controlador = new ProductoControlador(servicio);

    private static ProductoEnCatalogo enLaVista(int existencia) {
        return new ProductoEnCatalogo(55, "ARZ-500", "ARZ-500", "Arroz Diana", "500 g", null,
                null, 4, "Granos", 9, "Distribuidora", new BigDecimal("3100.00"), existencia, 20,
                "ACTIVO", null, null, null, null, null, null, null, null, 0, 0, 0, 0, null, 0,
                null, null, null, null, null, null);
    }

    /**
     * El producto recién guardado nace en cero: es el kárdex quien sube la
     * existencia, y la entidad en memoria no se entera. La respuesta tiene
     * que salir de releer la vista, o quien da de alta 120 unidades vería 0.
     */
    @Test
    void laRespuestaDelAltaMuestraLaExistenciaInicialQueDejoElKardex() {
        Producto guardado = new Producto(3, 4, 9, "ARZ-500", "ARZ-500", "Arroz Diana",
                new BigDecimal("3100.00"));
        ReflectionTestUtils.setField(guardado, "id", 55);
        when(servicio.crear(org.mockito.ArgumentMatchers.any(SolicitudProducto.class)))
                .thenReturn(guardado);
        when(servicio.buscar(55)).thenReturn(Optional.of(enLaVista(120)));

        ResponseEntity<ProductoEnCatalogo> respuesta = controlador.crear(new SolicitudProducto(
                4, 9, "ARZ-500", "ARZ-500", null, "Arroz Diana", "500 g", null,
                new BigDecimal("3100.00"), 120, 20,
                new SolicitudPrecio(new BigDecimal("20"), null), null));

        assertEquals(201, respuesta.getStatusCode().value());
        assertEquals(0, guardado.getExistencia(), "la entidad en memoria sigue en cero");
        assertEquals(120, respuesta.getBody().existencia());
    }
}
