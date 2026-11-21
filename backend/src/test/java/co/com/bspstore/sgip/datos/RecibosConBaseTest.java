package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Domicilio;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioRecibos;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El recibo de una venta ya registrada (RF-70) contra el esquema real: lo que de verdad quedó guardado en la
 * venta, sus líneas y sus pagos, la empresa que sale de {@code empresas}, el IVA según
 * {@code empresa_config} y que la venta de otra empresa no existe para quien pregunta.
 *
 * El conteo de consultas está aparte, en {@code ReciboConsultasConBaseTest}: allí el espía de
 * {@code JdbcClient} cambia el contexto de Spring.
 *
 * Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class RecibosConBaseTest {

    private static final Domicilio ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", false);
    private static final Domicilio CONTRA_ENTREGA = new Domicilio("Calle 1 # 2-3", "Cali", "3001112233", "Ana Pérez", true);

    @Autowired ServicioVentas ventas;
    @Autowired ServicioRecibos recibos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private CajaDePrueba ajena;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Recibos", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        caja.entrarComoAdministrador();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (ajena != null) {
            ajena.borrar();
        }
        if (caja != null) {
            caja.borrar();
        }
    }

    private PagoSolicitado efectivo(String monto, String recibido) {
        return new PagoSolicitado(caja.efectivoId, new BigDecimal(monto),
                recibido == null ? null : new BigDecimal(recibido), null);
    }

    private VentaRegistrada vender(List<LineaSolicitada> lineas, List<PagoSolicitado> pagos) {
        return ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId, lineas, null, pagos, null, null,
                null));
    }

    private static LineaSolicitada linea(Integer producto, int cantidad) {
        return new LineaSolicitada(producto, null, cantidad, null, null);
    }

    private static void igual(String esperado, BigDecimal obtenido) {
        assertNotNull(obtenido);
        assertEquals(0, new BigDecimal(esperado).compareTo(obtenido), "esperaba " + esperado + " y fue " + obtenido);
    }

    // ------------------------------------------------------- lo que pide RF-70

    @Test
    void elReciboTraeLoGuardadoDeLaVentaConIvaDeLaEmpresaResponsable() {
        caja.responsableDeIva19();
        Integer a = caja.producto("A", 10, "10000");
        Integer b = caja.producto("B", 10, "5000");
        VentaRegistrada v = vender(List.of(linea(a, 2), linea(b, 1)), List.of(efectivo("29750", "30000")));
        caja.entrarComoVendedor();

        Recibo r = recibos.recibo(v.id());

        assertTrue(r.empresa().startsWith("Recibos "), r.empresa());
        assertEquals("E" + caja.empresa.marca, r.nitEmpresa());
        assertEquals(v.consecutivo(), r.consecutivo());
        assertEquals("Prueba", r.cajero());
        assertEquals("Cliente " + caja.empresa.marca.toLowerCase(), r.cliente());
        assertEquals("CC 9" + caja.empresa.marca.toLowerCase(), r.identificacionCliente());
        assertNotNull(r.fecha());
        assertEquals(2, r.lineas().size());
        assertEquals("Producto A", r.lineas().get(0).nombre());
        assertEquals(2, r.lineas().get(0).cantidad());
        igual("10000", r.lineas().get(0).precioUnitario());
        igual("23800", r.lineas().get(0).total());
        igual("3800", r.lineas().get(0).iva());
        igual("25000", r.subtotal());
        igual("0", r.descuento());
        igual("4750", r.iva());
        igual("29750", r.total());
        assertEquals(1, r.pagos().size());
        assertEquals(TipoMedio.EFECTIVO, r.pagos().get(0).tipo());
        igual("29750", r.pagos().get(0).monto());
        igual("30000", r.pagos().get(0).recibido());
        igual("250", r.cambio());
        assertTrue(r.marcas().isEmpty());
    }

    @Test
    void laEmpresaSinConfiguracionNoEsResponsableYElReciboNoDiscriminaIva() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(List.of(linea(a, 1)), List.of(efectivo("10000", null)));

        Recibo r = recibos.recibo(v.id());

        assertNull(r.iva());
        assertNull(r.lineas().get(0).iva());
        igual("10000", r.total());
    }

    @Test
    void elPagoConSaldoAFavorApareceComoSaldoAFavor() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(List.of(linea(a, 1)),
                List.of(efectivo("4000", "5000"),
                        new PagoSolicitado(caja.tarjetaId, new BigDecimal("6000"), null, "AUT-1")));
        // el saldo a favor lo paga el sistema; aquí se reconvierte el pago de tarjeta para no armar toda la devolución
        jdbc.sql("""
                UPDATE pagos_venta SET medio_id = :saldo, referencia = NULL, cierre_id = NULL
                 WHERE empresa_id = :e AND venta_id = :v AND medio_id = :tarjeta
                """).param("saldo", caja.saldoAFavorId).param("e", caja.empresa.empresaId).param("v", v.id())
                .param("tarjeta", caja.tarjetaId).update();

        Recibo r = recibos.recibo(v.id());

        assertEquals(2, r.pagos().size());
        Recibo.PagoDeRecibo saldo = r.pagos().stream().filter(p -> p.tipo() == TipoMedio.SALDO_A_FAVOR)
                .findFirst().orElseThrow();
        assertEquals("Saldo a favor", saldo.medio());
        igual("6000", saldo.monto());
        igual("1000", r.cambio());
    }

    // ------------------------------------------------------- las marcas

    @Test
    void elEncargoYElDomicilioPagadoLlevanSusMarcas() {
        Integer enExistencia = caja.producto("A", 10, "10000");
        Integer sinExistencia = caja.producto("B", 0, "5000");
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(linea(enExistencia, 1),
                        new LineaSolicitada(sinExistencia, null, 1, null, LocalDate.now().plusDays(7))), null,
                List.of(efectivo("15000", null)), "No había existencia", null, ENTREGA));

        Recibo r = recibos.recibo(v.id());

        String entrega = LocalDate.now().plusDays(7).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        assertEquals(List.of("Encargo — entrega estimada: " + entrega, "Domicilio",
                "Entrega: Calle 1 # 2-3, Cali · Tel. 3001112233 · Recibe: Ana Pérez"), r.marcas());
    }

    @Test
    void elContraEntregaSinCobrarSeMarcaPorCobrarYSinPagos() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = ventas.registrar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(linea(a, 1)), null, List.of(), null, null, CONTRA_ENTREGA));

        Recibo r = recibos.recibo(v.id());

        assertTrue(r.marcas().contains("Domicilio"), r.marcas().toString());
        assertTrue(r.marcas().contains("Pago contra entrega — por cobrar"), r.marcas().toString());
        assertTrue(r.pagos().isEmpty());
        igual("0", r.cambio());
        igual("10000", r.total());
    }

    @Test
    void unaVentaDelMostradorNoLlevaMarcas() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(List.of(linea(a, 1)), List.of(efectivo("10000", null)));

        assertTrue(recibos.recibo(v.id()).marcas().isEmpty());
    }

    // ------------------------------------------------------- el PDF y la venta ajena

    @Test
    void elPdfDeLaVentaSale() throws IOException {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(List.of(linea(a, 1)), List.of(efectivo("10000", null)));

        byte[] pdf = recibos.pdf(v.id());

        try (PDDocument documento = Loader.loadPDF(pdf)) {
            assertEquals(1, documento.getNumberOfPages());
            String texto = new PDFTextStripper().getText(documento);
            assertTrue(texto.contains(v.consecutivo()), texto);
            assertTrue(texto.contains("Producto A"), texto);
        }
    }

    @Test
    void reciboDeOtraEmpresaEs404() {
        Integer a = caja.producto("A", 10, "10000");
        VentaRegistrada v = vender(List.of(linea(a, 1)), List.of(efectivo("10000", null)));
        ajena = CajaDePrueba.crear("Recibos ajena", jdbc, plataforma, categorias, proveedores, clientes);
        ajena.entrarComoAdministrador();

        assertThrows(RecursoNoEncontradoException.class, () -> recibos.recibo(v.id()));
        assertThrows(RecursoNoEncontradoException.class, () -> recibos.pdf(v.id()));
        assertThrows(RecursoNoEncontradoException.class, () -> recibos.recibo(2_000_000_000L));
    }
}
