package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import co.com.bspstore.sgip.logica.ServicioEncargos;
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
 * Cuántas consultas cuestan las listas, el cobro y la entrega de los domicilios y los encargos
 * (RNF-03): las mismas con una fila que con veinte, y con un pago que con tres. Los 500 ms del
 * requisito se cubren con este conteo; la CI no mide milisegundos.
 *
 * Se cuenta como {@code VentaConsultasConBaseTest}: con un espía sobre {@code JdbcClient}. Crea su
 * empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class ListasDeCajaConsultasConBaseTest {

    private static final Domicilio CONTRA_ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", true);

    @Autowired ServicioVentas ventas;
    @Autowired ServicioDomicilios domicilios;
    @Autowired ServicioEncargos encargos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @MockitoSpyBean JdbcClient jdbc;

    private CajaDePrueba caja;
    private Integer producto;
    private Integer sinExistencia;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Consultas de las listas", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        producto = caja.producto("A", 500, "1000");
        sinExistencia = caja.producto("B", 0, "1000");
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

    private List<VentaRegistrada> contraEntrega(int cuantas) {
        List<VentaRegistrada> hechas = new ArrayList<>();
        for (int i = 0; i < cuantas; i++) {
            hechas.add(ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                    List.of(new LineaSolicitada(producto, null, 1, null, null)), null, List.of(), null, null, CONTRA_ENTREGA)));
        }
        return hechas;
    }

    private void venderEncargos(int cuantos) {
        for (int i = 0; i < cuantos; i++) {
            ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                    List.of(new LineaSolicitada(sinExistencia, null, 1, null, LocalDate.now().plusDays(7))), null,
                    List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("1000"), null, null)), "Sin existencia", null, null));
        }
    }

    private long sentenciasDe(Runnable operacion) {
        Mockito.clearInvocations(jdbc);
        operacion.run();
        return sentencias();
    }

    @Test
    void laListaDeDomiciliosHaceLasMismasConsultasConUnaFilaQueConVeinte() {
        contraEntrega(1);
        domicilios.listar("POR_COBRAR");
        long conUna = sentenciasDe(() -> assertEquals(1, domicilios.listar("POR_COBRAR").size()));

        contraEntrega(19);
        long conVeinte = sentenciasDe(() -> assertEquals(20, domicilios.listar("POR_COBRAR").size()));
        long despachar = sentenciasDe(() -> assertEquals(20, domicilios.listar("POR_DESPACHAR").size()));

        assertEquals(conUna, conVeinte, "con una fila fueron " + conUna + " consultas y con veinte, " + conVeinte);
        assertEquals(conUna, despachar, "las dos listas cuestan lo mismo");
        assertTrue(conVeinte <= 2, "una lista costó " + conVeinte + " consultas");
    }

    @Test
    void laListaDeEncargosHaceLasMismasConsultasConUnaFilaQueConDiez() {
        venderEncargos(1);
        encargos.listar("PENDIENTE");
        long conUna = sentenciasDe(() -> assertEquals(1, encargos.listar("PENDIENTE").size()));

        venderEncargos(9);
        long conDiez = sentenciasDe(() -> assertEquals(10, encargos.listar("PENDIENTE").size()));
        long llego = sentenciasDe(() -> assertEquals(0, encargos.listar("LLEGO").size()));

        assertEquals(conUna, conDiez, "con una fila fueron " + conUna + " consultas y con diez, " + conDiez);
        assertEquals(conUna, llego);
        assertTrue(conDiez <= 2, "una lista costó " + conDiez + " consultas");
    }

    @Test
    void elCobroHaceLasMismasConsultasConUnPagoQueConTres() {
        List<VentaRegistrada> hechas = contraEntrega(3);
        domicilios.cobrar(hechas.get(0).id(), List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("1000"), null, null)));

        long conUno = sentenciasDe(() -> domicilios.cobrar(hechas.get(1).id(),
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("1000"), null, null))));
        List<VentaRegistrada> otras = contraEntrega(1);
        long conTres = sentenciasDe(() -> domicilios.cobrar(otras.get(0).id(), List.of(
                new PagoSolicitado(caja.efectivoId, new BigDecimal("300"), null, null),
                new PagoSolicitado(caja.efectivoId, new BigDecimal("300"), null, null),
                new PagoSolicitado(caja.tarjetaId, new BigDecimal("400"), null, "APROB-1"))));

        assertEquals(conUno, conTres, "con un pago fueron " + conUno + " consultas y con tres, " + conTres);
        assertTrue(conTres <= 8, "un cobro costó " + conTres + " consultas");
    }

    @Test
    void laEntregaDeUnDomicilioYDeUnEncargoCuestanPocasConsultas() {
        VentaRegistrada r = contraEntrega(1).get(0);
        venderEncargos(1);
        Long item = jdbc.sql("SELECT item_id FROM venta_items WHERE empresa_id = :e AND cantidad_encargo > 0")
                .param("e", caja.empresa.empresaId).query(Long.class).single();

        long domicilio = sentenciasDe(() -> domicilios.entregar(r.id()));
        long encargo = sentenciasDe(() -> encargos.entregar(item));

        assertTrue(domicilio <= 3, "entregar un domicilio costó " + domicilio + " consultas");
        assertTrue(encargo <= 3, "entregar un encargo costó " + encargo + " consultas");
    }
}
