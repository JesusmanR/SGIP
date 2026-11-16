package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.DatosDeDomicilioIncompletosException;
import co.com.bspstore.sgip.logica.ExistenciaInsuficienteException;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioInventario;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El domicilio dentro de la venta (RF-44, RF-69) contra el esquema real: la venta a domicilio
 * pagada por adelantado, la que se cobra contra entrega (nace por cobrar, sin pagos, y ya descontó
 * la existencia) y lo que no se admite. Los datos personales del domicilio solo se leen aquí, de la
 * base: ninguna respuesta de la venta los devuelve.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class DomiciliosConBaseTest {

    private static final Domicilio ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", false);
    private static final Domicilio CONTRA_ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", true);

    @Autowired ServicioVentas ventas;
    @Autowired ServicioInventario inventario;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private Long turnoDelAdministrador;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Domicilios", jdbc, plataforma, categorias, proveedores, clientes);
        turnoDelAdministrador = caja.abrirTurno(caja.administradorId);
        caja.abrirTurno(caja.vendedorId);
        caja.entrarComoAdministrador();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    private PagoSolicitado efectivo(String monto) {
        return new PagoSolicitado(caja.efectivoId, new BigDecimal(monto), null, null);
    }

    private SolicitudVenta domicilio(UUID clave, Integer cliente, Integer producto, int cantidad, Domicilio d,
                                     List<PagoSolicitado> pagos) {
        return new SolicitudVenta(clave, cliente, List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null,
                pagos, null, null, d);
    }

    private SolicitudVenta pagado(Integer producto, String monto) {
        return domicilio(UUID.randomUUID(), caja.clienteId, producto, 1, ENTREGA, List.of(efectivo(monto)));
    }

    private SolicitudVenta contraEntrega(Integer producto, int cantidad) {
        return domicilio(UUID.randomUUID(), caja.clienteId, producto, cantidad, CONTRA_ENTREGA, List.of());
    }

    private Map<String, Object> ventaGuardada(Long id) {
        return jdbc.sql("SELECT * FROM ventas WHERE empresa_id = :e AND venta_id = :id")
                .param("e", caja.empresa.empresaId).param("id", id).query().singleRow();
    }

    private Map<String, Object> domicilioGuardado(Long id) {
        return jdbc.sql("SELECT * FROM domicilios_venta WHERE empresa_id = :e AND venta_id = :id")
                .param("e", caja.empresa.empresaId).param("id", id).query().singleRow();
    }

    private static void igual(String esperado, Object obtenido) {
        assertEquals(0, new BigDecimal(esperado).compareTo((BigDecimal) obtenido),
                "esperaba " + esperado + " y fue " + obtenido);
    }

    private Integer clienteSinDatos(String marca, String telefono, String direccion, String ciudad) {
        return clientes.insertar(caja.empresa.empresaId, new Cliente(null, "Cliente " + marca, TipoDocumento.CC,
                "8" + caja.empresa.marca + marca, null, telefono, direccion, ciudad, false),
                LocalDateTime.of(2026, 11, 1, 8, 0), "CAJA");
    }

    private void nadaGuardado(Integer producto, int existencia) {
        assertEquals(0, caja.cuantas("ventas"), "ninguna venta");
        assertEquals(0, caja.cuantas("domicilios_venta"), "ningún domicilio");
        assertEquals(0, caja.cuantas("pagos_venta"), "ningún pago");
        assertEquals(0, caja.cuantas("consecutivos"), "ningún consecutivo consumido");
        assertEquals(existencia, caja.existencia(producto), "la existencia no se movió");
    }

    // ------------------------------------------------------- el domicilio pagado por adelantado

    @Test
    void unDomicilioPagadoPorAdelantadoSeRegistraConSusPagosYSusDatosDeEntrega() {
        Integer producto = caja.producto("A", 5, "10000");

        VentaRegistrada r = ventas.registrar(pagado(producto, "10000"));

        Map<String, Object> v = ventaGuardada(r.id());
        assertEquals("DOMICILIO", v.get("tipo_entrega"));
        assertEquals("PAGADA", v.get("estado_pago"));
        assertEquals(false, v.get("pago_contra_entrega"));
        assertEquals(turnoDelAdministrador, v.get("cierre_id"));
        Map<String, Object> d = domicilioGuardado(r.id());
        assertEquals("Calle 1 # 2-3", d.get("direccion"));
        assertEquals("Cali", d.get("ciudad"));
        assertEquals("3001112233", d.get("telefono"));
        assertEquals("Ana Pérez", d.get("recibe"));
        assertNull(d.get("entregado_en"), "nace sin entregar");
        assertNull(d.get("entregado_por"));
        assertEquals(1, caja.cuantas("pagos_venta"), "un domicilio pagado lleva sus pagos por el total");
        assertEquals(4, caja.existencia(producto));
    }

    @Test
    void unaVentaDelMostradorNoTieneDomicilio() {
        Integer producto = caja.producto("A", 5, "10000");

        VentaRegistrada r = ventas.registrar(domicilio(UUID.randomUUID(), null, producto, 1, null, List.of(efectivo("10000"))));

        assertEquals("MOSTRADOR", ventaGuardada(r.id()).get("tipo_entrega"));
        assertEquals(0, caja.cuantas("domicilios_venta"));
    }

    // ------------------------------------------------------- lo que exige un domicilio

    @Test
    void domicilioExigeDatosCompletosYNoConsumidorFinal() {
        Integer producto = caja.producto("A", 5, "10000");
        Integer sinTelefono = clienteSinDatos("T", null, "Calle 9", "Cali");
        Integer sinDireccion = clienteSinDatos("D", "3001112233", null, "Cali");
        Integer sinCiudad = clienteSinDatos("C", "3001112233", "Calle 9", null);

        assertThrows(DatosDeDomicilioIncompletosException.class, () -> ventas.procesar(
                domicilio(UUID.randomUUID(), caja.consumidorFinalId, producto, 1, ENTREGA, List.of(efectivo("10000")))),
                "el Consumidor final no sirve para un domicilio");
        assertThrows(DatosDeDomicilioIncompletosException.class, () -> ventas.procesar(
                domicilio(UUID.randomUUID(), null, producto, 1, ENTREGA, List.of(efectivo("10000")))),
                "ni la ausencia de cliente, que es el Consumidor final");
        for (Integer incompleto : List.of(sinTelefono, sinDireccion, sinCiudad)) {
            assertThrows(DatosDeDomicilioIncompletosException.class, () -> ventas.procesar(
                    domicilio(UUID.randomUUID(), incompleto, producto, 1, ENTREGA, List.of(efectivo("10000")))),
                    "al cliente le faltan datos");
        }

        nadaGuardado(producto, 5);
    }

    @Test
    void unClienteDeOtraEmpresaNoSirveParaUnDomicilio() {
        Integer producto = caja.producto("A", 5, "10000");
        CajaDePrueba otra = CajaDePrueba.crear("Otra empresa", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            assertThrows(co.com.bspstore.sgip.logica.RecursoNoEncontradoException.class, () -> ventas.procesar(
                    domicilio(UUID.randomUUID(), otra.clienteId, producto, 1, ENTREGA, List.of(efectivo("10000")))));
        } finally {
            otra.borrar();
        }

        nadaGuardado(producto, 5);
    }

    @Test
    void laCiudadVaciaTomaLaDeLaEmpresaYSiLaEmpresaNoTieneEsUn400() {
        Integer producto = caja.producto("A", 5, "10000");
        Domicilio sinCiudad = new Domicilio("Calle 1 # 2-3", null, "3001112233", "Ana Pérez", false);

        assertThrows(DatosDeDomicilioIncompletosException.class, () -> ventas.procesar(
                domicilio(UUID.randomUUID(), caja.clienteId, producto, 1, sinCiudad, List.of(efectivo("10000")))),
                "la empresa de la prueba no tiene ciudad");
        nadaGuardado(producto, 5);

        jdbc.sql("UPDATE empresas SET ciudad = 'Medellín' WHERE empresa_id = :e").param("e", caja.empresa.empresaId).update();
        VentaRegistrada r = ventas.registrar(
                domicilio(UUID.randomUUID(), caja.clienteId, producto, 1, sinCiudad, List.of(efectivo("10000"))));

        assertEquals("Medellín", domicilioGuardado(r.id()).get("ciudad"));
    }

    // ------------------------------------------------------- contra entrega

    @Test
    void contraEntregaQuedaPorCobrarSinPagosYYaDescuentaLaExistencia() {
        Integer producto = caja.producto("A", 5, "10000");

        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 2));

        Map<String, Object> v = ventaGuardada(r.id());
        assertEquals("PENDIENTE", v.get("estado_pago"));
        assertEquals(true, v.get("pago_contra_entrega"));
        assertEquals("DOMICILIO", v.get("tipo_entrega"));
        assertEquals(turnoDelAdministrador, v.get("cierre_id"), "la venta se hace en el turno de quien la registra");
        igual("20000", v.get("total"));
        igual("20000", r.total());
        igual("0", r.cambio());
        assertEquals(0, caja.cuantas("pagos_venta"), "no hay pagos hasta que llegue el dinero");
        assertEquals(3, caja.existencia(producto), "la mercancía sale de la tienda al registrarla");
        assertEquals(1, caja.movimientosDe(producto, "EGRESO"));
        assertTrue(inventario.cuadre(producto).orElseThrow().cuadra());
        assertEquals(1, caja.cuantas("domicilios_venta"));
        assertNull(domicilioGuardado(r.id()).get("entregado_en"), "falta despacharlo y falta cobrarlo");
    }

    @Test
    void contraEntregaNoExigeEfectivoPeroSiTurnoAbierto() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.cerrarTurno(turnoDelAdministrador);

        assertThrows(SinTurnoAbiertoException.class, () -> ventas.procesar(contraEntrega(producto, 1)));

        nadaGuardado(producto, 5);
    }

    @Test
    void contraEntregaTambienNecesitaExistenciaOEncargoAutorizado() {
        Integer producto = caja.producto("A", 1, "10000");

        assertThrows(ExistenciaInsuficienteException.class, () -> ventas.procesar(contraEntrega(producto, 2)));

        nadaGuardado(producto, 1);
    }

    @Test
    void contraEntregaConPagosEs400() {
        Integer producto = caja.producto("A", 5, "10000");

        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(
                domicilio(UUID.randomUUID(), caja.clienteId, producto, 1, CONTRA_ENTREGA, List.of(efectivo("10000")))));

        nadaGuardado(producto, 5);
    }

    @Test
    void elVendedorTambienRegistraUnDomicilioContraEntregaEnSuTurno() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoVendedor();

        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));

        assertEquals(caja.vendedorId, ventaGuardada(r.id()).get("usuario_id"));
        assertEquals("PENDIENTE", ventaGuardada(r.id()).get("estado_pago"));
    }

    // ------------------------------------------------------- el reintento

    @Test
    void elReintentoDeUnDomicilioDevuelveLaMismaVentaYNoDuplicaNada() {
        Integer producto = caja.producto("A", 5, "10000");
        SolicitudVenta solicitud = contraEntrega(producto, 1);

        ResultadoDeVenta primera = ventas.procesar(solicitud);
        ResultadoDeVenta segunda = ventas.procesar(solicitud);

        assertFalse(primera.yaExistia());
        assertTrue(segunda.yaExistia());
        assertEquals(primera.venta().id(), segunda.venta().id());
        assertEquals(1, caja.cuantas("ventas"));
        assertEquals(1, caja.cuantas("domicilios_venta"));
        assertEquals(4, caja.existencia(producto), "el descuento se hizo una sola vez");
    }

    @Test
    void laMismaClaveConOtroDatoDelDomicilioEs409YNoCambiaNada() {
        Integer producto = caja.producto("A", 5, "10000");
        UUID clave = UUID.randomUUID();
        ventas.procesar(domicilio(clave, caja.clienteId, producto, 1, ENTREGA, List.of(efectivo("10000"))));

        for (Domicilio otro : List.of(
                new Domicilio("Calle 9", "Cali", "3001112233", "Ana Pérez", false),
                new Domicilio("Calle 1 # 2-3", "Bogotá", "3001112233", "Ana Pérez", false),
                new Domicilio("Calle 1 # 2-3", "Cali", "3009998877", "Ana Pérez", false),
                new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Luis Gómez", false))) {
            assertThrows(ClaveDeIdempotenciaEnUsoException.class, () -> ventas.procesar(
                    domicilio(clave, caja.clienteId, producto, 1, otro, List.of(efectivo("10000")))), String.valueOf(otro));
        }
        assertThrows(ClaveDeIdempotenciaEnUsoException.class, () -> ventas.procesar(
                domicilio(clave, caja.clienteId, producto, 1, null, List.of(efectivo("10000")))), "sin domicilio");

        Map<String, Object> d = domicilioGuardado(ventaIdDe(clave));
        assertEquals("Calle 1 # 2-3", d.get("direccion"));
        assertEquals(1, caja.cuantas("ventas"));
    }

    private Long ventaIdDe(UUID clave) {
        return jdbc.sql("SELECT venta_id FROM ventas WHERE empresa_id = :e AND clave_idempotencia = :c")
                .param("e", caja.empresa.empresaId).param("c", clave.toString()).query(Long.class).single();
    }
}
