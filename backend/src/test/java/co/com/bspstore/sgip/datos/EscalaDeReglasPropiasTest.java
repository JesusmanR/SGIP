package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Categoria;
import co.com.bspstore.sgip.modelo.Proveedor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que el trabajo de `v_regla_margen` crezca con el catálogo y no con su cuadrado.
 *
 * El alta exige el precio de contado y lo copia al crédito (ADR-0025), de modo
 * que dos reglas por producto son el régimen normal. Si la vista empareja cada
 * regla con cada producto de la empresa, duplicar el catálogo cuadruplica el
 * trabajo, y una tienda con 10.000 productos espera medio minuto por una página.
 *
 * No mide tiempo, que depende de la máquina, ni `Handler_read%`, que no lo ve: un
 * hash join lee cada tabla una sola vez y lo cuadrático ocurre al emparejar. Lee
 * cuántas filas produjo de verdad cada paso del plan, con `EXPLAIN ANALYZE`. Y
 * lo pide sin `index_condition_pushdown`: con él, cada regla recorre igual todos
 * los productos, pero dentro de InnoDB, y el plan solo cuenta los que salen.
 * Quitarlo no cambia el trabajo, lo deja a la vista.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class EscalaDeReglasPropiasTest {

    /** Lo que deja el paso de `(rows=… loops=…)` tras «actual». */
    private static final Pattern FILAS_REALES =
            Pattern.compile("actual time=[^ ]+ rows=([0-9.e+]+) loops=([0-9.e+]+)");

    /** Lineal da 2; cuadrático, 4. El margen es para lo que no depende de N. */
    private static final double CRECIMIENTO_MAXIMO = 3.0;

    private static final int PRODUCTOS = 300;

    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired JdbcClient jdbc;

    private Integer empresaId;
    private Integer categoriaId;
    private Integer proveedorId;
    private int creados;

    @BeforeEach
    void prepararEmpresa() {
        String marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("E" + marca, "Prueba de escala " + marca,
                null, null, null);
        categoriaId = categorias.save(new Categoria(empresaId, "Prueba", null)).getId();
        proveedorId = proveedores.save(new Proveedor(empresaId, "Proveedor", "P" + marca)).getId();
        // Unas generales, como cualquier tienda: lo que no debe multiplicarse
        // son las propias.
        jdbc.sql("""
                INSERT INTO reglas_margen (empresa_id, categoria_id, forma_pago, forma_calculo,
                                           factor, vigente_desde)
                VALUES (:empresa, NULL, 'CONTADO', 'SOBRE_COSTO', 1.2000, '2026-01-01'),
                       (:empresa, NULL, 'CREDITO', 'SOBRE_COSTO', 1.2500, '2026-01-01'),
                       (:empresa, :categoria, 'CONTADO', 'SOBRE_COSTO', 1.3000, '2026-01-01')
                """)
                .param("empresa", empresaId)
                .param("categoria", categoriaId)
                .update();
    }

    @AfterEach
    void borrarLoQueCreo() {
        for (String tabla : List.of("reglas_margen", "productos", "proveedores", "categorias",
                "empresa_config", "empresas")) {
            jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                    .param("empresa", empresaId)
                    .update();
        }
    }

    private void anadirProductosConReglaPropia(int cuantos) {
        for (int i = 0; i < cuantos; i++) {
            creados++;
            jdbc.sql("""
                    INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku,
                                           nombre, costo)
                    VALUES (:empresa, :categoria, :proveedor, :codigo, :codigo, :codigo, 1000)
                    """)
                    .param("empresa", empresaId)
                    .param("categoria", categoriaId)
                    .param("proveedor", proveedorId)
                    .param("codigo", "ESC-" + creados)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO reglas_margen (empresa_id, producto_id, forma_pago, forma_calculo,
                                           factor, vigente_desde)
                SELECT p.empresa_id, p.producto_id, f.forma, 'SOBRE_COSTO', 1.2000, '2026-01-01'
                  FROM productos p
                  JOIN (SELECT 'CONTADO' AS forma UNION ALL SELECT 'CREDITO') f
                 WHERE p.empresa_id = :empresa
                   AND NOT EXISTS (SELECT 1 FROM reglas_margen r
                                    WHERE r.empresa_id = p.empresa_id
                                      AND r.producto_id = p.producto_id)
                """)
                .param("empresa", empresaId)
                .update();
        jdbc.sql("ANALYZE TABLE productos, reglas_margen").query().listOfRows();
    }

    /** Las filas del paso que más produjo. Filas por vueltas: un bucle anidado las reparte. */
    private double filasDelPasoMayor() {
        String plan = String.join("\n", jdbc.sql(
                        "EXPLAIN ANALYZE SELECT /*+ SET_VAR(optimizer_switch='index_condition_pushdown=off') */"
                                + " factor FROM v_regla_margen WHERE empresa_id = " + empresaId)
                .query(String.class).list());
        Matcher m = FILAS_REALES.matcher(plan);
        double mayor = -1;
        while (m.find()) {
            mayor = Math.max(mayor, Double.parseDouble(m.group(1)) * Double.parseDouble(m.group(2)));
        }
        // Sin esto, un cambio de formato de MySQL dejaría la prueba pasando sin mirar nada.
        assertTrue(mayor > 0, "El plan no dice cuántas filas produjo: " + plan);
        return mayor;
    }

    @Test
    void duplicarElCatalogoConSusReglasPropiasNoCuadruplicaElTrabajo() {
        anadirProductosConReglaPropia(PRODUCTOS);
        double conN = filasDelPasoMayor();

        anadirProductosConReglaPropia(PRODUCTOS);
        double conElDoble = filasDelPasoMayor();

        assertTrue(conElDoble <= conN * CRECIMIENTO_MAXIMO,
                "De " + PRODUCTOS + " a " + (2 * PRODUCTOS) + " productos el paso mayor pasó de "
                        + conN + " a " + conElDoble + " filas: crece con el cuadrado.");
    }
}
