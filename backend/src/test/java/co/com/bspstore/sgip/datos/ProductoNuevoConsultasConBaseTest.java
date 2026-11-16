package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.ProductoNuevo;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.logica.ServicioVentas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cuántas consultas cuesta una venta con productos nuevos (RNF-03): el producto nuevo cuesta un
 * número fijo y lo demás de la venta no cambia con él. Las mismas con un producto del catálogo que
 * con diez, y cada producto nuevo suma lo mismo que el anterior. El tope de diez por venta acota
 * lo que una sola petición puede pedirle a la base.
 *
 * Se cuenta como {@code VentaConsultasConBaseTest}: con un espía sobre {@code JdbcClient}. Crea su
 * empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class ProductoNuevoConsultasConBaseTest {

    @Autowired ServicioVentas ventas;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @MockitoSpyBean JdbcClient jdbc;

    private CajaDePrueba caja;
    private final List<Integer> productos = new ArrayList<>();

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Consultas del producto nuevo", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        for (int i = 0; i < 10; i++) {
            productos.add(caja.producto("P" + i, 100, "10000"));
        }
        caja.entrarComoAdministrador();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
    }

    private long sentencias() {
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(llamada -> llamada.getMethod().getName().equals("sql"))
                .count();
    }

    private long sentenciasDeUnaVenta(int delCatalogo, int nuevos) {
        List<LineaSolicitada> lineas = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < delCatalogo; i++) {
            lineas.add(new LineaSolicitada(productos.get(i), null, 1, null, null));
            total += 10000;
        }
        for (int i = 0; i < nuevos; i++) {
            lineas.add(new LineaSolicitada(null, new ProductoNuevo("Nuevo " + UUID.randomUUID(), new BigDecimal("500"), null),
                    1, null, LocalDate.now().plusDays(7)));
            total += 500;
        }
        SolicitudVenta solicitud = new SolicitudVenta(UUID.randomUUID(), null, lineas, null,
                List.of(new PagoSolicitado(caja.efectivoId, BigDecimal.valueOf(total), null, null)),
                nuevos > 0 ? "Producto nuevo" : null, null, null);
        Mockito.clearInvocations(jdbc);
        ventas.registrar(solicitud);
        return sentencias();
    }

    @Test
    void unaVentaConUnProductoNuevoHaceLasMismasConsultasConUnaLineaDelCatalogoQueConDiez() {
        // La primera venta crea las filas de los consecutivos y calienta lo que haya que calentar.
        sentenciasDeUnaVenta(1, 1);

        long conUna = sentenciasDeUnaVenta(1, 1);
        long conDiez = sentenciasDeUnaVenta(9, 1);

        assertEquals(conUna, conDiez, "con una línea del catálogo fueron " + conUna + " consultas y con nueve, " + conDiez);
    }

    @Test
    void cadaProductoNuevoSumaLasMismasConsultasQueElAnterior() {
        sentenciasDeUnaVenta(0, 1);

        long conUno = sentenciasDeUnaVenta(0, 1);
        long conDos = sentenciasDeUnaVenta(0, 2);
        long conTres = sentenciasDeUnaVenta(0, 3);

        assertEquals(conDos - conUno, conTres - conDos, "uno " + conUno + ", dos " + conDos + ", tres " + conTres);
        assertTrue(conDos > conUno, "un producto nuevo cuesta unas consultas propias");
    }

    @Test
    void elTopeDeProductosNuevosAcotaLasConsultasDeUnaSolaPeticion() {
        sentenciasDeUnaVenta(0, 1);

        long conUno = sentenciasDeUnaVenta(0, 1);
        long conDiez = sentenciasDeUnaVenta(0, 10);

        long porProducto = (conDiez - conUno) / 9;
        assertTrue(porProducto <= 14, "un producto nuevo costó " + porProducto + " consultas");
        assertTrue(conDiez <= 12 + 10 * 14, "una venta con diez productos nuevos costó " + conDiez + " consultas");
    }
}
