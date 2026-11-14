package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.ProductoParaCaja;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.logica.ServicioCajaProductos;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La búsqueda de la caja contra el esquema real: lo que la vista de precios deja pasar
 * (solo productos activos), que cada consulta se acote a la empresa de la sesión, el
 * precio sin IVA que sale de la regla y cuántas consultas cuesta (RNF-03).
 *
 * Presupuesto: leer un producto por código cuesta una consulta si es un código de barras y
 * dos si es un código interno o no existe, y buscar por texto cuesta una, sin importar
 * cuántos productos haya ni cuántos coincidan. Hibernate Statistics no sirve aquí porque el
 * catálogo usa {@code JdbcClient}; se cuenta en su propia puerta, con un espía sobre el
 * bean, como en {@code ListadoDeOrdenesConsultasConBaseTest}.
 *
 * Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class BusquedaCajaConsultasConBaseTest {

    @Autowired ServicioCajaProductos caja;
    @Autowired ServicioProducto productos;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @MockitoSpyBean JdbcClient jdbc;

    private final List<EmpresaDePrueba> empresasCreadas = new ArrayList<>();
    private EmpresaDePrueba empresa;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = nuevaEmpresa("Caja");
        empresa.entrar();
    }

    @AfterEach
    void borrarLoQueCreo() {
        for (EmpresaDePrueba creada : empresasCreadas) {
            creada.borrar(jdbc);
        }
    }

    private EmpresaDePrueba nuevaEmpresa(String nombre) {
        EmpresaDePrueba creada = EmpresaDePrueba.crear(nombre, plataforma, jdbc, categorias, proveedores);
        empresasCreadas.add(creada);
        return creada;
    }

    /** Un producto de costo 1000 con el 20 % de margen: precio de contado 1200, sin IVA. */
    private Producto crear(EmpresaDePrueba de, String codigo, String barras, String nombre) {
        de.entrar();
        Producto creado = productos.crear(new SolicitudProducto(de.categoriaId, de.proveedorId, codigo, null,
                barras, nombre, null, null, new BigDecimal("1000.00"), 8, 5,
                new SolicitudPrecio(new BigDecimal("20"), null), null));
        empresa.entrar();
        return creado;
    }

    private void desactivar(EmpresaDePrueba de, Integer productoId) {
        jdbc.sql("UPDATE productos SET activo = 0 WHERE empresa_id = :e AND producto_id = :id")
                .param("e", de.empresaId).param("id", productoId).update();
    }

    private String codigo(String letra) {
        return letra + "-" + empresa.marca;
    }

    private long sentencias() {
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(llamada -> llamada.getMethod().getName().equals("sql"))
                .count();
    }

    // ------------------------------------------------------- lo que ve la caja

    @Test
    void laFichaTraeElPrecioSinIvaQueCalculaLaReglaYLaExistencia() {
        Producto p = crear(empresa, codigo("A"), "7701111111111", "Arroz");

        ProductoParaCaja ficha = caja.porCodigo("7701111111111").orElseThrow();

        assertEquals(p.getId(), ficha.id());
        assertEquals(codigo("A"), ficha.codigo());
        assertEquals("Arroz", ficha.nombre());
        assertEquals(8, ficha.existencia());
        assertEquals(0, new BigDecimal("1200").compareTo(ficha.precio()),
                "costo 1000 con 20 % de margen: el precio de contado es 1200, sin IVA");
        assertTrue(ficha.vendible());
        assertNull(ficha.motivoNoVendible());
    }

    @Test
    void unProductoSinReglaDePrecioNoEsVendible() {
        // Por el repositorio y no por el servicio: el alta por la API exige un precio, pero un
        // producto puede quedar sin regla (por ejemplo, si la regla de su categoría se retira).
        Producto sinPrecio = new Producto(empresa.empresaId, empresa.categoriaId, empresa.proveedorId,
                codigo("S"), codigo("S"), "Sin precio", new BigDecimal("1000.00"));
        sinPrecio.describir(null, null, "7702222222222");
        repositorioProductos.save(sinPrecio);

        ProductoParaCaja ficha = caja.porCodigo("7702222222222").orElseThrow();

        assertFalse(ficha.vendible());
        assertEquals("Este producto no tiene precio de venta", ficha.motivoNoVendible());
        assertNull(ficha.precio());
    }

    @Test
    void unProductoInactivoNoAparece() {
        Producto activo = crear(empresa, codigo("A"), "7701111111111", "Arroz activo");
        Producto inactivo = crear(empresa, codigo("I"), "7703333333333", "Arroz inactivo");
        desactivar(empresa, inactivo.getId());

        assertTrue(caja.porCodigo("7703333333333").isEmpty(), "por código de barras");
        assertTrue(caja.porCodigo(codigo("I")).isEmpty(), "por código interno");
        List<Integer> porTexto = caja.buscar("Arroz", 20).stream().map(ProductoParaCaja::id).toList();
        assertEquals(List.of(activo.getId()), porTexto, "por texto");
    }

    @Test
    void porCodigoDeOtraEmpresaEs404() {
        EmpresaDePrueba otra = nuevaEmpresa("Caja ajena");
        crear(otra, "AJENO-" + otra.marca, "7704444444444", "Producto ajeno");
        crear(empresa, codigo("A"), "7701111111111", "Producto propio");

        assertTrue(caja.porCodigo("7704444444444").isEmpty(), "su código de barras no es de mi empresa");
        assertTrue(caja.porCodigo("AJENO-" + otra.marca).isEmpty(), "su código interno no es de mi empresa");
        assertTrue(caja.buscar("ajeno", 20).isEmpty(), "su nombre no aparece en mi búsqueda");
        assertEquals(1, caja.buscar("Producto", 20).size(), "solo veo el mío");
    }

    @Test
    void unMismoCodigoDeBarrasEnDosEmpresasDevuelveElDeCadaUna() {
        EmpresaDePrueba otra = nuevaEmpresa("Caja gemela");
        Producto mio = crear(empresa, codigo("A"), "7705555555555", "Mío");
        Producto suyo = crear(otra, "GEMELO-" + otra.marca, "7705555555555", "Suyo");

        assertEquals(mio.getId(), caja.porCodigo("7705555555555").orElseThrow().id());
        otra.entrar();
        assertEquals(suyo.getId(), caja.porCodigo("7705555555555").orElseThrow().id());
    }

    @Test
    void elCodigoInternoSeEncuentraAunqueNoSeaUnCodigoDeBarras() {
        Producto p = crear(empresa, codigo("A"), null, "Sin etiqueta");

        assertEquals(p.getId(), caja.porCodigo(codigo("A")).orElseThrow().id());
    }

    @Test
    void buscarPorTextoNuncaDevuelveMasDeVeinteYVaOrdenadoPorNombre() {
        for (int i = 0; i < 25; i++) {
            crear(empresa, "L%02d-%s".formatted(i, empresa.marca), null, "Lata %02d".formatted(i));
        }

        List<ProductoParaCaja> encontrados = caja.buscar("lata", 500);

        assertEquals(20, encontrados.size());
        assertEquals("Lata 00", encontrados.get(0).nombre());
        assertEquals("Lata 19", encontrados.get(19).nombre());
    }

    // ------------------------------------------------------- las consultas

    @Test
    void buscarPorCodigoHaceUnNumeroFijoDeConsultas() {
        crear(empresa, codigo("A"), "7701111111111", "Arroz");
        crear(empresa, codigo("B"), null, "Frijol");

        long conPocos = medir();
        for (int i = 0; i < 30; i++) {
            crear(empresa, "M%02d-%s".formatted(i, empresa.marca), "77060000000%02d".formatted(i), "Mas " + i);
        }
        long conMuchos = medir();

        System.out.println("CONSULTAS por código (barras / interno / inexistente) con 2 productos: " + conPocos
                + " ; con 32: " + conMuchos);
        assertEquals(conPocos, conMuchos, "el número de consultas no puede depender de cuántos productos haya");
    }

    /** Lanza las tres lecturas posibles por código y devuelve cuántas consultas cuestan en total. */
    private long medir() {
        Mockito.clearInvocations(jdbc);
        Optional<ProductoParaCaja> porBarras = caja.porCodigo("7701111111111");
        long deBarras = sentencias();
        Mockito.clearInvocations(jdbc);
        Optional<ProductoParaCaja> porInterno = caja.porCodigo(codigo("B"));
        long deInterno = sentencias();
        Mockito.clearInvocations(jdbc);
        Optional<ProductoParaCaja> inexistente = caja.porCodigo("NO-EXISTE");
        long deInexistente = sentencias();

        assertTrue(porBarras.isPresent());
        assertTrue(porInterno.isPresent());
        assertTrue(inexistente.isEmpty());
        assertEquals(1, deBarras, "un código de barras se encuentra con una consulta");
        assertTrue(deInterno <= 2, "un código interno cuesta como mucho dos consultas y fueron " + deInterno);
        assertTrue(deInexistente <= 2, "un código inexistente cuesta como mucho dos consultas y fueron " + deInexistente);
        return deBarras * 100 + deInterno * 10 + deInexistente;
    }

    @Test
    void buscarPorTextoHaceUnaConsultaCoincidan10O30() {
        for (int i = 0; i < 10; i++) {
            crear(empresa, "T%02d-%s".formatted(i, empresa.marca), null, "Tarro %02d".formatted(i));
        }
        Mockito.clearInvocations(jdbc);
        int pocos = caja.buscar("tarro", 20).size();
        long consultasConPocos = sentencias();

        for (int i = 10; i < 30; i++) {
            crear(empresa, "T%02d-%s".formatted(i, empresa.marca), null, "Tarro %02d".formatted(i));
        }
        Mockito.clearInvocations(jdbc);
        int muchos = caja.buscar("tarro", 20).size();
        long consultasConMuchos = sentencias();

        System.out.println("CONSULTAS por texto: con 10 coincidencias " + consultasConPocos
                + " ; con 30 coincidencias (devuelve " + muchos + ") " + consultasConMuchos);
        assertEquals(10, pocos);
        assertEquals(20, muchos);
        assertEquals(1, consultasConPocos);
        assertEquals(1, consultasConMuchos);
    }
}
