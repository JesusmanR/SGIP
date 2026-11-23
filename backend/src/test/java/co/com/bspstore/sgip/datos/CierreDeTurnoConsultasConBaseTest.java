package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudCierre;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.logica.ServicioTurnos;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cuántas consultas cuestan el resumen, el cierre y la lista de turnos (RNF-03): las mismas con un pago
 * que con quince, y con un turno que con seis. Los 500 ms del requisito se cubren con este conteo; la CI
 * no mide milisegundos.
 *
 * Se cuenta como {@code ListasDeCajaConsultasConBaseTest}: con un espía sobre {@code JdbcClient}. Crea su
 * empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CierreDeTurnoConsultasConBaseTest {

    @Autowired ServicioTurnos turnos;
    @Autowired ServicioVentas ventas;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @MockitoSpyBean JdbcClient jdbc;

    private CajaDePrueba caja;
    private Integer producto;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Consultas del cierre", jdbc, plataforma, categorias, proveedores, clientes);
        producto = caja.producto("A", 500, "1000");
        caja.entrarComoAdministrador();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
    }

    private long sentenciasDe(Runnable operacion) {
        Mockito.clearInvocations(jdbc);
        operacion.run();
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(llamada -> llamada.getMethod().getName().equals("sql"))
                .count();
    }

    /** Ventas de 3 000 pagadas con los tres medios, para que el resumen agrupe por tipo. */
    private void vender(int cuantas) {
        for (int i = 0; i < cuantas; i++) {
            ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                    List.of(new LineaSolicitada(producto, null, 3, null, null)), null,
                    List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("1000"), null, null),
                            new PagoSolicitado(caja.tarjetaId, new BigDecimal("1000"), null, "AUT-" + i),
                            new PagoSolicitado(caja.transferenciaId, new BigDecimal("1000"), null, "TRF-" + i)),
                    null, null, null));
        }
    }

    @Test
    void elResumenHaceLasMismasConsultasConUnPagoQueConQuince() {
        caja.abrirTurno(caja.administradorId, "1000");
        vender(1);
        turnos.resumenActual();
        long conUno = sentenciasDe(() -> assertEquals(3, turnos.resumenActual().porMedio().size()));

        vender(14);
        long conQuince = sentenciasDe(() -> assertEquals(3, turnos.resumenActual().porMedio().size()));

        assertEquals(conUno, conQuince, "con un pago fueron " + conUno + " consultas y con quince, " + conQuince);
        assertTrue(conQuince <= 4, "el resumen costó " + conQuince + " consultas");
    }

    @Test
    void elCierreHaceLasMismasConsultasConUnPagoQueConQuince() {
        caja.abrirTurno(caja.administradorId, "0");
        vender(1);
        long conUno = sentenciasDe(() -> turnos.cerrar(new SolicitudCierre(new BigDecimal("1000"), null)));

        caja.abrirTurno(caja.administradorId, "0");
        vender(15);
        long conQuince = sentenciasDe(() -> turnos.cerrar(new SolicitudCierre(new BigDecimal("15000"), null)));

        assertEquals(conUno, conQuince, "con un pago fueron " + conUno + " consultas y con quince, " + conQuince);
        assertTrue(conQuince <= 8, "el cierre costó " + conQuince + " consultas");
    }

    @Test
    void laListaHaceLasMismasConsultasConUnTurnoQueConSeis() {
        LocalDate dia = LocalDate.of(2026, 11, 12);
        caja.abrirTurno(caja.administradorId, "0");
        vender(1);
        turnos.cerrar(new SolicitudCierre(new BigDecimal("1000"), null));
        turnos.listar(dia, dia);
        long conUno = sentenciasDe(() -> assertEquals(1, turnos.listar(dia, dia).size()));

        for (int i = 0; i < 3; i++) {
            caja.abrirTurno(caja.administradorId, "0");
            vender(2);
            turnos.cerrar(new SolicitudCierre(new BigDecimal("2000"), null));
        }
        caja.abrirTurno(caja.vendedorId, "500");
        caja.abrirTurno(caja.administradorId, "0");
        vender(3);
        long conSeis = sentenciasDe(() -> assertEquals(6, turnos.listar(dia, dia).size()));

        assertEquals(conUno, conSeis, "con un turno fueron " + conUno + " consultas y con seis, " + conSeis);
        assertTrue(conSeis <= 3, "la lista costó " + conSeis + " consultas");
    }
}
