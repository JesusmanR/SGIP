package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.UnidadDevuelta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
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
 * Cuántas consultas cuesta devolver (RNF-03): las mismas con una línea que con diez, y las mismas con un
 * reembolso que con tres. Los 500 ms del requisito se cubren con este conteo: en la CI no hay aserción
 * de milisegundos.
 *
 * Cuenta las sentencias de {@code JdbcClient}; los lotes ({@code JdbcTemplate.batchUpdate}) de los
 * ítems, los reembolsos y el kárdex no se cuentan una a una, que es justo lo que se quiere probar.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class DevolucionConsultasConBaseTest {

    /** Una devolución del administrador cuesta 13 sentencias (medido: la lectura previa de la clave, el turno, la venta, la clave dentro de la transacción, las líneas con lo ya devuelto, los medios, lo pagado y reembolsado sin saldo, los productos, el consecutivo con su lectura y su actualización, la devolución y la venta); el tope deja un margen corto. */
    private static final int TOPE_DE_SENTENCIAS = 14;

    @Autowired ServicioVentas ventas;
    @Autowired ServicioDevoluciones devoluciones;
    @Autowired co.com.bspstore.sgip.logica.ServicioConsultaDeVentas consulta;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @MockitoSpyBean JdbcClient jdbc;

    private CajaDePrueba caja;
    private final List<Integer> productos = new ArrayList<>();

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Consultas de devolución", jdbc, plataforma, categorias, proveedores, clientes);
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

    private VentaRegistrada vender(int lineas) {
        List<LineaSolicitada> pedidas = new ArrayList<>();
        for (int i = 0; i < lineas; i++) {
            pedidas.add(new LineaSolicitada(productos.get(i), null, 2, null, null));
        }
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId, pedidas, null,
                List.of(new PagoSolicitado(caja.efectivoId, BigDecimal.valueOf(20000L * lineas), null, null)),
                null, null, null));
    }

    /** Devuelve una unidad de cada línea, repartiendo el dinero en {@code reembolsos} pagos. */
    private long sentenciasDeDevolver(int lineas, int reembolsos) {
        VentaRegistrada v = vender(lineas);
        List<UnidadDevuelta> unidades = new ArrayList<>();
        for (int i = 0; i < lineas; i++) {
            unidades.add(new UnidadDevuelta(productos.get(i), 1));
        }
        long total = 10000L * lineas;
        List<Reembolso> repartidos = new ArrayList<>();
        for (int i = 0; i < reembolsos; i++) {
            long monto = i == reembolsos - 1 ? total - (total / reembolsos) * (reembolsos - 1) : total / reembolsos;
            repartidos.add(new Reembolso(caja.efectivoId, BigDecimal.valueOf(monto), null));
        }
        SolicitudDevolucion solicitud = new SolicitudDevolucion(UUID.randomUUID(), v.id(), unidades, "Motivo", repartidos, null);
        Mockito.clearInvocations(jdbc);
        devoluciones.registrar(solicitud);
        return sentencias();
    }

    @Test
    void unaDevolucionHaceLasMismasConsultasConUnaLineaQueConDiez() {
        // La primera devolución crea la fila del consecutivo y calienta lo que haya que calentar.
        sentenciasDeDevolver(1, 1);

        long conUna = sentenciasDeDevolver(1, 1);
        long conDiez = sentenciasDeDevolver(10, 1);

        assertEquals(conUna, conDiez, "con una línea fueron " + conUna + " consultas y con diez, " + conDiez);
        assertTrue(conDiez <= TOPE_DE_SENTENCIAS, "una devolución costó " + conDiez + " consultas");
    }

    @Test
    void unaDevolucionHaceLasMismasConsultasConUnReembolsoQueConTres() {
        sentenciasDeDevolver(3, 1);

        long conUno = sentenciasDeDevolver(3, 1);
        long conTres = sentenciasDeDevolver(3, 3);

        assertEquals(conUno, conTres, "con un reembolso fueron " + conUno + " consultas y con tres, " + conTres);
    }

    @Test
    void unaAnulacionTotalCuestaLoMismoQueUnaDevolucionDeTodasLasLineas() {
        sentenciasDeDevolver(1, 1);
        VentaRegistrada v = vender(10);
        SolicitudDevolucion anulacion = new SolicitudDevolucion(UUID.randomUUID(), v.id(), List.of(), "Motivo",
                List.of(new Reembolso(caja.efectivoId, new BigDecimal("200000"), null)), null);
        Mockito.clearInvocations(jdbc);

        devoluciones.registrar(anulacion);

        assertTrue(sentencias() <= TOPE_DE_SENTENCIAS, "una anulación de diez líneas costó " + sentencias() + " consultas");
    }

    private long sentenciasDeConsultar(int lineas) {
        VentaRegistrada v = vender(lineas);
        Mockito.clearInvocations(jdbc);
        consulta.porConsecutivo(v.consecutivo());
        return sentencias();
    }

    @Test
    void consultarUnaVentaHaceLasMismasConsultasConUnaLineaQueConDiez() {
        sentenciasDeConsultar(1);

        long conUna = sentenciasDeConsultar(1);
        long conDiez = sentenciasDeConsultar(10);

        assertEquals(conUna, conDiez, "con una línea fueron " + conUna + " consultas y con diez, " + conDiez);
        assertTrue(conDiez <= 3, "consultar una venta costó " + conDiez + " consultas: la venta, sus líneas y sus pagos");
    }

    @Test
    void consultarUnaVentaConDevolucionesHaceLasMismasConsultas() {
        VentaRegistrada v = vender(10);
        List<UnidadDevuelta> unidades = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            unidades.add(new UnidadDevuelta(productos.get(i), 1));
        }
        devoluciones.registrar(new SolicitudDevolucion(UUID.randomUUID(), v.id(), unidades, "Motivo",
                List.of(new Reembolso(caja.efectivoId, new BigDecimal("100000"), null)), null));
        Mockito.clearInvocations(jdbc);

        consulta.porConsecutivo(v.consecutivo());

        assertTrue(sentencias() <= 3, "consultar una venta devuelta costó " + sentencias() + " consultas");
    }
}
