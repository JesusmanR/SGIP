package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.CobroRegistrado;
import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.DomicilioPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.DatosDeDomicilioIncompletosException;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.ExistenciaInsuficienteException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import co.com.bspstore.sgip.logica.ServicioInventario;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import co.com.bspstore.sgip.modelo.TipoDocumento;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    @Autowired ServicioDomicilios domicilios;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private Long turnoDelAdministrador;
    private Long turnoDelVendedor;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Domicilios", jdbc, plataforma, categorias, proveedores, clientes);
        turnoDelAdministrador = caja.abrirTurno(caja.administradorId);
        turnoDelVendedor = caja.abrirTurno(caja.vendedorId);
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

    // =================================================================== las listas

    private static List<Long> ids(List<DomicilioPendiente> lista) {
        return lista.stream().map(DomicilioPendiente::ventaId).toList();
    }

    private PagoSolicitado efectivoRecibiendo(String monto, String recibido) {
        return new PagoSolicitado(caja.efectivoId, new BigDecimal(monto), new BigDecimal(recibido), null);
    }

    private List<Map<String, Object>> pagosGuardados(Long venta) {
        return jdbc.sql("SELECT * FROM pagos_venta WHERE empresa_id = :e AND venta_id = :v ORDER BY pago_id")
                .param("e", caja.empresa.empresaId).param("v", venta).query().listOfRows();
    }

    @Test
    void domicilioPagadoNoApareceEnPorCobrar() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada pagado = ventas.registrar(pagado(producto, "10000"));
        VentaRegistrada pendiente = ventas.registrar(contraEntrega(producto, 1));

        assertEquals(List.of(pendiente.id()), ids(domicilios.listar("POR_COBRAR")));
        assertEquals(Set.of(pagado.id(), pendiente.id()), Set.copyOf(ids(domicilios.listar("POR_DESPACHAR"))),
                "los dos faltan por despachar");
    }

    @Test
    void porCobrarMuestraValorYAntiguedad() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada vieja = ventas.registrar(contraEntrega(producto, 2));
        VentaRegistrada nueva = ventas.registrar(contraEntrega(producto, 1));
        LocalDateTime haceTresDias = Hoy.ahora(Clock.systemUTC()).minusDays(3);
        jdbc.sql("UPDATE ventas SET fecha = :f WHERE empresa_id = :e AND venta_id = :v")
                .param("f", haceTresDias).param("e", caja.empresa.empresaId).param("v", vieja.id()).update();

        List<DomicilioPendiente> lista = domicilios.listar("POR_COBRAR");

        assertEquals(List.of(vieja.id(), nueva.id()), ids(lista), "primero el más antiguo");
        DomicilioPendiente v = lista.get(0);
        assertEquals(3, v.antiguedadDias());
        igual("20000", v.valorPorCobrar());
        igual("20000", v.total());
        assertEquals("PENDIENTE", v.estadoPago());
        assertTrue(v.contraEntrega());
        assertEquals(vieja.consecutivo(), v.consecutivo());
        assertEquals("Cliente " + caja.empresa.marca.toLowerCase(), v.cliente());
        assertEquals("Calle 1 # 2-3", v.direccion());
        assertEquals("Cali", v.ciudad());
        assertEquals("3001112233", v.telefono());
        assertEquals("Ana Pérez", v.recibe());
        assertEquals(0, lista.get(1).antiguedadDias());
        igual("10000", lista.get(1).valorPorCobrar());
    }

    @Test
    void unDomicilioPagadoMuestraCeroPorCobrar() {
        Integer producto = caja.producto("A", 5, "10000");
        ventas.registrar(pagado(producto, "10000"));

        List<DomicilioPendiente> lista = domicilios.listar("POR_DESPACHAR");

        igual("0", lista.get(0).valorPorCobrar());
        assertEquals("PAGADA", lista.get(0).estadoPago());
        assertFalse(lista.get(0).contraEntrega());
    }

    @Test
    void elEstadoDeLaListaEsObligatorioYUnoDesconocidoEs400() {
        assertThrows(FiltroInvalidoException.class, () -> domicilios.listar(null));
        assertThrows(FiltroInvalidoException.class, () -> domicilios.listar("  "));
        assertThrows(FiltroInvalidoException.class, () -> domicilios.listar("ENTREGADOS"));
        assertEquals(List.of(), domicilios.listar(" por_cobrar "), "se acepta sin importar mayúsculas ni espacios");
    }

    @Test
    void lasListasNoMezclanEmpresasNiLasOperanPorId() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada propia = ventas.registrar(contraEntrega(producto, 1));
        CajaDePrueba otra = CajaDePrueba.crear("Otra empresa", jdbc, plataforma, categorias, proveedores, clientes);
        try {
            otra.abrirTurno(otra.administradorId);
            Integer delOtra = otra.producto("B", 5, "10000");
            otra.entrarComoAdministrador();
            VentaRegistrada ajena = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), otra.clienteId,
                    List.of(new LineaSolicitada(delOtra, null, 1, null, null)), null, List.of(), null, null, CONTRA_ENTREGA));

            caja.entrarComoAdministrador();
            assertEquals(List.of(propia.id()), ids(domicilios.listar("POR_COBRAR")));
            assertEquals(List.of(propia.id()), ids(domicilios.listar("POR_DESPACHAR")));
            assertThrows(RecursoNoEncontradoException.class, () -> domicilios.entregar(ajena.id()));
            assertThrows(RecursoNoEncontradoException.class,
                    () -> domicilios.cobrar(ajena.id(), List.of(efectivo("10000"))));
            otra.entrarComoAdministrador();
            assertEquals(List.of(ajena.id()), ids(domicilios.listar("POR_COBRAR")), "y la otra solo ve lo suyo");
        } finally {
            otra.borrar();
        }
    }

    @Test
    void laListaNoPasaDelTopeYMuestraLosMasAntiguos() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada vieja = ventas.registrar(contraEntrega(producto, 1));
        jdbc.sql("UPDATE ventas SET fecha = '2020-01-01 08:00:00' WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", vieja.id()).update();
        jdbc.sql("""
                INSERT INTO ventas (empresa_id, consecutivo, cliente_id, usuario_id, fecha, forma_pago, estado_pago,
                                    cierre_id, subtotal, descuento, iva, tipo_entrega, pago_contra_entrega)
                SELECT :e, CONCAT('X-', n.i), :cliente, :usuario, '2026-11-01 08:00:00', 'CONTADO', 'PENDIENTE', :turno,
                       1000, 0, 0, 'DOMICILIO', TRUE
                  FROM (WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < 205) SELECT i FROM s) n
                """).param("e", caja.empresa.empresaId).param("cliente", caja.clienteId)
                .param("usuario", caja.administradorId).param("turno", turnoDelAdministrador).update();
        jdbc.sql("""
                INSERT INTO domicilios_venta (venta_id, empresa_id, direccion, ciudad, telefono, recibe)
                SELECT venta_id, empresa_id, 'Calle 1', 'Cali', '3001112233', 'Ana'
                  FROM ventas WHERE empresa_id = :e AND consecutivo LIKE 'X-%'
                """).param("e", caja.empresa.empresaId).update();

        List<DomicilioPendiente> lista = domicilios.listar("POR_COBRAR");

        assertEquals(200, lista.size());
        assertEquals(vieja.id(), lista.get(0).ventaId(), "el tope deja fuera a los más nuevos, no a los más antiguos");
    }

    // =================================================================== marcar la entrega

    @Test
    void marcarEntregadoRegistraQuienYCuando() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));
        caja.entrarComoVendedor();
        LocalDateTime antes = Hoy.ahora(Clock.systemUTC());

        EntregaRegistrada entrega = domicilios.entregar(r.id());

        LocalDateTime despues = Hoy.ahora(Clock.systemUTC());
        Map<String, Object> d = domicilioGuardado(r.id());
        assertEquals(caja.vendedorId, d.get("entregado_por"));
        LocalDateTime cuando = (LocalDateTime) d.get("entregado_en");
        assertFalse(cuando.isBefore(antes) || cuando.isAfter(despues), "la hora de Bogotá de la aplicación: " + cuando);
        assertEquals(r.id(), entrega.id());
        assertEquals(caja.vendedorId, entrega.entregadoPor());
        assertEquals(cuando, entrega.entregadoEn().toLocalDateTime());
        assertEquals(List.of(), ids(domicilios.listar("POR_DESPACHAR")), "entregado sale de por despachar");
        assertEquals(List.of(r.id()), ids(domicilios.listar("POR_COBRAR")), "y sigue por cobrar hasta que llegue el dinero");
    }

    @Test
    void entregarDosVecesEs409YNoCambiaQuienNiCuando() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(pagado(producto, "10000"));
        domicilios.entregar(r.id());
        Map<String, Object> primera = domicilioGuardado(r.id());
        caja.entrarComoVendedor();

        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> domicilios.entregar(r.id()));

        assertEquals(primera, domicilioGuardado(r.id()));
    }

    @Test
    void entregarUnaVentaSinDomicilioOInexistenteEs404() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada mostrador = ventas.registrar(domicilio(UUID.randomUUID(), null, producto, 1, null,
                List.of(efectivo("10000"))));

        assertThrows(RecursoNoEncontradoException.class, () -> domicilios.entregar(mostrador.id()));
        assertThrows(RecursoNoEncontradoException.class, () -> domicilios.entregar(987654321L));
    }

    @Test
    void unaVentaAnuladaNoSeEntregaNiSeCobraNiApareceEnLasListas() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));
        jdbc.sql("UPDATE ventas SET estado = 'ANULADA', estado_pago = 'ANULADA' WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", r.id()).update();

        assertEquals(List.of(), domicilios.listar("POR_DESPACHAR"));
        assertEquals(List.of(), domicilios.listar("POR_COBRAR"));
        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> domicilios.entregar(r.id()));
        assertThrows(EstadoNoAdmiteLaOperacionException.class,
                () -> domicilios.cobrar(r.id(), List.of(efectivo("10000"))));
        assertNull(domicilioGuardado(r.id()).get("entregado_en"));
        assertEquals(0, caja.cuantas("pagos_venta"));
    }

    // =================================================================== el cobro posterior

    @Test
    void cobroPosteriorVaAlTurnoDeQuienCobra() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));
        caja.entrarComoVendedor();

        CobroRegistrado cobro = domicilios.cobrar(r.id(), List.of(efectivoRecibiendo("10000", "20000")));

        List<Map<String, Object>> pagos = pagosGuardados(r.id());
        assertEquals(1, pagos.size());
        assertEquals(turnoDelVendedor, pagos.get(0).get("cierre_id"), "el pago lleva el turno de quien cobra, no el de la venta");
        assertEquals(caja.vendedorId, pagos.get(0).get("registrado_por"));
        assertEquals(caja.efectivoId, pagos.get(0).get("medio_id"));
        igual("10000", pagos.get(0).get("monto"));
        igual("20000", pagos.get(0).get("recibido"));
        Map<String, Object> v = ventaGuardada(r.id());
        assertEquals("PAGADA", v.get("estado_pago"));
        assertEquals(turnoDelAdministrador, v.get("cierre_id"), "la venta sigue siendo del turno en que se hizo");
        assertEquals(true, v.get("pago_contra_entrega"));
        assertEquals(r.id(), cobro.ventaId());
        assertEquals(r.consecutivo(), cobro.consecutivo());
        igual("10000", cobro.cobrado());
        igual("10000", cobro.cambio());
        assertEquals(List.of(), ids(domicilios.listar("POR_COBRAR")), "ya no está por cobrar");
    }

    @Test
    void elCobroPuedeSerMixtoYSumaElSaldo() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));

        domicilios.cobrar(r.id(), List.of(efectivo("4000"),
                new PagoSolicitado(caja.tarjetaId, new BigDecimal("6000"), null, "APROB-123")));

        assertEquals(2, pagosGuardados(r.id()).size());
        assertEquals("PAGADA", ventaGuardada(r.id()).get("estado_pago"));
    }

    @Test
    void cobroParcialOdeMasEs400YLaVentaSigueConElSaldoCompleto() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));

        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(r.id(), List.of(efectivo("5000"))));
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(r.id(), List.of(efectivo("10001"))));
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(r.id(),
                List.of(efectivo("5000"), efectivo("5001"))));

        assertEquals(0, caja.cuantas("pagos_venta"));
        assertEquals("PENDIENTE", ventaGuardada(r.id()).get("estado_pago"));
        assertEquals(List.of(r.id()), ids(domicilios.listar("POR_COBRAR")));
    }

    @Test
    void cobrarDosVecesEs409() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));
        domicilios.cobrar(r.id(), List.of(efectivo("10000")));

        assertThrows(EstadoNoAdmiteLaOperacionException.class, () -> domicilios.cobrar(r.id(), List.of(efectivo("10000"))));

        assertEquals(1, pagosGuardados(r.id()).size(), "el dinero no se cuenta dos veces");
    }

    @Test
    void cobrarUnaVentaQueNoEsContraEntregaEs409() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada domicilioPagado = ventas.registrar(pagado(producto, "10000"));
        VentaRegistrada mostrador = ventas.registrar(domicilio(UUID.randomUUID(), null, producto, 1, null,
                List.of(efectivo("10000"))));

        assertThrows(EstadoNoAdmiteLaOperacionException.class,
                () -> domicilios.cobrar(domicilioPagado.id(), List.of(efectivo("10000"))));
        assertThrows(EstadoNoAdmiteLaOperacionException.class,
                () -> domicilios.cobrar(mostrador.id(), List.of(efectivo("10000"))));

        assertEquals(2, caja.cuantas("pagos_venta"), "solo los de las dos ventas pagadas");
    }

    @Test
    void cobrarUnaVentaInexistenteEs404() {
        assertThrows(RecursoNoEncontradoException.class, () -> domicilios.cobrar(987654321L, List.of(efectivo("10000"))));
        assertThrows(RecursoNoEncontradoException.class, () -> domicilios.cobrar(0L, List.of(efectivo("10000"))));
        assertThrows(RecursoNoEncontradoException.class, () -> domicilios.cobrar(-5L, List.of(efectivo("10000"))));
    }

    @Test
    void cobrarExigeElTurnoAbiertoDeQuienCobra() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));
        caja.cerrarTurno(turnoDelVendedor);
        caja.entrarComoVendedor();

        assertThrows(SinTurnoAbiertoException.class, () -> domicilios.cobrar(r.id(), List.of(efectivo("10000"))));

        assertEquals("PENDIENTE", ventaGuardada(r.id()).get("estado_pago"));
        assertEquals(0, caja.cuantas("pagos_venta"));
    }

    @Test
    void elCobroValidaLosPagosComoLaVenta() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 1));
        Long venta = r.id();

        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(venta, null), "sin pagos");
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(venta, List.of()), "sin pagos");
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(venta,
                List.of(new PagoSolicitado(caja.tarjetaId, new BigDecimal("10000"), null, null))), "tarjeta sin referencia");
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(venta,
                List.of(new PagoSolicitado(caja.inactivoId, new BigDecimal("10000"), null, "REF"))), "medio inactivo");
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(venta,
                List.of(new PagoSolicitado(caja.saldoAFavorId, new BigDecimal("10000"), null, null))), "saldo a favor");
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(venta,
                List.of(new PagoSolicitado(987654, new BigDecimal("10000"), null, null))), "medio ajeno");
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(venta,
                List.of(efectivoRecibiendo("10000", "9000"))), "el efectivo recibido no alcanza");
        assertThrows(VentaInvalidaException.class, () -> domicilios.cobrar(venta,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("10000.50"), null, null))), "centavos");

        assertEquals(0, caja.cuantas("pagos_venta"));
        assertEquals("PENDIENTE", ventaGuardada(venta).get("estado_pago"));
    }

    @Test
    void elCobroNoMueveLaExistenciaNiElKardex() {
        Integer producto = caja.producto("A", 5, "10000");
        VentaRegistrada r = ventas.registrar(contraEntrega(producto, 2));
        int movimientos = caja.cuantas("movimientos_inventario");

        domicilios.cobrar(r.id(), List.of(efectivo("20000")));

        assertEquals(3, caja.existencia(producto));
        assertEquals(movimientos, caja.cuantas("movimientos_inventario"));
    }
}
