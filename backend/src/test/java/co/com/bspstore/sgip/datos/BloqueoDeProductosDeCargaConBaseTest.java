package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El SQL que bloquea los productos de una carga antes de tocar el histórico (N1):
 * solo los aceptados, y solo de la empresa de la carga. Se ejecuta de verdad porque
 * {@code FOR UPDATE OF p} sobre un JOIN no lo cubre ninguna prueba con el repositorio
 * simulado.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class BloqueoDeProductosDeCargaConBaseTest {

    @Autowired RepositorioImportacion importacion;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager gestor;

    private EmpresaDePrueba empresa;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Bloqueo de carga", plataforma, jdbc, categorias, proveedores);
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            empresa.borrar(jdbc);
        }
    }

    private Integer producto(String letra) {
        String codigo = letra + "-" + empresa.marca;
        Producto p = new Producto(empresa.empresaId, empresa.categoriaId, empresa.proveedorId,
                codigo, codigo, "Producto " + letra, new BigDecimal("100.00"));
        p.fijarExistenciaMinima(0);
        return repositorioProductos.save(p).getId();
    }

    @Test
    void bloqueaSoloLosProductosAceptadosDeLaCargaEnOrdenDeIdentificador() {
        Integer a = producto("A");
        Integer b = producto("B");
        Integer rechazado = producto("C");
        Long carga = importacion.insertarCarga(empresa.empresaId, empresa.proveedorId, empresa.usuarioId,
                "prueba.xlsx", 3, 2, 1);
        importacion.insertarDetalles(empresa.empresaId, carga, List.of(
                new FilaDeCarga(2, b, "B", "Producto B", new BigDecimal("150.00"),
                        new BigDecimal("100.00"), true, null, null),
                new FilaDeCarga(3, a, "A", "Producto A", new BigDecimal("150.00"),
                        new BigDecimal("100.00"), true, null, null),
                new FilaDeCarga(4, rechazado, "C", "Producto C", new BigDecimal("150.00"),
                        new BigDecimal("100.00"), false, "El costo no es positivo", "COSTO_NO_POSITIVO")));

        List<Integer> bloqueados = new TransactionTemplate(gestor).execute(
                estado -> importacion.bloquearProductosDeLaCarga(empresa.empresaId, carga));

        assertEquals(List.of(a, b), bloqueados);
    }
}
