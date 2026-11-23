package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.RegistradorDeVenta;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioAutorizacion;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El reintento de una venta ya registrada devuelve esa venta, pase lo que pase después: el turno
 * se cerró, el PIN no viene o quedó bloqueado, o la fecha de entrega ya es de ayer. La respuesta de
 * la primera vez se perdió por la red; el cajero la repite con la misma clave y tiene que recibir
 * su venta (200), sin contar un intento de PIN y sin tocar el inventario.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class ReintentoDeVentaConBaseTest {

    @Autowired ServicioVentas ventas;
    @Autowired ServicioAutorizacion autorizacion;
    @Autowired RegistradorDeVenta registrador;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private Long turnoDelAdministrador;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Reintento", jdbc, plataforma, categorias, proveedores, clientes);
        turnoDelAdministrador = caja.abrirTurno(caja.administradorId);
        caja.abrirTurno(caja.vendedorId);
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
    }

    private SolicitudVenta venta(UUID clave, Integer producto, int cantidad, String pago) {
        return new SolicitudVenta(clave, null, List.of(new LineaSolicitada(producto, null, cantidad, null, null)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal(pago), null, null)), null, null, null);
    }

    private int intentosDelPin() {
        return jdbc.sql("SELECT pin_intentos + pin_errados_emitidos FROM usuarios WHERE usuario_id IN (:a, :v)")
                .param("a", caja.administradorId).param("v", caja.vendedorId).query(Integer.class).list()
                .stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void reintentarConElTurnoCerradoDevuelveLaVentaYNoTocaNada() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoAdministrador();
        SolicitudVenta solicitud = venta(UUID.randomUUID(), producto, 2, "20000");
        ResultadoDeVenta primera = ventas.procesar(solicitud);
        caja.cerrarTurno(turnoDelAdministrador);

        ResultadoDeVenta reintento = ventas.procesar(solicitud);

        assertTrue(reintento.yaExistia());
        assertEquals(primera.venta().id(), reintento.venta().id());
        assertEquals(primera.venta(), reintento.venta());
        assertEquals(1, caja.movimientosDe(producto, "EGRESO"), "sin nuevos movimientos");
        assertEquals(3, caja.existencia(producto));
        assertEquals(1, caja.cuantas("ventas"));
    }

    @Test
    void reintentarSinPinUnaVentaConDescuentoDeUnVendedorDevuelveLaVentaYNoCuentaIntentos() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoVendedor();
        UUID clave = UUID.randomUUID();
        var conDescuento = new LineaSolicitada(producto, null, 1, new Descuento(TipoDescuento.PESOS, new BigDecimal("500")), null);
        var pagos = List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("9500"), null, null));
        var conPin = new SolicitudVenta(clave, null, List.of(conDescuento), null, pagos, null,
                new SolicitudAutorizacion(caja.administradorId, CajaDePrueba.PIN), null);
        var sinPin = new SolicitudVenta(clave, null, List.of(conDescuento), null, pagos, null, null, null);
        var conPinErrado = new SolicitudVenta(clave, null, List.of(conDescuento), null, pagos, null,
                new SolicitudAutorizacion(caja.administradorId, "000000"), null);
        ResultadoDeVenta primera = ventas.procesar(conPin);
        int antes = intentosDelPin();

        ResultadoDeVenta sin = ventas.procesar(sinPin);
        ResultadoDeVenta errado = ventas.procesar(conPinErrado);

        assertTrue(sin.yaExistia());
        assertTrue(errado.yaExistia());
        assertEquals(primera.venta().id(), sin.venta().id());
        assertEquals(primera.venta().id(), errado.venta().id());
        assertEquals(antes, intentosDelPin(), "reconocer el reintento no cuenta ningún intento de PIN");
        assertEquals(1, caja.cuantas("ventas"));
    }

    @Test
    void reintentarUnEncargoUnDiaDespuesDevuelveLaVentaAunqueLaFechaYaSeaDeAyer() {
        Integer producto = caja.producto("A", 1, "10000");
        caja.entrarComoAdministrador();
        LocalDate hoy = LocalDate.now();
        UUID clave = UUID.randomUUID();
        var solicitud = new SolicitudVenta(clave, null,
                List.of(new LineaSolicitada(producto, null, 3, null, hoy)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("30000"), null, null)), "Llega hoy", null, null);
        ResultadoDeVenta primera = ventas.procesar(solicitud);
        Clock manana = Clock.offset(Clock.systemUTC(), Duration.ofDays(2));
        ServicioVentas conElRelojDeDespues = new ServicioVentas(autorizacion, registrador, manana);

        ResultadoDeVenta reintento = conElRelojDeDespues.procesar(solicitud);

        assertTrue(reintento.yaExistia());
        assertEquals(primera.venta().id(), reintento.venta().id());
        assertEquals(-2, caja.existencia(producto), "el encargo no se descontó otra vez");
        // Una venta nueva con esa misma fecha, en cambio, ya es de ayer.
        var nueva = new SolicitudVenta(UUID.randomUUID(), null, solicitud.lineas(), null, solicitud.pagos(),
                "Llega hoy", null, null);
        assertThrows(co.com.bspstore.sgip.logica.VentaInvalidaException.class, () -> conElRelojDeDespues.procesar(nueva));
    }

    @Test
    void laMismaClaveConOtraCargaConElTurnoCerradoEs409DeCargaDistintaYNoDeTurno() {
        Integer producto = caja.producto("A", 5, "10000");
        caja.entrarComoAdministrador();
        UUID clave = UUID.randomUUID();
        ventas.procesar(venta(clave, producto, 1, "10000"));
        caja.cerrarTurno(turnoDelAdministrador);

        assertThrows(ClaveDeIdempotenciaEnUsoException.class, () -> ventas.procesar(venta(clave, producto, 2, "20000")));
        // Una venta distinta, con otra clave, sí recibe el 409 de sin turno.
        assertThrows(SinTurnoAbiertoException.class, () -> ventas.procesar(venta(UUID.randomUUID(), producto, 1, "10000")));
        assertEquals(1, caja.cuantas("ventas"));
    }
}
