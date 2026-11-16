package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.ProductoParaCaja;
import co.com.bspstore.sgip.api.dto.ProductoNuevo;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.AutorizacionRequeridaException;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.NombreRepetidoException;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioCajaProductos;
import co.com.bspstore.sgip.logica.ServicioInventario;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El producto que nace dentro de la venta (RF-42) contra el esquema real: lo que se guarda, lo
 * que queda a la vista del catálogo y de la caja, y lo que NO queda cuando algo falla.
 *
 * Crea su empresa (con administrador, vendedor, medios de pago, «Sin proveedor» y «Sin
 * categoría») y la borra al terminar. Las carreras entre dos hilos están en
 * {@code ProductoNuevoConcurrenteConBaseTest}.
 */
@Tag("conBase")
@SpringBootTest
@AutoConfigureMockMvc
class ProductoNuevoConBaseTest {

    private static final String MOTIVO = "Producto que el cliente pidió y no está en el catálogo";

    @Autowired ServicioVentas ventas;
    @Autowired ServicioProducto productos;
    @Autowired ServicioCajaProductos cajaProductos;
    @Autowired ServicioInventario inventario;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @MockitoSpyBean RepositorioInventario kardex;

    private CajaDePrueba caja;
    private CajaDePrueba otraCaja;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Producto nuevo", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        caja.abrirTurno(caja.vendedorId);
        caja.entrarComoAdministrador();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (otraCaja != null) {
            otraCaja.borrar();
        }
        if (caja != null) {
            caja.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    private static LocalDate entrega() {
        return LocalDate.now().plusDays(7);
    }

    private static ProductoNuevo nuevo(String nombre, String precio, String barras) {
        return new ProductoNuevo(nombre, new BigDecimal(precio), barras);
    }

    private LineaSolicitada lineaNueva(ProductoNuevo p, int cantidad) {
        return new LineaSolicitada(null, p, cantidad, null, entrega());
    }

    private SolicitudAutorizacion pin() {
        return new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN);
    }

    private SolicitudVenta venta(UUID clave, String pago, SolicitudAutorizacion autorizacion, LineaSolicitada... lineas) {
        return new SolicitudVenta(clave, null, List.of(lineas), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal(pago), null, null)), MOTIVO, autorizacion, null);
    }

    private VentaRegistrada vender(CajaDePrueba de, String nombre, String precio, int cantidad) {
        de.entrarComoVendedor();
        SolicitudAutorizacion autorizacion = new SolicitudAutorizacion(de.administradorId, CajaDePrueba.PIN);
        BigDecimal total = new BigDecimal(precio).multiply(BigDecimal.valueOf(cantidad));
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), null,
                List.of(lineaNueva(nuevo(nombre, precio, null), cantidad)), null,
                List.of(new PagoSolicitado(de.efectivoId, total, null, null)), MOTIVO, autorizacion, null));
    }

    private Map<String, Object> producto(CajaDePrueba de, String codigo) {
        return jdbc.sql("SELECT * FROM productos WHERE empresa_id = :e AND codigo = :c")
                .param("e", de.empresa.empresaId).param("c", codigo).query().singleRow();
    }

    private Integer idDe(CajaDePrueba de, String codigo) {
        return (Integer) producto(de, codigo).get("producto_id");
    }

    private int cuantos(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql).param("e", caja.empresa.empresaId);
        for (int i = 0; i < parametros.length; i += 2) {
            consulta = consulta.param((String) parametros[i], parametros[i + 1]);
        }
        return consulta.query(Integer.class).single();
    }

    private void nadaCreado() {
        assertEquals(0, caja.cuantas("productos"), "ningún producto");
        assertEquals(0, caja.cuantas("reglas_margen"), "ninguna regla de precio");
        assertEquals(0, caja.cuantas("consecutivos"), "ningún consecutivo consumido");
        assertEquals(0, caja.cuantas("ventas"), "ninguna venta");
        assertEquals(0, caja.cuantas("venta_items"), "ninguna línea");
        assertEquals(0, caja.cuantas("pagos_venta"), "ningún pago");
        assertEquals(0, caja.cuantas("movimientos_inventario"), "ningún movimiento de inventario");
    }

    private static void igual(String esperado, Object obtenido) {
        assertEquals(0, new BigDecimal(esperado).compareTo((BigDecimal) obtenido),
                "esperaba " + esperado + " y fue " + obtenido);
    }

    // ------------------------------------------------------- la autorización

    @Test
    void productoNuevoSinPinEs403YNoQuedaNada() {
        caja.entrarComoVendedor();

        assertThrows(AutorizacionRequeridaException.class, () -> ventas.procesar(
                venta(UUID.randomUUID(), "15000", null, lineaNueva(nuevo("Tornillo", "7500", null), 2))));

        nadaCreado();
        assertEquals(1, cuantos("SELECT COUNT(*) FROM proveedores WHERE empresa_id = :e AND razon_social = 'Sin proveedor'"),
                "el proveedor y la categoría ya estaban sembrados: la venta no los toca");
        assertEquals(1, cuantos("SELECT COUNT(*) FROM categorias WHERE empresa_id = :e AND nombre = 'Sin categoría'"));
    }

    @Test
    void productoNuevoConUnPinQueSoloCubreElDescuentoEsUn403() {
        // El producto nuevo es una excepción propia: autorizarla no se hereda de un descuento.
        caja.entrarComoVendedor();

        assertThrows(AutorizacionRequeridaException.class, () -> ventas.procesar(
                new SolicitudVenta(UUID.randomUUID(), null, List.of(lineaNueva(nuevo("Tornillo", "7500", null), 2)),
                        null, List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("15000"), null, null)),
                        MOTIVO, null, null)));

        nadaCreado();
    }

    // ------------------------------------------------------- lo que se crea

    @Test
    void productoNuevoSeCreaDentroDeLaVentaConUnSoloPin() {
        caja.entrarComoVendedor();

        VentaRegistrada r = ventas.registrar(venta(UUID.randomUUID(), "15000", pin(),
                lineaNueva(nuevo("Tornillo hexagonal", "7500", "7701234567890"), 2)));

        Integer id = idDe(caja, "RAP-1");
        Map<String, Object> v = jdbc.sql("SELECT * FROM ventas WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", r.id()).query().singleRow();
        assertEquals(true, v.get("sin_existencia"));
        assertEquals(caja.administradorId, v.get("autorizada_por"));
        assertEquals(MOTIVO, v.get("motivo_sin_existencia"));
        assertEquals(1, r.lineas().size());
        assertEquals(id, r.lineas().get(0).productoId());
        Map<String, Object> egreso = jdbc.sql("""
                SELECT * FROM movimientos_inventario WHERE empresa_id = :e AND producto_id = :p AND tipo = 'EGRESO'
                """).param("e", caja.empresa.empresaId).param("p", id).query().singleRow();
        assertEquals(0, egreso.get("existencia_anterior"));
        assertEquals(-2, egreso.get("existencia_posterior"));
        assertEquals(caja.administradorId, egreso.get("autorizado_por"), "el kárdex guarda quién autorizó");
        assertEquals(r.consecutivo(), egreso.get("documento"));
        assertTrue(inventario.cuadre(id).orElseThrow().cuadra(), "la suma de los movimientos es la existencia");
    }

    @Test
    void productoNuevoSeVendeComoEncargoConExistenciaNegativa() {
        caja.entrarComoVendedor();

        VentaRegistrada r = ventas.registrar(venta(UUID.randomUUID(), "22500", pin(),
                lineaNueva(nuevo("Tornillo hexagonal", "7500", null), 3)));

        Integer id = idDe(caja, "RAP-1");
        assertEquals(-3, caja.existencia(id), "la existencia queda en menos la cantidad");
        Map<String, Object> linea = jdbc.sql("SELECT * FROM venta_items WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", r.id()).query().singleRow();
        assertEquals(3, linea.get("cantidad"));
        assertEquals(3, linea.get("cantidad_encargo"), "toda la venta del producto nuevo es encargo");
        assertEquals(java.sql.Date.valueOf(entrega()).toString(), String.valueOf(linea.get("entrega_estimada")));
        igual("1.00", linea.get("costo_unitario"));
        igual("7500", linea.get("precio_unitario"));
        igual("22500", linea.get("subtotal"));
        igual("21000", linea.get("utilidad"));
    }

    @Test
    void productoNuevoLlevaCostoProvisional() {
        ventas.registrar(venta(UUID.randomUUID(), "7500", null,
                lineaNueva(nuevo("Tornillo hexagonal", "7500", "7701234567890"), 1)));

        Map<String, Object> p = producto(caja, "RAP-1");
        igual("1.00", p.get("costo"));
        assertEquals(true, p.get("costo_provisional"));
        assertEquals("RAP-1", p.get("sku"));
        assertEquals("Tornillo hexagonal", p.get("nombre"));
        assertEquals("7701234567890", p.get("codigo_barras"));
        assertEquals(true, p.get("activo"));
        assertEquals(caja.sinProveedorId, p.get("proveedor_id"));
        assertEquals(caja.sinCategoriaId, p.get("categoria_id"));
    }

    @Test
    void productoNuevoQuedaEnElCatalogoConSuPrecio() {
        ventas.registrar(venta(UUID.randomUUID(), "15000", null,
                lineaNueva(nuevo("Tornillo hexagonal", "7500", "7701234567890"), 2)));
        Integer id = idDe(caja, "RAP-1");

        Map<String, Object> vista = jdbc.sql("""
                SELECT precio_contado, precio_credito, existencia FROM v_precios_producto
                 WHERE empresa_id = :e AND producto_id = :p
                """).param("e", caja.empresa.empresaId).param("p", id).query().singleRow();
        igual("7500", vista.get("precio_contado"));
        igual("7500", vista.get("precio_credito"));
        assertEquals(-2, vista.get("existencia"));
        assertEquals(2, cuantos("SELECT COUNT(*) FROM reglas_margen WHERE empresa_id = :e AND producto_id = :p",
                "p", id), "una regla de precio fijo propia para el contado y otra para el crédito");
        assertEquals(2, cuantos("""
                SELECT COUNT(*) FROM reglas_margen WHERE empresa_id = :e AND producto_id = :p
                   AND precio_fijo = 7500 AND forma_calculo = 'SOBRE_COSTO'
                """, "p", id));

        ProductoParaCaja porCodigo = cajaProductos.porCodigo("RAP-1").orElseThrow();
        assertEquals(id, porCodigo.id());
        assertEquals("Tornillo hexagonal", porCodigo.nombre());
        igual("7500", porCodigo.precio());
        assertTrue(porCodigo.vendible());
        assertEquals(-2, porCodigo.existencia());
        assertEquals(id, cajaProductos.porCodigo("7701234567890").orElseThrow().id(), "y por su código de barras");
        List<ProductoParaCaja> porTexto = cajaProductos.buscar("hexagonal", 0);
        assertEquals(1, porTexto.size());
        assertEquals(id, porTexto.get(0).id());
    }

    @Test
    void productoNuevoUsaSinProveedorYSinCategoriaYNoCreaOtros() {
        ventas.registrar(venta(UUID.randomUUID(), "15000", null,
                lineaNueva(nuevo("Tornillo hexagonal", "7500", null), 2)));
        ventas.registrar(venta(UUID.randomUUID(), "800", null, lineaNueva(nuevo("Tuerca", "800", null), 1)));

        assertEquals(caja.sinProveedorId, producto(caja, "RAP-1").get("proveedor_id"));
        assertEquals(caja.sinProveedorId, producto(caja, "RAP-2").get("proveedor_id"));
        assertEquals(caja.sinCategoriaId, producto(caja, "RAP-1").get("categoria_id"));
        assertEquals(caja.sinCategoriaId, producto(caja, "RAP-2").get("categoria_id"));
        assertEquals(1, cuantos("SELECT COUNT(*) FROM proveedores WHERE empresa_id = :e AND razon_social = 'Sin proveedor'"),
                "existe una sola vez por empresa");
        assertEquals(1, cuantos("SELECT COUNT(*) FROM categorias WHERE empresa_id = :e AND nombre = 'Sin categoría'"));
        assertEquals(2, cuantos("SELECT COUNT(*) FROM proveedores WHERE empresa_id = :e"), "el de la prueba y «Sin proveedor»");
        assertEquals(2, cuantos("SELECT COUNT(*) FROM categorias WHERE empresa_id = :e"), "la de la prueba y «Sin categoría»");
    }

    @Test
    void siFaltanSinProveedorOSinCategoriaEsUnErrorDeDatosYLaVentaNoLosCrea() {
        caja.quitarSinProveedorYSinCategoria();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ventas.procesar(
                venta(UUID.randomUUID(), "7500", null, lineaNueva(nuevo("Tornillo", "7500", null), 1))));

        assertTrue(e.getMessage().contains(String.valueOf(caja.empresa.empresaId)), "dice qué empresa está mal sembrada");
        nadaCreado();
        assertEquals(0, cuantos("SELECT COUNT(*) FROM proveedores WHERE empresa_id = :e AND razon_social = 'Sin proveedor'"));
        assertEquals(0, cuantos("SELECT COUNT(*) FROM categorias WHERE empresa_id = :e AND nombre = 'Sin categoría'"));
    }

    @Test
    void elCodigoRapidoEsConsecutivoPorEmpresa() {
        otraCaja = CajaDePrueba.crear("Otra empresa", jdbc, plataforma, categorias, proveedores, clientes);
        otraCaja.abrirTurno(otraCaja.vendedorId);

        vender(caja, "Tornillo", "1000", 1);
        vender(caja, "Tuerca", "500", 1);
        vender(otraCaja, "Arandela", "300", 1);
        caja.entrarComoVendedor();
        ventas.registrar(venta(UUID.randomUUID(), "3000", pin(),
                lineaNueva(nuevo("Pegante", "1000", null), 1), lineaNueva(nuevo("Lija", "2000", null), 1)));

        assertEquals("Tornillo", producto(caja, "RAP-1").get("nombre"));
        assertEquals("Tuerca", producto(caja, "RAP-2").get("nombre"));
        assertEquals("Pegante", producto(caja, "RAP-3").get("nombre"));
        assertEquals("Lija", producto(caja, "RAP-4").get("nombre"), "sin ceros a la izquierda y en el orden de las líneas");
        assertEquals("Arandela", producto(otraCaja, "RAP-1").get("nombre"), "otra empresa también empieza en RAP-1");
        assertEquals(5, jdbc.sql("""
                SELECT siguiente FROM consecutivos WHERE empresa_id = :e AND tipo = 'PRODUCTO_RAPIDO'
                """).param("e", caja.empresa.empresaId).query(Integer.class).single());
    }

    @Test
    void siElCodigoRapidoYaLoTieneOtroProductoSeSaltaAlSiguiente() {
        caja.producto("X", 5, "1000");
        jdbc.sql("UPDATE productos SET codigo = 'RAP-1', sku = 'RAP-1' WHERE empresa_id = :e AND nombre = 'Producto X'")
                .param("e", caja.empresa.empresaId).update();

        vender(caja, "Tornillo", "1000", 1);

        assertEquals("Tornillo", producto(caja, "RAP-2").get("nombre"));
        assertEquals("Producto X", producto(caja, "RAP-1").get("nombre"), "el existente no se toca");
    }

    @Test
    void unaVentaMezclaProductosDelCatalogoYProductosNuevos() {
        Integer delCatalogo = caja.producto("A", 5, "10000");
        caja.entrarComoVendedor();

        VentaRegistrada r = ventas.registrar(venta(UUID.randomUUID(), "25000", pin(),
                lineaNueva(nuevo("Tornillo hexagonal", "7500", null), 2),
                new LineaSolicitada(delCatalogo, null, 1, null, null)));

        assertEquals(4, caja.existencia(delCatalogo), "el del catálogo descuenta lo vendido");
        assertEquals(-2, caja.existencia(idDe(caja, "RAP-1")));
        igual("25000", r.total());
        assertEquals(2, r.lineas().size());
        List<Map<String, Object>> lineas = jdbc.sql("""
                SELECT producto_id, cantidad_encargo FROM venta_items WHERE empresa_id = :e AND venta_id = :v
                 ORDER BY producto_id
                """).param("e", caja.empresa.empresaId).param("v", r.id()).query().listOfRows();
        assertEquals(2, lineas.size());
        assertEquals(0, lineas.get(0).get("cantidad_encargo"), "lo que había no es encargo");
        assertEquals(2, lineas.get(1).get("cantidad_encargo"));
        assertEquals(2, cuantos("SELECT COUNT(*) FROM movimientos_inventario WHERE empresa_id = :e AND tipo = 'EGRESO'"));
    }

    @Test
    void unaVentaSoloDeProductosNuevosNoNecesitaProductosDelCatalogo() {
        VentaRegistrada r = ventas.registrar(venta(UUID.randomUUID(), "9000", null,
                lineaNueva(nuevo("Tornillo", "1000", null), 4), lineaNueva(nuevo("Tuerca", "500", null), 10)));

        assertEquals(2, r.lineas().size());
        assertEquals(2, caja.cuantas("productos"));
    }

    // ------------------------------------------------------- el precio y el código de barras

    @Test
    void precioInvalidoDelProductoNuevoEs400YNoQuedaNada() {
        for (String precio : List.of("0", "-100", "1500.50", "1000000000000", "1E+20")) {
            assertThrows(VentaInvalidaException.class, () -> ventas.procesar(
                    venta(UUID.randomUUID(), "1000", null, lineaNueva(nuevo("Tornillo", precio, null), 1))), precio);
        }

        nadaCreado();
    }

    @Test
    void elCodigoDeBarrasDeOtroProductoEs409YNoQuedaNada() {
        Integer existente = caja.producto("A", 5, "10000");
        jdbc.sql("UPDATE productos SET codigo_barras = '7701234567890' WHERE empresa_id = :e AND producto_id = :p")
                .param("e", caja.empresa.empresaId).param("p", existente).update();

        assertThrows(NombreRepetidoException.class, () -> ventas.procesar(venta(UUID.randomUUID(), "7500", null,
                lineaNueva(nuevo("Tornillo", "7500", "7701234567890"), 1))));

        assertEquals(1, caja.cuantas("productos"), "solo el que ya estaba");
        assertEquals(2, caja.cuantas("reglas_margen"), "y solo las dos reglas de ese producto");
        assertEquals(0, caja.cuantas("ventas"));
        assertEquals(0, caja.cuantas("consecutivos"), "el consecutivo del producto no se consumió");
    }

    // ------------------------------------------------------- la atomicidad

    @Test
    void siLaVentaFallaDespuesDeCrearElProductoNoQuedaCreadoNiSuReglaNiSuConsecutivo() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("falla el kárdex")).when(kardex)
                .registrarEgresos(any(), any(), any(), any());

        assertThrows(org.springframework.dao.DataAccessException.class, () -> ventas.procesar(
                venta(UUID.randomUUID(), "15000", null, lineaNueva(nuevo("Tornillo", "7500", "7701234567890"), 2))));

        nadaCreado();
    }

    @Test
    void siLosPagosNoSumanElProductoNuevoNoQuedaCreado() {
        assertThrows(VentaInvalidaException.class, () -> ventas.procesar(
                venta(UUID.randomUUID(), "1000", null, lineaNueva(nuevo("Tornillo", "7500", null), 2))));

        nadaCreado();
    }

    // ------------------------------------------------------- el reintento

    @Test
    void unReintentoDevuelveLaMismaVentaYNoCreaUnSegundoProducto() {
        UUID clave = UUID.randomUUID();
        SolicitudVenta solicitud = venta(clave, "15000", null,
                lineaNueva(nuevo("Tornillo hexagonal", "7500", "7701234567890"), 2));

        ResultadoDeVenta primera = ventas.procesar(solicitud);
        ResultadoDeVenta segunda = ventas.procesar(solicitud);

        assertFalse(primera.yaExistia());
        assertTrue(segunda.yaExistia());
        assertEquals(primera.venta().id(), segunda.venta().id());
        assertEquals(1, caja.cuantas("productos"), "un solo producto");
        assertEquals(1, caja.cuantas("ventas"));
        assertEquals(2, cuantos("SELECT COUNT(*) FROM reglas_margen WHERE empresa_id = :e"));
        assertEquals(2, cuantos("SELECT siguiente FROM consecutivos WHERE empresa_id = :e AND tipo = 'PRODUCTO_RAPIDO'"),
                "el reintento no consumió otro código");
        assertEquals(-2, caja.existencia(idDe(caja, "RAP-1")));
    }

    @Test
    void laMismaClaveConOtroProductoNuevoEs409YNoCreaNada() {
        UUID clave = UUID.randomUUID();
        ventas.procesar(venta(clave, "15000", null, lineaNueva(nuevo("Tornillo hexagonal", "7500", null), 2)));

        for (ProductoNuevo otro : List.of(nuevo("Tornillo largo", "7500", null), nuevo("Tornillo hexagonal", "7600", null),
                nuevo("Tornillo hexagonal", "7500", "7701234567890"))) {
            String pago = otro.precioVenta().multiply(BigDecimal.valueOf(2)).toPlainString();
            assertThrows(ClaveDeIdempotenciaEnUsoException.class,
                    () -> ventas.procesar(venta(clave, pago, null, lineaNueva(otro, 2))));
        }

        assertEquals(1, caja.cuantas("productos"));
        assertEquals(1, caja.cuantas("ventas"));
    }

    // ------------------------------------------------------- el costo provisional

    @Test
    void editarElCostoApagaElCostoProvisional() {
        ventas.registrar(venta(UUID.randomUUID(), "7500", null, lineaNueva(nuevo("Tornillo", "7500", null), 1)));
        Integer id = idDe(caja, "RAP-1");

        // Editar otra cosa sin tocar el costo no lo confirma.
        editar(id, "Tornillo hexagonal", "1.00");
        assertEquals(true, producto(caja, "RAP-1").get("costo_provisional"), "el costo no cambió: sigue provisional");

        editar(id, "Tornillo hexagonal", "3200.00");

        Map<String, Object> p = producto(caja, "RAP-1");
        igual("3200.00", p.get("costo"));
        assertEquals(false, p.get("costo_provisional"), "el administrador escribió el costo real");
    }

    private void editar(Integer id, String nombre, String costo) {
        ProductoEnCatalogo p = productos.buscar(id).orElseThrow();
        productos.actualizar(id, new SolicitudEdicionProducto(p.categoriaId(), p.proveedorId(), p.codigo(), p.sku(), null,
                nombre, null, null, new BigDecimal(costo), 5, null, null, null, p.version()));
    }

    @Test
    void elCatalogoDelAdministradorTraeCostoProvisionalYElDelVendedorNo() throws Exception {
        ventas.registrar(venta(UUID.randomUUID(), "7500", null, lineaNueva(nuevo("Tornillo", "7500", null), 1)));
        Integer id = idDe(caja, "RAP-1");
        caja.conPruebaVigente();

        mvc.perform(get("/api/v1/productos/" + id).with(como(caja.administradorId, "ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costoProvisional").value(true))
                .andExpect(jsonPath("$.costo").value(1.0));
        mvc.perform(get("/api/v1/productos").param("busqueda", "RAP-").with(como(caja.administradorId, "ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].costoProvisional").value(true));
        mvc.perform(get("/api/v1/productos/" + id).with(como(caja.vendedorId, "VENDEDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioContado").exists())
                .andExpect(jsonPath("$.costoProvisional").doesNotExist())
                .andExpect(jsonPath("$.costo").doesNotExist());
        mvc.perform(get("/api/v1/productos").param("busqueda", "RAP-").with(como(caja.vendedorId, "VENDEDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].costoProvisional").doesNotExist());
        mvc.perform(get("/api/v1/caja/productos").param("codigo", "RAP-1").with(como(caja.administradorId, "ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costoProvisional").doesNotExist())
                .andExpect(jsonPath("$.costo").doesNotExist());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor como(Integer usuario, String rol) {
        var u = new UsuarioAutenticado(usuario, "prueba@ejemplo.co", rol, caja.empresa.empresaId);
        return authentication(new UsernamePasswordAuthenticationToken(u, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + rol))));
    }

    @Test
    void unProductoDelCatalogoNaceConCostoDefinitivo() {
        Integer id = caja.producto("A", 5, "10000");

        assertEquals(false, jdbc.sql("SELECT costo_provisional FROM productos WHERE empresa_id = :e AND producto_id = :p")
                .param("e", caja.empresa.empresaId).param("p", id).query().singleRow().get("costo_provisional"));
    }
}
