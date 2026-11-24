package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CopiaDeEmpresa;
import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.logica.ReciboPdf;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioRecibos;
import co.com.bspstore.sgip.logica.ServicioVentas;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La copia de la empresa en la venta (V48): la venta guarda nombre, tipo de documento, NIT, dirección y teléfono tal
 * como están ese día, y el recibo que se reimprime después muestra esos datos, no los de hoy. Una venta anterior a
 * la V48 no tiene copia ({@code empresa_nit} nulo) y su recibo usa los datos actuales. La copia está o no está
 * entera: una copia sin dirección no toma la dirección de hoy.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CopiaDeEmpresaEnVentaConBaseTest {

    @Autowired ServicioVentas ventas;
    @Autowired ServicioRecibos recibos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private Integer producto;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Copia de empresa", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        producto = caja.producto("A", 50, "10000");
        caja.entrarComoAdministrador();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (caja != null) {
            caja.borrar();
        }
    }

    private void empresaAhora(String nombreComercial, String direccion, String telefono) {
        jdbc.sql("""
                UPDATE empresas SET nombre_comercial = :nombre, direccion = :direccion, telefono = :telefono
                 WHERE empresa_id = :e
                """)
                .param("nombre", nombreComercial).param("direccion", direccion).param("telefono", telefono)
                .param("e", caja.empresa.empresaId).update();
    }

    private ResultadoDeVenta vender() {
        return ventas.procesar(new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, 1, null, null)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("10000"), null, null)), null, null, null));
    }

    private record Copia(String nombre, String tipoDocumento, String nit, String direccion, String telefono) {}

    private Copia copiaGuardada(Long ventaId) {
        return jdbc.sql("""
                SELECT empresa_nombre AS nombre, empresa_tipo_documento AS tipo_documento, empresa_nit AS nit,
                       empresa_direccion AS direccion, empresa_telefono AS telefono
                  FROM ventas WHERE empresa_id = :e AND venta_id = :v
                """).param("e", caja.empresa.empresaId).param("v", ventaId).query(Copia.class).single();
    }

    private static String textoDelPdf(byte[] pdf) throws IOException {
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(documento);
        }
    }

    // ------------------------------------------------------- lo que la venta guarda

    @Test
    void laVentaGuardaLosDatosDeLaEmpresaDeEseDia() {
        empresaAhora("Tienda Aurora", "Calle 5 # 6-7", "3001234567");

        Long id = vender().venta().id();

        Copia c = copiaGuardada(id);
        assertEquals("Tienda Aurora", c.nombre());
        assertEquals("NIT", c.tipoDocumento());
        assertEquals("E" + caja.empresa.marca, c.nit());
        assertEquals("Calle 5 # 6-7", c.direccion());
        assertEquals("3001234567", c.telefono());
    }

    @Test
    void sinNombreComercialLaCopiaGuardaLaRazonSocial() {
        empresaAhora(null, null, null);

        Long id = vender().venta().id();

        Copia c = copiaGuardada(id);
        assertTrue(c.nombre().startsWith("Copia de empresa "), c.nombre());
        assertNull(c.direccion());
        assertNull(c.telefono());
    }

    @Test
    void laCopiaGuardaElNitTalComoEstaYElTipoDeDocumento() {
        jdbc.sql("UPDATE empresas SET nit = :nit, tipo_documento = 'CC' WHERE empresa_id = :e")
                .param("nit", "900" + caja.empresa.marca.substring(0, 6) + "-1").param("e", caja.empresa.empresaId)
                .update();

        Long id = vender().venta().id();

        Copia c = copiaGuardada(id);
        assertEquals("CC", c.tipoDocumento());
        assertEquals("900" + caja.empresa.marca.substring(0, 6) + "-1", c.nit(), "el NIT es una foto: no se normaliza");
    }

    // ------------------------------------------------------- el recibo reimpreso

    @Test
    void cambiarLaEmpresaDespuesNoCambiaElReciboViejo() {
        empresaAhora("Tienda Aurora", "Calle 5 # 6-7", "3001234567");
        Long vieja = vender().venta().id();

        empresaAhora("Ferretería Nueva", "Carrera 9 # 1-2", "3109998877");
        jdbc.sql("UPDATE empresas SET tipo_documento = 'CC' WHERE empresa_id = :e")
                .param("e", caja.empresa.empresaId).update();
        Long nueva = vender().venta().id();

        Recibo r = recibos.recibo(vieja);
        assertEquals("Tienda Aurora", r.empresa());
        assertEquals("NIT", r.tipoDocumentoEmpresa());
        assertEquals("E" + caja.empresa.marca, r.nitEmpresa());
        assertEquals("Calle 5 # 6-7", r.direccionEmpresa());
        assertEquals("3001234567", r.telefonoEmpresa());
        Recibo n = recibos.recibo(nueva);
        assertEquals("Ferretería Nueva", n.empresa());
        assertEquals("CC", n.tipoDocumentoEmpresa());
        assertEquals("Carrera 9 # 1-2", n.direccionEmpresa());
        assertEquals("3109998877", n.telefonoEmpresa());
    }

    @Test
    void ventaSinCopiaUsaLosDatosActuales() {
        empresaAhora("Tienda Aurora", "Calle 5 # 6-7", "3001234567");
        Long id = vender().venta().id();
        // una venta anterior a la V48: la copia está ausente si y solo si empresa_nit es nulo
        jdbc.sql("""
                UPDATE ventas SET empresa_nombre = NULL, empresa_tipo_documento = NULL, empresa_nit = NULL,
                                  empresa_direccion = NULL, empresa_telefono = NULL
                 WHERE empresa_id = :e AND venta_id = :v
                """).param("e", caja.empresa.empresaId).param("v", id).update();
        empresaAhora("Ferretería Nueva", "Carrera 9 # 1-2", "3109998877");

        Recibo r = recibos.recibo(id);

        assertEquals("Ferretería Nueva", r.empresa());
        assertEquals("NIT", r.tipoDocumentoEmpresa());
        assertEquals("E" + caja.empresa.marca, r.nitEmpresa());
        assertEquals("Carrera 9 # 1-2", r.direccionEmpresa());
        assertEquals("3109998877", r.telefonoEmpresa());
    }

    @Test
    void laCopiaSinDireccionNoTomaLaDireccionDeHoy() {
        empresaAhora("Tienda Aurora", null, null);
        Long id = vender().venta().id();
        empresaAhora("Tienda Aurora", "Carrera 9 # 1-2", "3109998877");

        Recibo r = recibos.recibo(id);

        assertEquals("Tienda Aurora", r.empresa());
        assertNull(r.direccionEmpresa(), "la copia está entera: lo que estaba vacío ese día sigue vacío");
        assertNull(r.telefonoEmpresa());
    }

    @Test
    void elReciboMuestraDireccionYTelefono() throws IOException {
        empresaAhora("Tienda Aurora", "Calle 5 # 6-7", "3001234567");
        Long id = vender().venta().id();

        Recibo r = recibos.recibo(id);
        String texto = textoDelPdf(new ReciboPdf().generar(r));

        assertEquals("Calle 5 # 6-7", r.direccionEmpresa());
        assertEquals("3001234567", r.telefonoEmpresa());
        assertTrue(texto.contains("Tienda Aurora"), texto);
        assertTrue(texto.contains("Calle 5 # 6-7"), texto);
        assertTrue(texto.contains("3001234567"), texto);
    }

    // ------------------------------------------------------- lo que se le responde al mostrador

    @Test
    void elResultadoDeLaVentaTraeLaCopia() {
        empresaAhora("Tienda Aurora", "Calle 5 # 6-7", "3001234567");

        ResultadoDeVenta resultado = vender();

        CopiaDeEmpresa c = resultado.venta().empresa();
        assertNotNull(c);
        assertEquals("Tienda Aurora", c.nombre());
        assertEquals("NIT", c.tipoDocumento());
        assertEquals("E" + caja.empresa.marca, c.nit());
        assertEquals("Calle 5 # 6-7", c.direccion());
        assertEquals("3001234567", c.telefono());
    }

    @Test
    void elReintentoDeLaVentaTraeLaCopiaDeLaVentaNoLaDeHoy() {
        empresaAhora("Tienda Aurora", "Calle 5 # 6-7", "3001234567");
        SolicitudVenta solicitud = new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, 1, null, null)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("10000"), null, null)), null, null, null);
        ResultadoDeVenta primera = ventas.procesar(solicitud);

        empresaAhora("Ferretería Nueva", "Carrera 9 # 1-2", "3109998877");
        ResultadoDeVenta reintento = ventas.procesar(solicitud);

        assertTrue(reintento.yaExistia());
        assertEquals(primera.venta().empresa(), reintento.venta().empresa());
        assertEquals("Tienda Aurora", reintento.venta().empresa().nombre());
    }

    @Test
    void laVentaSinCopiaResponde200ConLaEmpresaVacia() {
        empresaAhora("Tienda Aurora", "Calle 5 # 6-7", "3001234567");
        SolicitudVenta solicitud = new SolicitudVenta(UUID.randomUUID(), caja.clienteId,
                List.of(new LineaSolicitada(producto, null, 1, null, null)), null,
                List.of(new PagoSolicitado(caja.efectivoId, new BigDecimal("10000"), null, null)), null, null, null);
        VentaRegistrada primera = ventas.procesar(solicitud).venta();
        jdbc.sql("UPDATE ventas SET empresa_nit = NULL WHERE empresa_id = :e AND venta_id = :v")
                .param("e", caja.empresa.empresaId).param("v", primera.id()).update();

        VentaRegistrada reintento = ventas.procesar(solicitud).venta();

        assertNull(reintento.empresa(), "sin copia no se inventa una: el recibo usa los datos actuales");
        assertFalse(reintento.consecutivo().isBlank());
    }
}
