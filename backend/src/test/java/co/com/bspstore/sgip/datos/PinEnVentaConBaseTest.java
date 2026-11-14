package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.venta.Descuento;
import co.com.bspstore.sgip.logica.venta.TipoDescuento;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El PIN en la venta (ADR-0032) contra la base y por la API: los intentos erróneos quedan
 * contados aunque la venta no se registre, y la fachada no se llama desde una transacción.
 *
 * Es la repetición, por {@code POST /caja/ventas}, de la prueba «los intentos sobreviven al
 * rollback» de {@code PinConBaseTest}. Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
@AutoConfigureMockMvc
class PinEnVentaConBaseTest {

    private static final String RUTA = "/api/v1/caja/ventas";

    @Autowired MockMvc mvc;
    @Autowired ServicioVentas ventas;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private Integer producto;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Pin en venta", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.vendedorId);
        caja.conPruebaVigente();
        producto = caja.producto("A", 5, "10000");
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
    }

    private String ventaConDescuento(String pin) {
        return """
                {"claveIdempotencia":"%s",
                 "lineas":[{"productoId":%d,"cantidad":1,"descuento":{"tipo":"PESOS","valor":500}}],
                 "pagos":[{"medioId":%d,"monto":9500}],
                 "autorizacion":{"administradorId":%d,"pin":"%s"}}
                """.formatted(UUID.randomUUID(), producto, caja.efectivoId, caja.administradorId, pin);
    }

    private org.springframework.test.web.servlet.ResultActions comoVendedor(String cuerpo) throws Exception {
        var u = new UsuarioAutenticado(caja.vendedorId, "vendedor@ejemplo.co", "VENDEDOR", caja.empresa.empresaId);
        return mvc.perform(post(RUTA).contentType("application/json").content(cuerpo)
                .with(authentication(new UsernamePasswordAuthenticationToken(u, null,
                        List.of(new SimpleGrantedAuthority("ROLE_VENDEDOR"))))));
    }

    @Test
    void cincoPinErradosPorLaVentaDejanLosIntentosContados() throws Exception {
        for (int i = 0; i < 5; i++) {
            comoVendedor(ventaConDescuento("000000")).andExpect(status().isForbidden());
        }

        assertEquals(5, jdbc.sql("SELECT pin_intentos FROM usuarios WHERE empresa_id = :e AND usuario_id = :u")
                .param("e", caja.empresa.empresaId).param("u", caja.administradorId)
                .query(Integer.class).single(), "los intentos contra el PIN del administrador");
        assertEquals(true, jdbc.sql("SELECT pin_bloqueado_en IS NOT NULL FROM usuarios WHERE usuario_id = :u")
                .param("u", caja.administradorId).query(Boolean.class).single(), "al quinto queda bloqueado");
        assertEquals(true, jdbc.sql("""
                SELECT COUNT(*) >= 5 FROM auditoria_accesos WHERE correo = :correo AND exitoso = 0
                """).param("correo", "vendedor-" + caja.empresa.marca.toLowerCase() + "@ejemplo.co")
                .query(Boolean.class).single(), "queda la auditoría de cada rechazo");
        assertEquals(0, caja.cuantas("ventas"), "ninguna venta");
        assertEquals(0, caja.cuantas("pagos_venta"));
        assertEquals(0, caja.cuantas("consecutivos"));
        assertEquals(5, caja.existencia(producto));
        // Con el PIN bloqueado, ni el correcto sirve.
        comoVendedor(ventaConDescuento(CajaDePrueba.PIN)).andExpect(status().isForbidden());
        assertEquals(0, caja.cuantas("ventas"));
    }

    @Test
    void elPinCorrectoPorLaApiRegistraLaVentaYLimpiaLosIntentos() throws Exception {
        comoVendedor(ventaConDescuento("000000")).andExpect(status().isForbidden());
        assertEquals(1, jdbc.sql("SELECT pin_intentos FROM usuarios WHERE usuario_id = :u")
                .param("u", caja.administradorId).query(Integer.class).single());

        comoVendedor(ventaConDescuento(CajaDePrueba.PIN)).andExpect(status().isCreated());

        assertEquals(0, jdbc.sql("SELECT pin_intentos FROM usuarios WHERE usuario_id = :u")
                .param("u", caja.administradorId).query(Integer.class).single(), "un acierto limpia el contador");
        assertEquals(1, caja.cuantas("ventas"));
    }

    @Test
    void unPinAcertadoSeguidoDeUnaVentaSinTurnoEs409YNoDejaVentaNiExistenciaCambiada() throws Exception {
        // Sin turno el vendedor recibe 409 DESPUÉS de haber pasado el PIN: el PIN se valida antes de
        // abrir la transacción de la venta, y esa transacción independiente ya confirmó.
        jdbc.sql("UPDATE cierres_caja SET cierre = apertura WHERE empresa_id = :e").param("e", caja.empresa.empresaId).update();

        comoVendedor(ventaConDescuento(CajaDePrueba.PIN)).andExpect(status().isConflict());

        assertEquals(0, caja.cuantas("ventas"));
        assertEquals(5, caja.existencia(producto));
    }

    @Test
    void siSeLlamaDesdeUnaTransaccionEsUnErrorDeProgramacionYNoSeCuentaNingunIntento() {
        caja.entrarComoVendedor();
        var conPinErrado = new SolicitudVenta(UUID.randomUUID(), null,
                List.of(new LineaSolicitada(producto, null, 1,
                        new Descuento(TipoDescuento.PESOS, new BigDecimal("500")), null)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("9500"), null, null)), null,
                new SolicitudAutorizacion(caja.administradorId, "000000"), null);

        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transacciones).execute(estado -> ventas.procesar(conPinErrado)));

        assertEquals(0, jdbc.sql("SELECT pin_intentos FROM usuarios WHERE usuario_id = :u")
                .param("u", caja.administradorId).query(Integer.class).single(),
                "la guardia salta antes de tocar el PIN");
    }
}
