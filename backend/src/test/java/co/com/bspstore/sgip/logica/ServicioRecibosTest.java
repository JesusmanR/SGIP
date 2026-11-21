package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioRecibos;
import co.com.bspstore.sgip.datos.RepositorioRecibos.CabeceraDeRecibo;
import co.com.bspstore.sgip.datos.RepositorioRecibos.LineaDeVenta;
import co.com.bspstore.sgip.datos.RepositorioRecibos.PagoDeVenta;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El recibo de una venta (RF-70): qué trae, cuándo discrimina el IVA, qué marcas lleva y que la venta de otra
 * empresa es, para quien pregunta, una venta que no existe. El repositorio se simula: el SQL y el número de
 * consultas están en {@code ReciboConsultasConBaseTest}.
 */
@ExtendWith(MockitoExtension.class)
class ServicioRecibosTest {

    private static final Integer EMPRESA = 3;
    private static final Long VENTA = 9L;

    @Mock RepositorioRecibos repositorio;

    private ServicioRecibos servicio;

    @BeforeEach
    void comoVendedor() {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "VENDEDOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
        servicio = new ServicioRecibos(repositorio);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    private static CabeceraDeRecibo cabecera(boolean responsableIva, boolean domicilio, boolean contraEntrega,
                                             String estadoPago) {
        return new CabeceraDeRecibo("Black Steel Parts S.A.S.", "900123456-7", responsableIva, "V-00000012",
                LocalDateTime.of(2026, 11, 21, 15, 30, 5), "Ana Gómez", "Carlos Ruiz", "CC", "79123456",
                d("20000.00"), d("1000.00"), d("3610.00"), d("22610.00"), estadoPago, contraEntrega, domicilio,
                domicilio ? "Calle 1 # 2-3" : null, domicilio ? "Cali" : null, domicilio ? "3001112233" : null,
                domicilio ? "Ana Pérez" : null);
    }

    private static LineaDeVenta linea(String nombre, int cantidad, String precio, String descuento, String iva,
                                      String total, int encargo, LocalDate entrega) {
        return new LineaDeVenta(nombre, cantidad, d(precio), d(descuento), d(iva), d(total), encargo, entrega);
    }

    private static LineaDeVenta lineaSimple() {
        return linea("Celular económico", 2, "10000.00", "1000.00", "3610.00", "22610.00", 0, null);
    }

    private void venta(CabeceraDeRecibo cabecera, List<LineaDeVenta> lineas, List<PagoDeVenta> pagos) {
        when(repositorio.cabecera(EMPRESA, VENTA)).thenReturn(Optional.of(cabecera));
        when(repositorio.lineas(EMPRESA, VENTA)).thenReturn(lineas);
        when(repositorio.pagos(EMPRESA, VENTA)).thenReturn(pagos);
    }

    private static PagoDeVenta efectivo(String monto, String recibido) {
        return new PagoDeVenta("Efectivo", TipoMedio.EFECTIVO, d(monto), recibido == null ? null : d(recibido), null);
    }

    // ------------------------------------------------------- qué trae (RF-70)

    @Test
    void reciboTraeLoQuePideRf70() {
        venta(cabecera(true, false, false, "PAGADA"), List.of(lineaSimple()),
                List.of(new PagoDeVenta("Tarjeta", TipoMedio.TARJETA, d("12610.00"), null, "AUT-889"),
                        efectivo("10000.00", "15000.00")));

        Recibo r = servicio.recibo(VENTA);

        assertEquals("Black Steel Parts S.A.S.", r.empresa());
        assertEquals("900123456-7", r.nitEmpresa());
        assertEquals("V-00000012", r.consecutivo());
        assertEquals(OffsetDateTime.of(2026, 11, 21, 15, 30, 5, 0, ZoneOffset.ofHours(-5)), r.fecha());
        assertEquals("Ana Gómez", r.cajero());
        assertEquals("Carlos Ruiz", r.cliente());
        assertEquals("CC 79123456", r.identificacionCliente());
        assertEquals(1, r.lineas().size());
        Recibo.LineaDeRecibo l = r.lineas().get(0);
        assertEquals("Celular económico", l.nombre());
        assertEquals(2, l.cantidad());
        assertEquals(d("10000.00"), l.precioUnitario());
        assertEquals(d("1000.00"), l.descuento());
        assertEquals(d("3610.00"), l.iva());
        assertEquals(d("22610.00"), l.total());
        assertEquals(d("20000.00"), r.subtotal());
        assertEquals(d("1000.00"), r.descuento());
        assertEquals(d("3610.00"), r.iva());
        assertEquals(d("22610.00"), r.total());
        assertEquals(2, r.pagos().size());
        assertEquals("Tarjeta", r.pagos().get(0).medio());
        assertEquals("AUT-889", r.pagos().get(0).referencia());
        assertEquals(d("12610.00"), r.pagos().get(0).monto());
        assertEquals(d("15000.00"), r.pagos().get(1).recibido());
        // el cambio es lo recibido en efectivo menos lo cobrado en efectivo
        assertEquals(d("5000.00"), r.cambio());
        assertTrue(r.marcas().isEmpty());
    }

    @Test
    void sinEfectivoRecibidoElCambioEsCero() {
        venta(cabecera(true, false, false, "PAGADA"), List.of(lineaSimple()), List.of(efectivo("22610.00", null)));

        Recibo r = servicio.recibo(VENTA);

        assertEquals(0, r.cambio().compareTo(BigDecimal.ZERO));
    }

    @Test
    void unPagoConSaldoAFavorApareceComoSaldoAFavorEntreLosPagosYNoEntraEnElCambio() {
        venta(cabecera(true, false, false, "PAGADA"), List.of(lineaSimple()),
                List.of(new PagoDeVenta("Saldo a favor", TipoMedio.SALDO_A_FAVOR, d("10000.00"), null, null),
                        efectivo("12610.00", "13000.00")));

        Recibo r = servicio.recibo(VENTA);

        assertEquals(TipoMedio.SALDO_A_FAVOR, r.pagos().get(0).tipo());
        assertEquals(d("10000.00"), r.pagos().get(0).monto());
        assertEquals(d("390.00"), r.cambio());
    }

    // ------------------------------------------------------- el IVA (RF-34)

    @Test
    void empresaNoResponsableNoMuestraIva() {
        venta(cabecera(false, false, false, "PAGADA"),
                List.of(linea("Celular económico", 2, "10000.00", "1000.00", "0.00", "19000.00", 0, null)),
                List.of(efectivo("19000.00", null)));

        Recibo r = servicio.recibo(VENTA);

        assertNull(r.iva(), "una empresa que no es responsable de IVA no discrimina el impuesto");
        assertNull(r.lineas().get(0).iva());
    }

    @Test
    void empresaResponsableMuestraElIvaAunqueSeaCero() {
        venta(cabecera(true, false, false, "PAGADA"),
                List.of(linea("Libro", 1, "10000.00", "0.00", "0.00", "10000.00", 0, null)),
                List.of(efectivo("10000.00", null)));

        Recibo r = servicio.recibo(VENTA);

        assertEquals(d("3610.00"), r.iva());
        assertEquals(d("0.00"), r.lineas().get(0).iva());
    }

    // ------------------------------------------------------- las marcas

    @Test
    void marcasDeEncargoYDomicilio() {
        venta(cabecera(true, true, false, "PAGADA"),
                List.of(linea("Pastillas de freno", 3, "10000.00", "0.00", "0.00", "30000.00", 2,
                                LocalDate.of(2026, 12, 3)),
                        linea("Aceite", 1, "5000.00", "0.00", "0.00", "5000.00", 0, null),
                        linea("Filtro", 1, "5000.00", "0.00", "0.00", "5000.00", 1, LocalDate.of(2027, 1, 9))),
                List.of(efectivo("40000.00", null)));

        List<String> marcas = servicio.recibo(VENTA).marcas();

        assertEquals(List.of(
                "Encargo — entrega estimada: 03/12/2026",
                "Encargo — entrega estimada: 09/01/2027",
                "Domicilio",
                "Entrega: Calle 1 # 2-3, Cali · Tel. 3001112233 · Recibe: Ana Pérez"), marcas);
    }

    @Test
    void elContraEntregaPendienteSeMarcaPorCobrarYNoTienePagos() {
        venta(cabecera(true, true, true, "PENDIENTE"), List.of(lineaSimple()), List.of());

        Recibo r = servicio.recibo(VENTA);

        assertTrue(r.marcas().contains("Domicilio"));
        assertTrue(r.marcas().contains("Pago contra entrega — por cobrar"));
        assertTrue(r.pagos().isEmpty());
        assertEquals(0, r.cambio().compareTo(BigDecimal.ZERO));
    }

    @Test
    void elContraEntregaYaCobradoNoSeMarcaPorCobrar() {
        venta(cabecera(true, true, true, "PAGADA"), List.of(lineaSimple()), List.of(efectivo("22610.00", "23000.00")));

        Recibo r = servicio.recibo(VENTA);

        assertFalse(r.marcas().contains("Pago contra entrega — por cobrar"));
        assertEquals(1, r.pagos().size());
    }

    // ------------------------------------------------------- de quién es la venta

    @Test
    void reciboDeOtraEmpresaEs404() {
        // la venta existe en otra empresa: para esta, el repositorio no la encuentra
        when(repositorio.cabecera(EMPRESA, 55L)).thenReturn(Optional.empty());

        var e = assertThrows(RecursoNoEncontradoException.class, () -> servicio.recibo(55L));

        assertEquals("La venta no existe.", e.getMessage());
        verify(repositorio).cabecera(EMPRESA, 55L);
    }

    @Test
    void elPdfDeUnaVentaDeOtraEmpresaTambienEs404() {
        when(repositorio.cabecera(EMPRESA, 55L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class, () -> servicio.pdf(55L));
    }

    // ------------------------------------------------------- el costo nunca sale

    @Test
    void elReciboNoTraeCosto() {
        List<String> prohibidos = List.of("costo", "utilidad", "margen", "ganancia");
        List<String> encontrados = new ArrayList<>();
        for (Class<?> tipo : List.of(Recibo.class, Recibo.LineaDeRecibo.class, Recibo.PagoDeRecibo.class)) {
            for (RecordComponent campo : tipo.getRecordComponents()) {
                String nombre = campo.getName().toLowerCase();
                for (String p : prohibidos) {
                    if (nombre.contains(p)) {
                        encontrados.add(tipo.getSimpleName() + "." + campo.getName());
                    }
                }
            }
        }

        assertTrue(encontrados.isEmpty(), "el recibo trae campos de costo: " + encontrados);
    }

    @Test
    void elPdfEsUnPdf() {
        venta(cabecera(true, false, false, "PAGADA"), List.of(lineaSimple()), List.of(efectivo("22610.00", null)));

        byte[] pdf = servicio.pdf(VENTA);

        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }
}
