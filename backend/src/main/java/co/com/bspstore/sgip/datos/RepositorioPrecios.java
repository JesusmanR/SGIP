package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.ReglaDeMargen;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Las reglas de ganancia de la empresa (RF-30).
 *
 * El cálculo del precio no está aquí ni en ninguna clase de Java: vive
 * en `v_precios_producto`, que aplica la fórmula que cada regla declara.
 * Este repositorio solo administra las reglas, y esa separación es la
 * que hace cierto el criterio del requisito —«la modificación surte
 * efecto en el cálculo siguiente sin desplegar»—: cambiar una fila
 * cambia el precio porque la vista recalcula en cada lectura.
 */
@Repository
public class RepositorioPrecios {

    private final JdbcClient jdbc;

    public RepositorioPrecios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * La especificidad es cuántos de los dos ámbitos acota la regla, que
     * es exactamente el criterio por el que `v_regla_margen` las ordena
     * para quedarse con una (RN-03). Se calcula aquí con la misma
     * expresión, de modo que la pantalla ordena como el motor elige.
     *
     * `vigente` mira la fecha de hoy contra el rango, igual que la vista.
     */
    private static final String SELECCION = """
            SELECT r.regla_id, r.categoria_id, c.nombre AS categoria,
                   r.proveedor_id, p.razon_social AS proveedor,
                   r.forma_pago, r.forma_calculo, r.factor,
                   r.vigente_desde, r.vigente_hasta,
                   (r.categoria_id IS NOT NULL) + (r.proveedor_id IS NOT NULL)
                       AS especificidad,
                   (CURDATE() >= r.vigente_desde
                    AND (r.vigente_hasta IS NULL OR CURDATE() <= r.vigente_hasta))
                       AS vigente
              FROM reglas_margen r
              LEFT JOIN categorias  c ON c.categoria_id  = r.categoria_id
                                     AND c.empresa_id    = r.empresa_id
              LEFT JOIN proveedores p ON p.proveedor_id  = r.proveedor_id
                                     AND p.empresa_id    = r.empresa_id
             WHERE r.empresa_id = :empresa
            """;

    public List<ReglaDeMargen> listar(Integer empresaId) {
        return jdbc.sql(SELECCION + """
                         ORDER BY r.forma_pago,
                                  especificidad DESC,
                                  r.vigente_desde DESC,
                                  r.regla_id DESC
                        """)
                .param("empresa", empresaId)
                .query(ReglaDeMargen.class)
                .list();
    }

    public Optional<ReglaDeMargen> buscar(Integer empresaId, Integer reglaId) {
        return jdbc.sql(SELECCION + " AND r.regla_id = :regla")
                .param("empresa", empresaId)
                .param("regla", reglaId)
                .query(ReglaDeMargen.class)
                .optional();
    }

    /**
     * Si ya hay una regla del mismo ámbito y forma de pago cuya vigencia
     * se solape con la que se pretende.
     *
     * Dos reglas idénticas no rompen nada —`v_regla_margen` desempata
     * por identificador y se queda con la última—, pero el resultado es
     * invisible: alguien corrige el factor de la primera y el precio no
     * cambia, porque manda la otra. Por eso se rechaza al crearla.
     *
     * No lo puede hacer una clave única del esquema. El ámbito se
     * expresa con nulos —categoría nula significa «todas»— y los dos
     * motores consideran distintos dos nulos en un índice único, de modo
     * que la clave dejaría pasar justamente el caso más común: dos
     * reglas generales. Es el mismo obstáculo que la V22 encontró con
     * los parámetros de plataforma.
     *
     * El solape se calcula con el convenio habitual: dos intervalos se
     * solapan si cada uno empieza antes de que acabe el otro, con la
     * vigencia abierta tratada como un final lejanísimo.
     */
    public boolean haySolape(Integer empresaId, Integer categoriaId, Integer proveedorId,
                             String formaPago, LocalDate desde, LocalDate hasta,
                             Integer excluirRegla) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM reglas_margen
                         WHERE empresa_id = :empresa
                           AND forma_pago = :forma
                           AND (:excluir IS NULL OR regla_id <> :excluir)
                           AND ((categoria_id IS NULL AND :categoria IS NULL)
                                OR categoria_id = :categoria)
                           AND ((proveedor_id IS NULL AND :proveedor IS NULL)
                                OR proveedor_id = :proveedor)
                           AND :desde <= COALESCE(vigente_hasta, '9999-12-31')
                           AND vigente_desde <= COALESCE(:hasta, '9999-12-31')
                        """)
                .param("empresa", empresaId)
                .param("forma", formaPago)
                .param("excluir", excluirRegla)
                .param("categoria", categoriaId)
                .param("proveedor", proveedorId)
                .param("desde", desde)
                .param("hasta", hasta)
                .query(Integer.class)
                .single() > 0;
    }

    public Integer insertar(Integer empresaId, Integer categoriaId, Integer proveedorId,
                           String formaPago, String formaCalculo, BigDecimal factor,
                           LocalDate desde, LocalDate hasta) {
        jdbc.sql("""
                        INSERT INTO reglas_margen
                               (empresa_id, categoria_id, proveedor_id, forma_pago,
                                forma_calculo, factor, vigente_desde, vigente_hasta)
                        VALUES (:empresa, :categoria, :proveedor, :forma,
                                :calculo, :factor, :desde, :hasta)
                        """)
                .param("empresa", empresaId)
                .param("categoria", categoriaId)
                .param("proveedor", proveedorId)
                .param("forma", formaPago)
                .param("calculo", formaCalculo)
                .param("factor", factor)
                .param("desde", desde)
                .param("hasta", hasta)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Integer.class).single();
    }

    /**
     * Corrige una regla existente. El ámbito —categoría y proveedor— no
     * está en el SET: cambiarlo no es corregir una regla, es sustituirla
     * por otra, y entonces la que había deja de tener historia. Para eso
     * se cierra la vigencia de la vieja y se crea la nueva.
     */
    public int actualizar(Integer empresaId, Integer reglaId, String formaCalculo,
                          BigDecimal factor, LocalDate desde, LocalDate hasta) {
        return jdbc.sql("""
                        UPDATE reglas_margen
                           SET forma_calculo = :calculo,
                               factor        = :factor,
                               vigente_desde = :desde,
                               vigente_hasta = :hasta
                         WHERE empresa_id = :empresa AND regla_id = :regla
                        """)
                .param("calculo", formaCalculo)
                .param("factor", factor)
                .param("desde", desde)
                .param("hasta", hasta)
                .param("empresa", empresaId)
                .param("regla", reglaId)
                .update();
    }

    /** Cierra la vigencia sin borrar la fila: el histórico de precios la necesita. */
    public int cerrar(Integer empresaId, Integer reglaId, LocalDate hasta) {
        return jdbc.sql("""
                        UPDATE reglas_margen SET vigente_hasta = :hasta
                         WHERE empresa_id = :empresa AND regla_id = :regla
                        """)
                .param("hasta", hasta)
                .param("empresa", empresaId)
                .param("regla", reglaId)
                .update();
    }

    /** Si la categoría es de esta empresa. Nulo cuenta como válido: es «todas». */
    public boolean categoriaPropia(Integer empresaId, Integer categoriaId) {
        if (categoriaId == null) {
            return true;
        }
        return jdbc.sql("""
                        SELECT COUNT(*) FROM categorias
                         WHERE empresa_id = :empresa AND categoria_id = :categoria
                        """)
                .param("empresa", empresaId)
                .param("categoria", categoriaId)
                .query(Integer.class)
                .single() > 0;
    }

    public boolean proveedorPropio(Integer empresaId, Integer proveedorId) {
        if (proveedorId == null) {
            return true;
        }
        return jdbc.sql("""
                        SELECT COUNT(*) FROM proveedores
                         WHERE empresa_id = :empresa AND proveedor_id = :proveedor
                        """)
                .param("empresa", empresaId)
                .param("proveedor", proveedorId)
                .query(Integer.class)
                .single() > 0;
    }

    /**
     * Las referencias que cambiarían de tratamiento tributario si se
     * aplicara esta regla (RF-35).
     *
     * El precio nuevo se calcula con la misma fórmula que la V25 puso en
     * la vista, y no es duplicarla por gusto: aquí se necesita el precio
     * de una regla que todavía no existe, de modo que la vista no lo
     * puede dar. Si las dos expresiones discreparan, la simulación
     * mentiría, y por eso `verificar_precios.sh` comprueba que una
     * simulación con el factor que ya rige no encuentre ningún cruce.
     *
     * Solo miran al umbral las referencias cuyo tratamiento lo usa; para
     * las demás `umbral_pesos` es nulo, la comparación da nulo y la fila
     * no entra. Una lista vacía significa que nadie cruza, que es lo que
     * el requisito pide que ocurra en lugar de un error.
     */
    public List<CruceDeUmbral> simularCruces(Integer empresaId, Integer categoriaId,
                                             Integer proveedorId, String formaPago,
                                             String formaCalculo, BigDecimal factor) {
        return jdbc.sql("""
                        SELECT v.producto_id, v.codigo, v.nombre, v.costo,
                               CASE WHEN :forma = 'CREDITO' THEN v.precio_credito
                                    ELSE v.precio_contado END         AS precio_antes,
                               CASE WHEN :calculo = 'SOBRE_PRECIO'
                                    THEN ROUND(v.costo / (2 - :factor), 0)
                                    ELSE ROUND(v.costo * :factor, 0) END AS precio_despues,
                               v.umbral_pesos                          AS umbral,
                               CASE WHEN (CASE WHEN :forma = 'CREDITO' THEN v.tarifa_credito
                                               ELSE v.tarifa_contado END) > 0
                                    THEN 'GRAVADO' ELSE 'EXCLUIDO' END AS tratamiento_antes,
                               CASE WHEN (CASE WHEN :calculo = 'SOBRE_PRECIO'
                                               THEN ROUND(v.costo / (2 - :factor), 0)
                                               ELSE ROUND(v.costo * :factor, 0) END)
                                         > v.umbral_pesos
                                    THEN 'GRAVADO' ELSE 'EXCLUIDO' END AS tratamiento_despues
                          FROM v_precios_producto v
                         WHERE v.empresa_id = :empresa
                           AND v.tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL'
                           AND (:categoria IS NULL OR v.categoria_id = :categoria)
                           AND (:proveedor IS NULL OR v.proveedor_id = :proveedor)
                           AND ((CASE WHEN :forma = 'CREDITO' THEN v.tarifa_credito
                                      ELSE v.tarifa_contado END) > 0)
                               <> ((CASE WHEN :calculo = 'SOBRE_PRECIO'
                                         THEN ROUND(v.costo / (2 - :factor), 0)
                                         ELSE ROUND(v.costo * :factor, 0) END)
                                   > v.umbral_pesos)
                         ORDER BY v.codigo
                        """)
                .param("empresa", empresaId)
                .param("categoria", categoriaId)
                .param("proveedor", proveedorId)
                .param("forma", formaPago)
                .param("calculo", formaCalculo)
                .param("factor", factor)
                .query(CruceDeUmbral.class)
                .list();
    }

    /**
     * Cuántas referencias del catálogo quedarían afectadas por una regla
     * de este ámbito.
     *
     * Se expone para que la pantalla pueda decir «esto cambia el precio
     * de 34 productos» antes de guardar. Un cambio de margen es de los
     * que se hacen sin mirar y se notan en la factura del cliente.
     */
    public int productosAlcanzados(Integer empresaId, Integer categoriaId,
                                   Integer proveedorId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM productos
                         WHERE empresa_id = :empresa AND activo = 1
                           AND (:categoria IS NULL OR categoria_id = :categoria)
                           AND (:proveedor IS NULL OR proveedor_id = :proveedor)
                        """)
                .param("empresa", empresaId)
                .param("categoria", categoriaId)
                .param("proveedor", proveedorId)
                .query(Integer.class)
                .single();
    }
}
