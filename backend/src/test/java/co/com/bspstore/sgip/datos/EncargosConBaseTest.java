package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.EncargoPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudIngreso;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioEncargos;
import co.com.bspstore.sgip.logica.ServicioInventario;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.util.Hoy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Los encargos (RF-42, RF-69) contra el esquema real: la lista del administrador, el estado que se
 * calcula con la existencia del producto (pendiente mientras esté en negativo, «llegó» desde cero) y la
 * entrega que se marca a mano. Nada de esto actualiza {@code reposicion_pendiente}: esa marca dice que
 * la venta tuvo un encargo, no cuál es su estado de hoy.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class EncargosConBaseTest {

    private static final String MOTIVO = "No había existencia";

    @Autowired ServicioVentas ventas;
    @Autowired ServicioEncargos encargos;
    @Autowired ServicioInventario inventario;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Encargos", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        caja.entrarComoAdministrador();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    private LocalDate entrega() {
        return LocalDate.now().plusDays(7);
    }

    /** Vende {@code cantidad} de un producto con la existencia dada, como encargo (la existencia no alcanza). */
    private VentaRegistrada encargo(Integer producto, int cantidad, Integer cliente) {
        BigDecimal total = new BigDecimal("10000").multiply(BigDecimal.valueOf(cantidad));
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), cliente,
                List.of(new LineaSolicitada(producto, null, cantidad, null, entrega())), null,
                List.of(new PagoSolicitado(caja.efectivoId, total, null, null)), MOTIVO, null, null));
    }

    private Long itemDe(Long venta) {
        return jdbc.sql("SELECT item_id FROM venta_items WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", venta).query(Long.class).single();
    }

    private Map<String, Object> linea(Long item) {
        return jdbc.sql("SELECT * FROM venta_items WHERE empresa_id = :e AND item_id = :i")
                .param("e", caja.empresa.empresaId).param("i", item).query().singleRow();
    }

    private static List<Long> items(List<EncargoPendiente> lista) {
        return lista.stream().map(EncargoPendiente::itemId).toList();
    }

    // ------------------------------------------------------- la lista

    @Test
    void laListaTraeElClienteLaFechaEstimadaYLaExistenciaDelProducto() {
        Integer producto = caja.producto("A", 1, "10000");
        VentaRegistrada r = encargo(producto, 3, caja.clienteId);
        Long item = itemDe(r.id());

        List<EncargoPendiente> lista = encargos.listar("PENDIENTE");

        assertEquals(1, lista.size());
        EncargoPendiente e = lista.get(0);
        assertEquals(item, e.itemId());
        assertEquals(r.id(), e.ventaId());
        assertEquals(r.consecutivo(), e.consecutivo());
        assertEquals("Cliente " + caja.empresa.marca.toLowerCase(), e.cliente());
        assertEquals("3001112233", e.telefono());
        assertEquals(producto, e.productoId());
        assertEquals("Producto A", e.producto());
        assertEquals("A-" + caja.empresa.marca, e.codigo());
        assertEquals(3, e.cantidad());
        assertEquals(2, e.cantidadEncargo(), "se vende la que había y las 2 que faltan quedan debiéndose");
        assertEquals(entrega(), e.entregaEstimada());
        assertEquals(-2, e.existencia());
        assertEquals("PENDIENTE", e.estado());
        assertEquals(0, e.antiguedadDias());
        assertEquals(List.of(), encargos.listar("LLEGO"));
    }

    @Test
    void laVentaSinEncargoNoApareceNiLaLineaQueTuvoExistencia() {
        Integer producto = caja.producto("A", 5, "10000");
        ventas.registrar(new SolicitudVenta(UUID.randomUUID(), null,
                List.of(new LineaSolicitada(producto, null, 2, null, null)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("20000"), null, null)), null, null, null));

        assertEquals(List.of(), encargos.listar("PENDIENTE"));
        assertEquals(List.of(), encargos.listar("LLEGO"));
    }

    @Test
    void encargoPasaALlegoCuandoLaExistenciaVuelveACero() {
        Integer producto = caja.producto("A", 0, "10000");
        VentaRegistrada r = encargo(producto, 2, caja.clienteId);
        Long item = itemDe(r.id());
        assertEquals(-2, caja.existencia(producto));
        assertEquals(List.of(item), items(encargos.listar("PENDIENTE")));

        inventario.ingresar(new SolicitudIngreso(producto, 2, "FAC-1", null));

        assertEquals(0, caja.existencia(producto));
        List<EncargoPendiente> llego = encargos.listar("LLEGO");
        assertEquals(List.of(item), items(llego), "con la existencia en cero ya no se debe nada");
        assertEquals("LLEGO", llego.get(0).estado());
        assertEquals(List.of(), encargos.listar("PENDIENTE"));
        Map<String, Object> v = jdbc.sql("SELECT sin_existencia, reposicion_pendiente FROM ventas WHERE venta_id = :v")
                .param("v", r.id()).query().singleRow();
        assertEquals(true, v.get("reposicion_pendiente"), "es la marca de que tuvo encargo, no el estado vigente");
        assertEquals(true, v.get("sin_existencia"));
    }

    @Test
    void elEstadoSeCalculaPorProductoYNoPorVenta() {
        Integer faltante = caja.producto("A", 0, "10000");
        Integer llegado = caja.producto("B", 0, "10000");
        VentaRegistrada ventaFaltante = encargo(faltante, 1, null);
        VentaRegistrada ventaLlegada = encargo(llegado, 1, null);
        inventario.ingresar(new SolicitudIngreso(llegado, 5, "FAC-2", null));

        assertEquals(List.of(itemDe(ventaFaltante.id())), items(encargos.listar("PENDIENTE")));
        assertEquals(List.of(itemDe(ventaLlegada.id())), items(encargos.listar("LLEGO")));
    }

    @Test
    void elEstadoEsObligatorioYUnoDesconocidoEs400() {
        assertThrows(FiltroInvalidoException.class, () -> encargos.listar(null));
        assertThrows(FiltroInvalidoException.class, () -> encargos.listar(""));
        assertThrows(FiltroInvalidoException.class, () -> encargos.listar("ENTREGADO"));
        assertEquals(List.of(), encargos.listar(" pendiente "), "se acepta sin importar mayúsculas ni espacios");
    }

    @Test
    void lasListasNoMezclanEmpresas() {
        Integer producto = caja.producto("A", 0, "10000");
        VentaRegistrada propia = encargo(producto, 1, null);
        CajaDePrueba otra = CajaDePrueba.crear("Otra empresa", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            otra.abrirTurno(otra.administradorId);
            Integer delOtra = otra.producto("B", 0, "10000");
            otra.entrarComoAdministrador();
            VentaRegistrada ajena = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), null,
                    List.of(new LineaSolicitada(delOtra, null, 1, null, entrega())), null,
                    List.of(new PagoSolicitado(otra.efectivoId, new BigDecimal("10000"), null, null)), MOTIVO, null, null));
            Long itemAjeno = jdbc.sql("SELECT item_id FROM venta_items WHERE venta_id = :v")
                    .param("v", ajena.id()).query(Long.class).single();

            caja.entrarComoAdministrador();
            assertEquals(List.of(itemDe(propia.id())), items(encargos.listar("PENDIENTE")));
            assertThrows(RecursoNoEncontradoException.class, () -> encargos.entregar(itemAjeno), "es de otra empresa");
            otra.entrarComoAdministrador();
            assertEquals(List.of(itemAjeno), items(encargos.listar("PENDIENTE")), "y la otra solo ve lo suyo");
        } finally {
            otra.borrar();
        }
    }

    @Test
    void unaVentaAnuladaNoApareceNiSeEntrega() {
        Integer producto = caja.producto("A", 0, "10000");
        VentaRegistrada r = encargo(producto, 1, null);
        jdbc.sql("UPDATE ventas SET estado = 'ANULADA', estado_pago = 'ANULADA' WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", r.id()).update();

        assertEquals(List.of(), encargos.listar("PENDIENTE"));
        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> encargos.entregar(itemDe(r.id())));
    }

    // ------------------------------------------------------- la entrega a mano

    @Test
    void entregarEncargoRegistraQuienYCuando() {
        Integer producto = caja.producto("A", 0, "10000");
        VentaRegistrada r = encargo(producto, 2, null);
        Long item = itemDe(r.id());
        LocalDateTime antes = Hoy.ahora(Clock.systemUTC());

        EntregaRegistrada entrega = encargos.entregar(item);

        LocalDateTime despues = Hoy.ahora(Clock.systemUTC());
        Map<String, Object> l = linea(item);
        assertEquals(caja.administradorId, l.get("encargo_entregado_por"));
        LocalDateTime cuando = (LocalDateTime) l.get("encargo_entregado_en");
        assertFalse(cuando.isBefore(antes) || cuando.isAfter(despues), "la hora de Bogotá de la aplicación: " + cuando);
        assertEquals(item, entrega.id());
        assertEquals(caja.administradorId, entrega.entregadoPor());
        assertEquals(cuando, entrega.entregadoEn().toLocalDateTime());
        assertEquals(List.of(), encargos.listar("PENDIENTE"), "entregado sale de la lista, aunque la existencia siga negativa");
        assertEquals(-2, caja.existencia(producto), "marcar la entrega no toca el inventario");
    }

    @Test
    void entregarEncargoDosVecesEs409YNoCambiaQuienNiCuando() {
        Integer producto = caja.producto("A", 0, "10000");
        Long item = itemDe(encargo(producto, 2, null).id());
        encargos.entregar(item);
        Map<String, Object> primera = linea(item);

        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> encargos.entregar(item));

        assertEquals(primera, linea(item));
    }

    @Test
    void entregarUnaLineaQueNoEsEncargoOInexistenteEs404() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada normal = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), null,
                List.of(new LineaSolicitada(producto, null, 1, null, null)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("10000"), null, null)), null, null, null));

        assertThrows(RecursoNoEncontradoException.class, () -> encargos.entregar(itemDe(normal.id())));
        assertThrows(RecursoNoEncontradoException.class, () -> encargos.entregar(987654321L));
        assertThrows(RecursoNoEncontradoException.class, () -> encargos.entregar(0L));
        assertNull(linea(itemDe(normal.id())).get("encargo_entregado_en"));
    }
}
