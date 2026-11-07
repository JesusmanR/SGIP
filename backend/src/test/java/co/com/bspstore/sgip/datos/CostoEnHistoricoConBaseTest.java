package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.logica.ServicioProducto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Editar el costo de un producto deja rastro en {@code historico_costos}, igual
 * que la importación de una lista (M15): sin eso el histórico deja de decir el
 * costo real, y con él el aviso de umbral de la carga y cualquier informe de
 * margen de ventas.
 *
 * Una edición escribe el mismo formato que la importación: cierra ayer el
 * periodo abierto y abre otro desde hoy. Se distingue de ella porque no
 * pertenece a ninguna carga ({@code carga_id} nulo). «Hoy» es el de Bogotá,
 * leído y escrito siempre por JDBC como {@code LocalDate}.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CostoEnHistoricoConBaseTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Autowired ServicioProducto productos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;

    private EmpresaDePrueba empresa;
    private int consecutivo;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Costo en historico", plataforma, jdbc, categorias, proveedores);
        empresa.entrar();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            empresa.borrar(jdbc);
        }
    }

    private static LocalDate hoy() {
        return LocalDate.now(BOGOTA);
    }

    private Integer alta(String costo) {
        consecutivo++;
        String codigo = "H" + consecutivo + "-" + empresa.marca;
        return productos.crear(new SolicitudProducto(empresa.categoriaId, empresa.proveedorId,
                codigo, codigo, null, "Producto " + codigo, null, null, new BigDecimal(costo),
                0, 0, new SolicitudPrecio(new BigDecimal("20"), null), null)).getId();
    }

    private void editarCosto(Integer id, String costo) {
        ProductoEnCatalogo p = productos.buscar(id).orElseThrow();
        productos.actualizar(id, new SolicitudEdicionProducto(empresa.categoriaId, empresa.proveedorId,
                p.codigo(), p.sku(), null, p.nombre(), null, null, new BigDecimal(costo), 0,
                null, null, null, p.version()));
    }

    private record Periodo(BigDecimal costo, LocalDate desde, LocalDate hasta, Long cargaId) {}

    private List<Periodo> periodos(Integer id) {
        return jdbc.sql("""
                SELECT costo, vigente_desde AS desde, vigente_hasta AS hasta, carga_id
                  FROM historico_costos
                 WHERE empresa_id = :e AND producto_id = :p
                 ORDER BY vigente_desde
                """).param("e", empresa.empresaId).param("p", id).query(Periodo.class).list();
    }

    private void periodoAbierto(Integer id, String costo, LocalDate desde) {
        jdbc.sql("""
                INSERT INTO historico_costos (empresa_id, producto_id, costo, vigente_desde)
                VALUES (:e, :p, :costo, :desde)
                """)
                .param("e", empresa.empresaId).param("p", id)
                .param("costo", new BigDecimal(costo)).param("desde", desde)
                .update();
    }

    private static void igualA(String esperado, BigDecimal obtenido) {
        assertEquals(0, new BigDecimal(esperado).compareTo(obtenido),
                "esperaba " + esperado + " y dio " + obtenido);
    }

    @Test
    void cambiarElCostoAbreUnPeriodoDesdeHoyQueNoPerteneceAUnaCarga() {
        Integer id = alta("1000.00");

        editarCosto(id, "1200.00");

        List<Periodo> filas = periodos(id);
        assertEquals(1, filas.size());
        igualA("1200", filas.get(0).costo());
        assertEquals(hoy(), filas.get(0).desde());
        assertNull(filas.get(0).hasta(), "el periodo queda abierto");
        assertNull(filas.get(0).cargaId(), "una edición no viene de ninguna carga");
    }

    @Test
    void cambiarElCostoCierraAyerElPeriodoQueVenia() {
        Integer id = alta("1000.00");
        periodoAbierto(id, "1000.00", hoy().minusDays(30));

        editarCosto(id, "1200.00");

        List<Periodo> filas = periodos(id);
        assertEquals(2, filas.size());
        igualA("1000", filas.get(0).costo());
        assertEquals(hoy().minusDays(1), filas.get(0).hasta());
        igualA("1200", filas.get(1).costo());
        assertEquals(hoy(), filas.get(1).desde());
        assertNull(filas.get(1).hasta());
    }

    @Test
    void dosCambiosElMismoDiaDejanUnSoloPeriodoConElUltimoCosto() {
        Integer id = alta("1000.00");
        periodoAbierto(id, "1000.00", hoy().minusDays(30));

        editarCosto(id, "1200.00");
        editarCosto(id, "1300.00");

        List<Periodo> filas = periodos(id);
        assertEquals(2, filas.size(), "el de hoy sustituye al de hoy: una vigencia de cero días no explica nada");
        igualA("1000", filas.get(0).costo());
        assertEquals(hoy().minusDays(1), filas.get(0).hasta());
        igualA("1300", filas.get(1).costo());
        assertEquals(hoy(), filas.get(1).desde());
    }

    @Test
    void editarSinCambiarElCostoNoEscribeNada() {
        Integer id = alta("1000.00");
        periodoAbierto(id, "1000.00", hoy().minusDays(30));

        editarCosto(id, "1000");

        List<Periodo> filas = periodos(id);
        assertEquals(1, filas.size());
        assertNull(filas.get(0).hasta(), "el periodo sigue abierto, intacto");
        igualA("1000", filas.get(0).costo());
    }
}
