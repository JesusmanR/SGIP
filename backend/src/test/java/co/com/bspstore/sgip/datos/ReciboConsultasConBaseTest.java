package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ServicioRecibos;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cuántas consultas cuesta el recibo (RNF-03): las mismas con una línea que con veinte, y las mismas en JSON que
 * en PDF. Los 500 ms del requisito se cubren con este conteo: en la CI no hay aserción de milisegundos.
 *
 * Cuenta las sentencias de {@code JdbcClient}. El recibo lee con tres: la venta con su empresa, su cajero y su
 * cliente; sus líneas con el nombre del producto, y sus pagos con el nombre del medio.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class ReciboConsultasConBaseTest {

    private static final int TOPE_DE_SENTENCIAS = 3;
    private static final int LINEAS = 20;

    @Autowired ServicioVentas ventas;
    @Autowired ServicioRecibos recibos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @MockitoSpyBean JdbcClient jdbc;

    private CajaDePrueba caja;
    private final List<Integer> productos = new ArrayList<>();

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Consultas del recibo", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        for (int i = 0; i < LINEAS; i++) {
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

    private VentaRegistrada vender(int lineas) {
        List<LineaSolicitada> pedidas = new ArrayList<>();
        for (int i = 0; i < lineas; i++) {
            pedidas.add(new LineaSolicitada(productos.get(i), null, 2, null, null));
        }
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId, pedidas, null,
                List.of(new PagoSolicitado(caja.efectivoId, BigDecimal.valueOf(20000L * lineas), null, null)),
                null, null, null));
    }

    private long sentenciasDelJson(int lineas) {
        VentaRegistrada v = vender(lineas);
        Mockito.clearInvocations(jdbc);
        recibos.recibo(v.id());
        return sentencias();
    }

    private long sentenciasDelPdf(int lineas) {
        VentaRegistrada v = vender(lineas);
        Mockito.clearInvocations(jdbc);
        recibos.pdf(v.id());
        return sentencias();
    }

    @Test
    void elReciboHaceLasMismasConsultasConUnaLineaQueConVeinte() {
        sentenciasDelJson(1);

        long conUna = sentenciasDelJson(1);
        long conVeinte = sentenciasDelJson(LINEAS);

        assertEquals(conUna, conVeinte, "con una línea fueron " + conUna + " consultas y con veinte, " + conVeinte);
        assertTrue(conVeinte <= TOPE_DE_SENTENCIAS,
                "el recibo costó " + conVeinte + " consultas: la venta, sus líneas y sus pagos");
    }

    @Test
    void elPdfHaceLasMismasConsultasQueElJson() {
        sentenciasDelPdf(1);

        long conUna = sentenciasDelPdf(1);
        long conVeinte = sentenciasDelPdf(LINEAS);

        assertEquals(conUna, conVeinte, "con una línea fueron " + conUna + " consultas y con veinte, " + conVeinte);
        assertTrue(conVeinte <= TOPE_DE_SENTENCIAS, "el PDF costó " + conVeinte + " consultas");
    }
}
