package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.ProductoParaCaja;
import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.ConsultaCatalogo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Lo que la caja ve de un producto (RF-31, RF-32): el precio sin IVA que calculó el servidor,
 * si se puede vender y por qué no, y nunca el costo. El servicio no sabe de HTTP: las
 * pruebas de la ruta están en {@code CajaProductosAutorizacionTest}, y las de la base
 * (productos inactivos, empresas ajenas, número de consultas) en {@code BusquedaCajaConsultasConBaseTest}.
 */
@ExtendWith(MockitoExtension.class)
class ServicioCajaProductosTest {

    private static final String SIN_PRECIO = "Este producto no tiene precio de venta";

    @Mock ConsultaCatalogo catalogo;

    private ServicioCajaProductos servicio;

    @BeforeEach
    void comoLaEmpresaTres() {
        servicio = new ServicioCajaProductos(catalogo);
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "VENDEDOR", 3);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    /** Un producto del catálogo, con costo, margen y proveedor: lo que la caja no debe copiar. */
    private static ProductoEnCatalogo producto(BigDecimal precioContado) {
        return new ProductoEnCatalogo(11, "ARZ-500", "ARZ-500-S", "Arroz Diana", "500 g", "Arroz blanco",
                "7701234567890", 4, "Granos", 9, "Distribuidora La Esperanza",
                new BigDecimal("2500.00"), 120, 20, "ACTIVO",
                precioContado, new BigDecimal("3400"), "GRAVADO", new BigDecimal("0.19"),
                precioContado == null ? null : precioContado.multiply(new BigDecimal("1.19")),
                new BigDecimal("4046"), "SOBRE_COSTO", "SOBRE_COSTO",
                0, 0, 0, 0, null, 0,
                new BigDecimal("24.00"), new BigDecimal("36.00"),
                null, null, new BigDecimal("24.00"), new BigDecimal("36.00"), 5, true);
    }

    // ------------------------------------------------------- el precio y si se puede vender

    @Test
    void elPrecioEsElDeContadoSinIvaYLaTarifaVaAparte() {
        when(catalogo.porCodigoBarras(3, "7701234567890")).thenReturn(Optional.of(producto(new BigDecimal("3100"))));

        ProductoParaCaja p = servicio.porCodigo("7701234567890").orElseThrow();

        assertEquals(11, p.id());
        assertEquals("ARZ-500", p.codigo());
        assertEquals("Arroz Diana", p.nombre());
        assertEquals("500 g", p.presentacion());
        assertEquals("7701234567890", p.codigoBarras());
        assertEquals(120, p.existencia());
        assertEquals(0, new BigDecimal("3100").compareTo(p.precio()),
                "el precio es el de contado SIN IVA: el IVA lo suma la venta con la tarifa");
        assertEquals(0, new BigDecimal("0.19").compareTo(p.tarifaIva()));
        assertEquals("GRAVADO", p.tratamientoIva());
        assertTrue(p.vendible());
        assertNull(p.motivoNoVendible());
    }

    @Test
    void elPrecioSeEntregaAlPesoConMediosHaciaArriba() {
        when(catalogo.porCodigoBarras(3, "1")).thenReturn(Optional.of(producto(new BigDecimal("3100.50"))));

        ProductoParaCaja p = servicio.porCodigo("1").orElseThrow();

        assertEquals(new BigDecimal("3101"), p.precio(), "pesos enteros, HALF_UP (RNF-16)");
    }

    @Test
    void sinPrecioNoEsVendible() {
        when(catalogo.porCodigoBarras(3, "1")).thenReturn(Optional.of(producto(null)));

        ProductoParaCaja p = servicio.porCodigo("1").orElseThrow();

        assertFalse(p.vendible());
        assertEquals(SIN_PRECIO, p.motivoNoVendible());
        assertNull(p.precio());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "1000000000000", "1000000000000.00", "99999999999999"})
    void unPrecioCeroNegativoOdeMasDeDoceCifrasEnterasNoEsVendible(String precio) {
        when(catalogo.porCodigoBarras(3, "1")).thenReturn(Optional.of(producto(new BigDecimal(precio))));

        ProductoParaCaja p = servicio.porCodigo("1").orElseThrow();

        assertFalse(p.vendible(), "no cabe en una venta: " + precio);
        assertEquals(SIN_PRECIO, p.motivoNoVendible());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "999999999999"})
    void elPrecioMinimoYElMaximoQueCabenSiSeVenden(String precio) {
        when(catalogo.porCodigoBarras(3, "1")).thenReturn(Optional.of(producto(new BigDecimal(precio))));

        ProductoParaCaja p = servicio.porCodigo("1").orElseThrow();

        assertTrue(p.vendible());
        assertNull(p.motivoNoVendible());
    }

    @Test
    void laExistenciaNegativaSePasaTalCualYNoImpideLaFicha() {
        // Vender sin existencia lo decide la venta (con autorización); la ficha solo informa.
        ProductoEnCatalogo sinExistencia = producto(new BigDecimal("3100"));
        ProductoEnCatalogo agotado = new ProductoEnCatalogo(sinExistencia.productoId(), sinExistencia.codigo(),
                sinExistencia.sku(), sinExistencia.nombre(), sinExistencia.presentacion(),
                sinExistencia.descripcion(), sinExistencia.codigoBarras(), sinExistencia.categoriaId(),
                sinExistencia.categoria(), sinExistencia.proveedorId(), sinExistencia.proveedor(),
                sinExistencia.costo(), -3, sinExistencia.existenciaMinima(), "AGOTADO",
                sinExistencia.precioContado(), sinExistencia.precioCredito(), sinExistencia.tratamientoIva(),
                sinExistencia.tarifaContado(), sinExistencia.totalContado(), sinExistencia.totalCredito(),
                sinExistencia.baseContado(), sinExistencia.baseCredito(), 0, 0, 0, 0, null, 0,
                sinExistencia.margenContado(), sinExistencia.margenCredito(), null, null,
                sinExistencia.porcentajeContado(), sinExistencia.porcentajeCredito(), 5, true);
        when(catalogo.porCodigoBarras(3, "1")).thenReturn(Optional.of(agotado));

        ProductoParaCaja p = servicio.porCodigo("1").orElseThrow();

        assertEquals(-3, p.existencia());
        assertTrue(p.vendible());
    }

    // ------------------------------------------------------- lo que la caja no ve

    @Test
    void laFichaDeLaCajaNoTieneCampoDeCostoMargenNiUtilidad() {
        List<String> nombres = Arrays.stream(ProductoParaCaja.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertEquals(List.of("id", "codigo", "nombre", "presentacion", "codigoBarras", "existencia",
                "precio", "tarifaIva", "tratamientoIva", "vendible", "motivoNoVendible"), nombres);
        for (String nombre : nombres) {
            String minuscula = nombre.toLowerCase();
            assertFalse(minuscula.contains("costo") || minuscula.contains("margen")
                    || minuscula.contains("utilidad") || minuscula.contains("proveedor"), nombre);
        }
    }

    @Test
    void elCostoDelProductoNoAparecePorNingunLado() {
        when(catalogo.porCodigoBarras(3, "7701234567890")).thenReturn(Optional.of(producto(new BigDecimal("3100"))));

        String ficha = servicio.porCodigo("7701234567890").orElseThrow().toString();

        assertFalse(ficha.contains("2500"), "el costo 2500 no puede estar en la ficha: " + ficha);
        assertFalse(ficha.contains("24.00"), "el margen no puede estar en la ficha: " + ficha);
    }

    // ------------------------------------------------------- por código

    @Test
    void porCodigoBuscaPrimeroPorCodigoDeBarrasYSiEncuentraNoPreguntaPorElCodigo() {
        when(catalogo.porCodigoBarras(3, "7701234567890")).thenReturn(Optional.of(producto(new BigDecimal("3100"))));

        assertTrue(servicio.porCodigo("7701234567890").isPresent());

        verify(catalogo, never()).porCodigo(any(), any());
    }

    @Test
    void siNoEsUnCodigoDeBarrasLoBuscaPorCodigoInterno() {
        when(catalogo.porCodigoBarras(3, "ARZ-500")).thenReturn(Optional.empty());
        when(catalogo.porCodigo(3, "ARZ-500")).thenReturn(Optional.of(producto(new BigDecimal("3100"))));

        ProductoParaCaja p = servicio.porCodigo("ARZ-500").orElseThrow();

        assertEquals(11, p.id());
    }

    @Test
    void sinNingunaCoincidenciaNoHayProducto() {
        when(catalogo.porCodigoBarras(3, "NO-EXISTE")).thenReturn(Optional.empty());
        when(catalogo.porCodigo(3, "NO-EXISTE")).thenReturn(Optional.empty());

        assertTrue(servicio.porCodigo("NO-EXISTE").isEmpty());
    }

    @Test
    void elCodigoSeBuscaSinEspaciosAlBorde() {
        when(catalogo.porCodigoBarras(3, "7701234567890")).thenReturn(Optional.of(producto(new BigDecimal("3100"))));

        assertTrue(servicio.porCodigo("  7701234567890\n").isPresent());
    }

    @Test
    void porCodigoSiempreVaAcotadoALaEmpresaDeLaSesion() {
        when(catalogo.porCodigoBarras(3, "X")).thenReturn(Optional.empty());
        when(catalogo.porCodigo(3, "X")).thenReturn(Optional.empty());

        servicio.porCodigo("X");

        verify(catalogo).porCodigoBarras(3, "X");
        verify(catalogo).porCodigo(3, "X");
        verifyNoMoreInteractions(catalogo);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void unCodigoVacioEsUnFiltroInvalidoYNoConsulta(String codigo) {
        FiltroInvalidoException e = assertThrows(FiltroInvalidoException.class, () -> servicio.porCodigo(codigo));

        assertEquals("El código del producto no puede estar vacío.", e.getMessage());
        verifyNoInteractions(catalogo);
    }

    @Test
    void unCodigoMasLargoQueLaColumnaEsUnFiltroInvalidoSinRepetirlo() {
        String largo = "9".repeat(61);

        FiltroInvalidoException e = assertThrows(FiltroInvalidoException.class, () -> servicio.porCodigo(largo));

        assertEquals("El código del producto es demasiado largo.", e.getMessage());
        verifyNoInteractions(catalogo);
    }

    @Test
    void sinEmpresaEnLaSesionNoSeConsultaNada() {
        var plataforma = new UsuarioAutenticado(1, "root@sgip.co", "SUPER_ADMIN", null);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(plataforma, null, List.of()));
        SecurityContextHolder.setContext(c);

        assertThrows(SinEmpresaException.class, () -> servicio.porCodigo("X"));
        assertThrows(SinEmpresaException.class, () -> servicio.buscar("arroz", 20));
        verifyNoInteractions(catalogo);
    }

    // ------------------------------------------------------- por texto

    @Test
    void buscarPorTextoUsaElListadoDelCatalogoDeLaEmpresaConElLimite() {
        when(catalogo.listar(eq(3), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(producto(new BigDecimal("3100")), producto(null)));

        List<ProductoParaCaja> encontrados = servicio.buscar("arroz", 10);

        verify(catalogo).listar(3, "arroz", null, null, null, 10);
        assertEquals(2, encontrados.size());
        assertTrue(encontrados.get(0).vendible());
        assertFalse(encontrados.get(1).vendible());
    }

    @Test
    void elLimiteNuncaPasaDeVeinteYPorOmisionEsVeinte() {
        when(catalogo.listar(eq(3), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        servicio.buscar("arroz", 5000);
        servicio.buscar("arroz", 0);
        servicio.buscar("arroz", -4);
        servicio.buscar("arroz", 20);
        servicio.buscar("arroz", 7);

        verify(catalogo, org.mockito.Mockito.times(4)).listar(3, "arroz", null, null, null, 20);
        verify(catalogo).listar(3, "arroz", null, null, null, 7);
    }

    @Test
    void elTextoSeBuscaSinEspaciosAlBorde() {
        when(catalogo.listar(eq(3), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        servicio.buscar("  arroz diana ", 20);

        verify(catalogo).listar(3, "arroz diana", null, null, null, 20);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n"})
    void unTextoEnBlancoEsUnFiltroInvalidoYNoConsulta(String texto) {
        FiltroInvalidoException e = assertThrows(FiltroInvalidoException.class, () -> servicio.buscar(texto, 20));

        assertEquals("El texto a buscar no puede estar vacío.", e.getMessage());
        verifyNoInteractions(catalogo);
    }

    @Test
    void unTextoDeMasDeCienCaracteresEsUnFiltroInvalidoSinRepetirlo() {
        FiltroInvalidoException e = assertThrows(FiltroInvalidoException.class,
                () -> servicio.buscar("a".repeat(101), 20));

        assertEquals("El texto a buscar admite hasta 100 caracteres.", e.getMessage());
        verifyNoInteractions(catalogo);
    }

    @Test
    void unTextoDeCienCaracteresSiSeBusca() {
        when(catalogo.listar(eq(3), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        servicio.buscar("a".repeat(100), 20);

        verify(catalogo).listar(3, "a".repeat(100), null, null, null, 20);
    }
}
