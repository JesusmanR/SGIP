package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cuántas consultas cuesta registrar una venta (RNF-03): las mismas con una línea que con diez,
 * y las mismas con un pago que con tres. Los 500 ms del requisito se cubren con este conteo; la
 * CI no mide milisegundos.
 *
 * Las inserciones de líneas, pagos y kárdex, y la actualización de las existencias, van por lotes
 * ({@code batchUpdate}, como la importación de listas) y no pasan por {@code JdbcClient.sql}, que
 * es lo que se cuenta, con un espía sobre el bean como en {@code ListadoDeOrdenesConsultasConBaseTest}.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class VentaConsultasConBaseTest {

    /** Una venta de un administrador sin nada que autorizar cuesta 10 (medido); el tope deja un margen corto. */
    private static final int TOPE_DE_SENTENCIAS = 12;

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
        caja = CajaDePrueba.crear("Consultas de venta", jdbc, plataforma, categorias, proveedores, clientes);
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

    private long sentenciasDeUnaVentaDe(int lineas, int pagos) {
        List<LineaSolicitada> pedidas = new ArrayList<>();
        for (int i = 0; i < lineas; i++) {
            pedidas.add(new LineaSolicitada(productos.get(i), null, 1, null, null));
        }
        List<PagoSolicitado> repartidos = new ArrayList<>();
        long total = 10000L * lineas;
        for (int i = 0; i < pagos; i++) {
            long monto = i == pagos - 1 ? total - (total / pagos) * (pagos - 1) : total / pagos;
            repartidos.add(new PagoSolicitado(caja.efectivoId, BigDecimal.valueOf(monto), null, null));
        }
        SolicitudVenta solicitud = new SolicitudVenta(UUID.randomUUID(), null, pedidas, null, repartidos, null, null, null);
        Mockito.clearInvocations(jdbc);
        ventas.registrar(solicitud);
        return sentencias();
    }

    @Test
    void unaVentaHaceLasMismasConsultasConUnaLineaQueConDiez() {
        // La primera venta crea la fila del consecutivo y calienta lo que haya que calentar.
        sentenciasDeUnaVentaDe(1, 1);

        long conUna = sentenciasDeUnaVentaDe(1, 1);
        long conDiez = sentenciasDeUnaVentaDe(10, 1);

        assertEquals(conUna, conDiez, "con una línea fueron " + conUna + " consultas y con diez, " + conDiez);
        assertTrue(conDiez <= TOPE_DE_SENTENCIAS, "una venta costó " + conDiez + " consultas");
    }

    @Test
    void unaVentaHaceLasMismasConsultasConUnPagoQueConTres() {
        sentenciasDeUnaVentaDe(1, 1);

        long conUno = sentenciasDeUnaVentaDe(3, 1);
        long conTres = sentenciasDeUnaVentaDe(3, 3);

        assertEquals(conUno, conTres, "con un pago fueron " + conUno + " consultas y con tres, " + conTres);
    }

    @Test
    void laPrimeraVentaDeLaEmpresaSoloAñadeLaCreacionDeSuConsecutivo() {
        long primera = sentenciasDeUnaVentaDe(1, 1);
        long segunda = sentenciasDeUnaVentaDe(1, 1);

        assertTrue(primera - segunda <= 2, "crear la fila del consecutivo cuesta, a lo sumo, un INSERT y una relectura");
        assertTrue(primera >= segunda);
    }
}
