package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CotizacionDetalle;
import co.com.bspstore.sgip.api.dto.LineaCotizada;
import co.com.bspstore.sgip.api.dto.LineaDeCotizacion;
import co.com.bspstore.sgip.api.dto.SolicitudCotizacion;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioCotizaciones;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crear y leer una cotización (RF-37) contra la base real: lo que se guarda, con qué número y a qué
 * precio, y lo que NO queda cuando algo falla. El reloj es de la prueba, para fijar el día.
 *
 * Crea sus empresas (con administrador, vendedor, clientes y productos) y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class CotizacionConBaseTest {

    /** Un reloj que la prueba mueve: arranca en el instante real y se fija con {@link #fijar}. */
    static final class RelojDePrueba extends Clock {
        private volatile Instant ahora = Instant.now();

        void fijar(String instante) {
            ahora = Instant.parse(instante);
        }

        @Override public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override public Instant instant() {
            return ahora;
        }
    }

    @TestConfiguration
    static class Configuracion {
        @Bean
        @Primary
        RelojDePrueba relojDePrueba() {
            return new RelojDePrueba();
        }
    }

    /** El miércoles 1 de abril de 2026, a las 10:00 en Bogotá. */
    private static final String UNO_DE_ABRIL = "2026-04-01T15:00:00Z";

    @Autowired ServicioCotizaciones cotizaciones;
    @Autowired RelojDePrueba reloj;
    @Autowired RepositorioVentas repositorioVentas;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private CajaDePrueba otra;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Cotización", jdbc, plataforma, categorias, proveedores, clientes);
        reloj.fijar(UNO_DE_ABRIL);
        vigenciaDe(caja, 5);
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
        if (otra != null) {
            otra.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    private void vigenciaDe(CajaDePrueba de, int dias) {
        jdbc.sql("""
                INSERT INTO empresa_config (empresa_id, cotizacion_vigencia_dias) VALUES (:e, :dias)
                ON DUPLICATE KEY UPDATE cotizacion_vigencia_dias = :dias
                """).param("e", de.empresa.empresaId).param("dias", dias).update();
    }

    private SolicitudCotizacion pedir(Integer cliente, LineaCotizada... lineas) {
        return new SolicitudCotizacion(cliente, List.of(lineas), null);
    }

    private static LineaCotizada linea(Integer producto, int cantidad) {
        return new LineaCotizada(producto, cantidad);
    }

    private CotizacionDetalle crear(CajaDePrueba de, Integer usuario, Integer cliente, LineaCotizada... lineas) {
        return cotizaciones.crear(de.empresa.empresaId, usuario, pedir(cliente, lineas));
    }

    private CotizacionDetalle crear(Integer cliente, LineaCotizada... lineas) {
        return crear(caja, caja.vendedorId, cliente, lineas);
    }

    private Map<String, Object> cabecera(CajaDePrueba de, Long id) {
        return jdbc.sql("SELECT * FROM cotizaciones WHERE empresa_id = :e AND cotizacion_id = :id")
                .param("e", de.empresa.empresaId).param("id", id).query().singleRow();
    }

    private List<Map<String, Object>> filas(CajaDePrueba de, Long id) {
        return jdbc.sql("""
                SELECT * FROM cotizacion_items WHERE empresa_id = :e AND cotizacion_id = :id ORDER BY producto_id
                """).param("e", de.empresa.empresaId).param("id", id).query().listOfRows();
    }

    private static void igual(String esperado, Object real, String que) {
        assertEquals(0, new BigDecimal(esperado).compareTo((BigDecimal) real), que + ": esperaba " + esperado + " y fue " + real);
    }

    // ------------------------------------------------------- crear

    @Test
    void creaVigenteConNumeroYVencimiento() {
        Integer producto = caja.producto("A", 5, "10000");
        int movimientosAntes = caja.cuantas("movimientos_inventario");
        int existenciaAntes = caja.existencia(producto);

        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 2));

        assertEquals("COT-00000001", c.numero());
        assertEquals("VIGENTE", c.estado());
        assertEquals(LocalDate.of(2026, 4, 1), c.fechaEmision());
        assertEquals(LocalDate.of(2026, 4, 10), c.fechaVencimiento(), "5 días hábiles: el 2 y el 3 son Semana Santa");
        assertEquals(5, c.vigenciaDias());
        assertEquals(caja.clienteId, c.clienteId());
        assertEquals(caja.vendedorId, c.usuarioId());
        assertEquals("Vendedor", c.creadaPor());
        assertNull(c.ventaId());
        assertNull(c.motivoAnulacion());
        Map<String, Object> fila = cabecera(caja, c.id());
        assertEquals("COT-00000001", fila.get("consecutivo"));
        assertEquals("VIGENTE", fila.get("estado"));
        assertEquals(5, fila.get("vigencia_dias"));
        assertEquals("CONTADO", fila.get("forma_pago"));
        igual("0", fila.get("descuento"), "descuento");
        igual("0", fila.get("envio"), "envío");
        assertEquals(movimientosAntes, caja.cuantas("movimientos_inventario"), "cotizar no mueve el kárdex");
        assertEquals(existenciaAntes, caja.existencia(producto), "cotizar no descuenta existencias");
        assertEquals(0, caja.cuantas("ventas"));
    }

    @Test
    void laVigenciaDeLaEmpresaSeCopiaALaCotizacion() {
        Integer producto = caja.producto("A", 5, "10000");
        vigenciaDe(caja, 3);

        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1));
        vigenciaDe(caja, 10);

        assertEquals(3, c.vigenciaDias());
        assertEquals(LocalDate.of(2026, 4, 8), c.fechaVencimiento(), "6, 7 y 8 de abril");
        assertEquals(3, cabecera(caja, c.id()).get("vigencia_dias"), "un cambio posterior no toca la emitida");
    }

    @Test
    void laVigenciaCruzaElAnioConSusFestivos() {
        Integer producto = caja.producto("A", 5, "10000");
        reloj.fijar("2026-12-28T15:00:00Z");

        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1));

        assertEquals(LocalDate.of(2027, 1, 5), c.fechaVencimiento(), "29, 30 y 31 de dic.; el 1 de ene. es festivo; 4 y 5");
    }

    @Test
    void elImpuestoSeRedondeaComoLaVenta() {
        caja.responsableDeIva19();
        Integer producto = caja.producto("A", 5, "12345");

        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1));

        LineaDeCotizacion l = c.lineas().get(0);
        igual("12345", l.precioUnitario(), "precio");
        igual("0.1900", l.tarifaIva(), "tarifa");
        assertEquals("GRAVADO", l.tratamientoIva());
        igual("12345", l.subtotal(), "subtotal de la línea");
        igual("2346", l.valorIva(), "IVA de la línea en el detalle");
        igual("12345", c.subtotal(), "subtotal");
        igual("2346", c.iva(), "IVA");
        igual("14691", c.total(), "total");
        Map<String, Object> fila = filas(caja, c.id()).get(0);
        igual("2346.00", fila.get("valor_iva"), "valor_iva guardado: 2 345,55 se redondea a pesos");
        igual("12345.00", fila.get("subtotal"), "subtotal guardado");
        Map<String, Object> cab = cabecera(caja, c.id());
        igual("2346", cab.get("iva"), "iva de la cabecera");
        igual("14691", cab.get("total"), "total de la cabecera");
    }

    @Test
    void elImpuestoSeRedondeaPorLineaYSeSuma() {
        caja.responsableDeIva19();
        Integer a = caja.producto("A", 5, "1001");
        Integer b = caja.producto("B", 5, "1001");

        CotizacionDetalle c = crear(caja.clienteId, linea(a, 1), linea(b, 1));

        // 1001 * 0,19 = 190,19: cada línea da 190 y la suma 380 (y no 380,38 redondeado al final).
        igual("190", c.lineas().get(0).valorIva(), "IVA de A");
        igual("190", c.lineas().get(1).valorIva(), "IVA de B");
        igual("380", c.iva(), "IVA de la cotización");
        igual("2002", c.subtotal(), "subtotal");
    }

    @Test
    void losPreciosQuedanCongelados() {
        Integer producto = caja.producto("A", 5, "10000");
        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 2));
        jdbc.sql("UPDATE reglas_margen SET precio_fijo = 20000 WHERE empresa_id = :e AND producto_id = :p")
                .param("e", caja.empresa.empresaId).param("p", producto).update();
        assertEquals(0, new BigDecimal("20000").compareTo(
                repositorioVentas.preciosVigentes(caja.empresa.empresaId, List.of(producto)).get(0).precio()),
                "el precio de hoy sí subió");

        CotizacionDetalle despues = cotizaciones.detalle(caja.empresa.empresaId, c.id());

        assertEquals("VIGENTE", despues.estado());
        igual("10000", despues.lineas().get(0).precioUnitario(), "el precio congelado");
        igual("20000", despues.subtotal(), "subtotal congelado");
        assertNull(despues.lineas().get(0).precioHoy(), "una vigente no muestra el precio de hoy");
        assertTrue(despues.lineas().get(0).disponible());
    }

    @Test
    void productoRepetidoSeSuma() {
        Integer producto = caja.producto("A", 50, "10000");

        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 2), linea(producto, 3));

        assertEquals(1, c.lineas().size());
        assertEquals(5, c.lineas().get(0).cantidad());
        igual("50000", c.subtotal(), "subtotal");
        assertEquals(1, filas(caja, c.id()).size());
        assertEquals(5, filas(caja, c.id()).get(0).get("cantidad"));
    }

    @Test
    void laSumaDeUnProductoRepetidoNoPasaElTopeDeUnidades() {
        Integer producto = caja.producto("A", 50, "10000");

        assertThrows(VentaInvalidaException.class,
                () -> crear(caja.clienteId, linea(producto, 99_999), linea(producto, 1)));

        assertEquals(0, caja.cuantas("cotizaciones"));
    }

    @Test
    void lasLineasSalenEnOrdenDeProducto() {
        Integer a = caja.producto("A", 5, "10000");
        Integer b = caja.producto("B", 5, "20000");

        CotizacionDetalle c = crear(caja.clienteId, linea(b, 1), linea(a, 1));

        assertEquals(List.of(a, b), c.lineas().stream().map(LineaDeCotizacion::productoId).toList());
        assertEquals("A-" + caja.empresa.marca, c.lineas().get(0).codigo());
        assertEquals("Producto A", c.lineas().get(0).nombre());
    }

    @Test
    void sinClienteSeCotizaAlConsumidorFinal() {
        Integer producto = caja.producto("A", 5, "10000");

        CotizacionDetalle c = crear(null, linea(producto, 1));

        assertEquals(caja.consumidorFinalId, c.clienteId());
        assertEquals("Consumidor final", c.clienteNombre());
    }

    @Test
    void elDetalleTraeElNombreYLaIdentificacionDelCliente() {
        Integer producto = caja.producto("A", 5, "10000");

        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1));

        assertEquals("Cliente " + caja.empresa.marca.toLowerCase(), c.clienteNombre());
        assertEquals("9" + caja.empresa.marca.toLowerCase(), c.clienteIdentificacion());
    }

    @Test
    void lasObservacionesSeGuardanYSeDevuelven() {
        Integer producto = caja.producto("A", 5, "10000");

        CotizacionDetalle c = cotizaciones.crear(caja.empresa.empresaId, caja.vendedorId,
                new SolicitudCotizacion(caja.clienteId, List.of(linea(producto, 1)), "Entrega en obra"));

        assertEquals("Entrega en obra", c.observaciones());
        assertEquals("Entrega en obra", cabecera(caja, c.id()).get("observaciones"));
    }

    @Test
    void numeracionPorEmpresa() {
        otra = CajaDePrueba.crear("Cotización B", jdbc, plataforma, categorias, proveedores, clientes);
        vigenciaDe(otra, 5);
        Integer deA = caja.producto("A", 5, "10000");
        Integer deB = otra.producto("A", 5, "10000");

        CotizacionDetalle a1 = crear(caja.clienteId, linea(deA, 1));
        CotizacionDetalle b1 = crear(otra, otra.vendedorId, otra.clienteId, linea(deB, 1));
        CotizacionDetalle a2 = crear(caja.clienteId, linea(deA, 1));

        assertEquals("COT-00000001", a1.numero());
        assertEquals("COT-00000001", b1.numero());
        assertEquals("COT-00000002", a2.numero());
    }

    // ------------------------------------------------------- lo que se rechaza y no deja nada

    @Test
    void unClienteDeOtraEmpresaEsUn404SinGastarNumero() {
        otra = CajaDePrueba.crear("Cotización B", jdbc, plataforma, categorias, proveedores, clientes);
        Integer producto = caja.producto("A", 5, "10000");

        assertThrows(RecursoNoEncontradoException.class, () -> crear(otra.clienteId, linea(producto, 1)));

        assertEquals(0, caja.cuantas("cotizaciones"));
        assertEquals("COT-00000001", crear(caja.clienteId, linea(producto, 1)).numero(), "el 404 no gastó el número");
    }

    @Test
    void unProductoDeOtraEmpresaEsUn404() {
        otra = CajaDePrueba.crear("Cotización B", jdbc, plataforma, categorias, proveedores, clientes);
        Integer ajeno = otra.producto("A", 5, "10000");

        assertThrows(RecursoNoEncontradoException.class, () -> crear(caja.clienteId, linea(ajeno, 1)));
        assertThrows(RecursoNoEncontradoException.class, () -> crear(caja.clienteId, linea(987_654_321, 1)));

        assertEquals(0, caja.cuantas("cotizaciones"));
    }

    @Test
    void unProductoInactivoEsUn400() {
        Integer activo = caja.producto("A", 5, "10000");
        Integer inactivo = caja.producto("B", 5, "10000");
        caja.desactivarProducto(inactivo);

        VentaInvalidaException e = assertThrows(VentaInvalidaException.class,
                () -> crear(caja.clienteId, linea(activo, 1), linea(inactivo, 1)));

        assertEquals("Este producto no se puede vender: no tiene un precio de venta válido.", e.getMessage());
        assertEquals(0, caja.cuantas("cotizaciones"));
        assertEquals(0, caja.cuantas("cotizacion_items"));
    }

    @Test
    void unProductoSinPrecioEsUn400() {
        Integer sinPrecio = caja.productoSinPrecio("A", 5);

        assertThrows(VentaInvalidaException.class, () -> crear(caja.clienteId, linea(sinPrecio, 1)));

        assertEquals(0, caja.cuantas("cotizaciones"));
    }

    @Test
    void unSubtotalQueNoCabeNoSeGuarda() {
        Integer enorme = caja.producto("A", 5, "400000000000");

        assertThrows(VentaInvalidaException.class, () -> crear(caja.clienteId, linea(enorme, 2)));

        assertEquals(0, caja.cuantas("cotizaciones"));
    }

    @Test
    void siFallaLaInsercionNoQuedaNadaNiSeGastaElNumero() {
        Integer producto = caja.producto("A", 5, "10000");
        // Un usuario de otra empresa rompe la clave compuesta de la cabecera, después de tomar el número.
        otra = CajaDePrueba.crear("Cotización B", jdbc, plataforma, categorias, proveedores, clientes);

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> cotizaciones.crear(caja.empresa.empresaId, otra.vendedorId,
                        pedir(caja.clienteId, linea(producto, 1))));

        assertEquals(0, caja.cuantas("cotizaciones"));
        assertEquals("COT-00000001", crear(caja.clienteId, linea(producto, 1)).numero());
    }

    // ------------------------------------------------------- leer

    @Test
    void unIdDeOtraEmpresaEsUn404() {
        otra = CajaDePrueba.crear("Cotización B", jdbc, plataforma, categorias, proveedores, clientes);
        Integer producto = caja.producto("A", 5, "10000");
        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1));

        assertThrows(RecursoNoEncontradoException.class, () -> cotizaciones.detalle(otra.empresa.empresaId, c.id()));
        assertThrows(RecursoNoEncontradoException.class, () -> cotizaciones.detalle(caja.empresa.empresaId, 987_654_321L));
    }

    @Test
    void vencidaAlLeer() {
        Integer producto = caja.producto("A", 5, "10000");
        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 2));
        jdbc.sql("UPDATE reglas_margen SET precio_fijo = 20000 WHERE empresa_id = :e AND producto_id = :p")
                .param("e", caja.empresa.empresaId).param("p", producto).update();

        reloj.fijar("2026-04-10T15:00:00Z");
        assertEquals("VIGENTE", cotizaciones.detalle(caja.empresa.empresaId, c.id()).estado(),
                "el día del vencimiento todavía vale");

        reloj.fijar("2026-04-13T15:00:00Z");
        CotizacionDetalle vencida = cotizaciones.detalle(caja.empresa.empresaId, c.id());

        assertEquals("VENCIDA", vencida.estado());
        assertEquals("VIGENTE", cabecera(caja, c.id()).get("estado"), "VENCIDA no se escribe");
        LineaDeCotizacion l = vencida.lineas().get(0);
        igual("10000", l.precioUnitario(), "el precio congelado sigue ahí");
        igual("20000", l.precioHoy(), "el precio de hoy");
        assertTrue(l.disponible());
    }

    @Test
    void vencidaConElProductoDesactivadoNoEstaDisponible() {
        Integer producto = caja.producto("A", 5, "10000");
        Integer otroProducto = caja.producto("B", 5, "5000");
        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1), linea(otroProducto, 1));
        caja.desactivarProducto(producto);

        reloj.fijar("2026-04-13T15:00:00Z");
        CotizacionDetalle vencida = cotizaciones.detalle(caja.empresa.empresaId, c.id());

        LineaDeCotizacion desactivada = vencida.lineas().get(0);
        assertFalse(desactivada.disponible());
        assertNull(desactivada.precioHoy());
        igual("10000", desactivada.precioUnitario(), "el congelado se conserva para mostrarlo");
        LineaDeCotizacion activa = vencida.lineas().get(1);
        assertTrue(activa.disponible());
        igual("5000", activa.precioHoy(), "la otra línea sigue con su precio de hoy");
    }

    @Test
    void unaVigenteConUnProductoDesactivadoNoEstaDisponible() {
        Integer producto = caja.producto("A", 5, "10000");
        Integer otro = caja.producto("B", 5, "5000");
        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1), linea(otro, 1));
        caja.desactivarProducto(producto);

        CotizacionDetalle vigente = cotizaciones.detalle(caja.empresa.empresaId, c.id());

        assertEquals("VIGENTE", vigente.estado());
        assertFalse(vigente.lineas().get(0).disponible(), "RF-17: un producto inactivo no se vende");
        assertNull(vigente.lineas().get(0).precioHoy(), "una vigente no muestra el precio de hoy");
        igual("10000", vigente.lineas().get(0).precioUnitario(), "el congelado se conserva para mostrarlo");
        assertTrue(vigente.lineas().get(1).disponible(), "el producto activo sigue disponible");
    }

    @Test
    void unaVigenteConUnProductoActivoSinPrecioHoySigueDisponible() {
        Integer producto = caja.producto("A", 5, "10000");
        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1));
        jdbc.sql("DELETE FROM reglas_margen WHERE empresa_id = :e AND producto_id = :p")
                .param("e", caja.empresa.empresaId).param("p", producto).update();
        assertNull(repositorioVentas.preciosVigentes(caja.empresa.empresaId, List.of(producto)).get(0).precio(),
                "hoy el producto no tiene regla de precio");

        CotizacionDetalle vigente = cotizaciones.detalle(caja.empresa.empresaId, c.id());

        assertEquals("VIGENTE", vigente.estado());
        assertTrue(vigente.lineas().get(0).disponible(), "el congelado vale mientras el producto siga activo");
        assertNull(vigente.lineas().get(0).precioHoy());
        igual("10000", vigente.lineas().get(0).precioUnitario(), "el precio congelado");
    }

    @Test
    void lasObservacionesSeRecortanYUnTextoEnBlancoQuedaNulo() {
        Integer producto = caja.producto("A", 5, "10000");

        CotizacionDetalle enBlanco = cotizaciones.crear(caja.empresa.empresaId, caja.vendedorId,
                new SolicitudCotizacion(caja.clienteId, List.of(linea(producto, 1)), "   "));
        CotizacionDetalle conEspacios = cotizaciones.crear(caja.empresa.empresaId, caja.vendedorId,
                new SolicitudCotizacion(caja.clienteId, List.of(linea(producto, 1)), "  hola  "));

        assertNull(enBlanco.observaciones());
        assertNull(cabecera(caja, enBlanco.id()).get("observaciones"));
        assertEquals("hola", conEspacios.observaciones());
        assertEquals("hola", cabecera(caja, conEspacios.id()).get("observaciones"));
    }

    @Test
    void convertidaYAnuladaNoSeCalculanComoVencidas() {
        Integer producto = caja.producto("A", 5, "10000");
        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1));
        jdbc.sql("UPDATE cotizaciones SET estado = 'CONVERTIDA' WHERE empresa_id = :e AND cotizacion_id = :id")
                .param("e", caja.empresa.empresaId).param("id", c.id()).update();

        reloj.fijar("2026-06-01T15:00:00Z");

        CotizacionDetalle convertida = cotizaciones.detalle(caja.empresa.empresaId, c.id());
        assertEquals("CONVERTIDA", convertida.estado());
        assertNull(convertida.lineas().get(0).precioHoy());
    }

    // ------------------------------------------------------- el calendario

    @Test
    void unAnioSinFestivosCargadosAvisaEnElLog(CapturedOutput salida) {
        Integer producto = caja.producto("A", 5, "10000");
        reloj.fijar("2030-03-01T15:00:00Z");

        CotizacionDetalle c = crear(caja.clienteId, linea(producto, 1));

        assertEquals(LocalDate.of(2030, 3, 8), c.fechaVencimiento(), "solo se saltan los fines de semana");
        String aviso = salida.getAll();
        assertTrue(aviso.contains("calendario_habil") && aviso.contains("2030"),
                "el log avisa que falta el año 2030: " + aviso);
        assertFalse(aviso.contains(c.numero()), "el aviso no lleva datos de la cotización");
        assertFalse(aviso.contains(caja.empresa.marca), "ni datos del cliente ni de la empresa");
    }

    @Test
    void conLosFestivosCargadosNoAvisa(CapturedOutput salida) {
        Integer producto = caja.producto("A", 5, "10000");

        crear(caja.clienteId, linea(producto, 1));

        assertFalse(salida.getAll().contains("calendario_habil"), "2026 tiene festivos: no hay nada que avisar");
    }
}
