package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.ConfiguracionTributaria;
import co.com.bspstore.sgip.api.dto.ReglaDeIva;
import co.com.bspstore.sgip.api.dto.TipoImpuestoNacional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lo que cada empresa decide sobre sus impuestos: su fila de
 * {@code empresa_config} y sus {@code reglas_iva} (ADR-0016, ADR-0024).
 *
 * Todo lo que toca una tabla de la empresa se acota por {@code empresa_id}.
 * El catálogo de tipos de impuesto es la excepción y es deliberada: son
 * dos tablas de la plataforma, sin empresa, que aquí solo se leen.
 *
 * Va con sentencias explícitas, como {@link RepositorioTributario} y
 * {@link RepositorioPrecios}: la regla de IVA se cierra pero no se edita ni
 * se borra, y una entidad mapeada ofrecería justo esas dos cosas.
 * {@code verificar_sql_del_backend.py} analiza estas sentencias en la CI.
 */
@Repository
public class RepositorioConfiguracionTributaria {

    private final JdbcClient jdbc;

    public RepositorioConfiguracionTributaria(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------ la configuración

    public Optional<ConfiguracionTributaria> buscarConfiguracion(Integer empresaId) {
        return jdbc.sql("""
                        SELECT responsable_iva, aplica_exclusion_umbral, practica_retenciones,
                               tarifa_ica, maneja_envios, cotizacion_vigencia_dias,
                               configuracion_completa
                          FROM empresa_config
                         WHERE empresa_id = :empresa
                        """)
                .param("empresa", empresaId)
                .query(ConfiguracionTributaria.class)
                .optional();
    }

    /**
     * Fija la configuración y la deja completa, en una sola sentencia.
     *
     * Crea la fila si no existía: el alta de la empresa la crea, pero una
     * empresa anterior a la V12 o una fila borrada a mano no tienen por qué
     * dejar al administrador sin poder configurar.
     */
    public void guardarConfiguracion(Integer empresaId, boolean responsableIva,
                                     boolean aplicaExclusionUmbral, boolean practicaRetenciones,
                                     BigDecimal tarifaIca, boolean manejaEnvios,
                                     int cotizacionVigenciaDias) {
        jdbc.sql("""
                        INSERT INTO empresa_config
                               (empresa_id, responsable_iva, aplica_exclusion_umbral,
                                practica_retenciones, tarifa_ica, maneja_envios,
                                cotizacion_vigencia_dias, configuracion_completa)
                        VALUES (:empresa, :responsable, :exclusion, :retenciones, :ica,
                                :envios, :dias, TRUE) AS nueva
                        ON DUPLICATE KEY UPDATE
                               responsable_iva          = nueva.responsable_iva,
                               aplica_exclusion_umbral  = nueva.aplica_exclusion_umbral,
                               practica_retenciones     = nueva.practica_retenciones,
                               tarifa_ica               = nueva.tarifa_ica,
                               maneja_envios            = nueva.maneja_envios,
                               cotizacion_vigencia_dias = nueva.cotizacion_vigencia_dias,
                               configuracion_completa   = TRUE
                        """)
                .param("empresa", empresaId)
                .param("responsable", responsableIva)
                .param("exclusion", aplicaExclusionUmbral)
                .param("retenciones", practicaRetenciones)
                .param("ica", tarifaIca)
                .param("envios", manejaEnvios)
                .param("dias", cotizacionVigenciaDias)
                .update();
    }

    // ------------------------------------------------------------- el catálogo

    private static final String SELECCION_IMPUESTO = """
            SELECT tipo_impuesto_id, codigo, nombre, tratamiento, tarifa,
                   umbral_uvt, fundamento, vigente_desde, vigente_hasta, activo
              FROM tipos_impuesto
             WHERE activo = TRUE
            """;

    /** Los tipos que la plataforma tiene activos: los que una regla puede usar. */
    public List<TipoImpuestoNacional> listarImpuestosActivos() {
        return jdbc.sql(SELECCION_IMPUESTO + " ORDER BY codigo")
                .query(TipoImpuestoNacional.class)
                .list();
    }

    /** Vacío si el código no existe o el tipo está inactivo: para una regla es lo mismo. */
    public Optional<TipoImpuestoNacional> buscarImpuestoActivo(String codigo) {
        return jdbc.sql(SELECCION_IMPUESTO + " AND codigo = :codigo")
                .param("codigo", codigo)
                .query(TipoImpuestoNacional.class)
                .optional();
    }

    // --------------------------------------------------------------- las reglas

    /**
     * La especificidad es la de {@code v_regla_iva}: un producto gana a una
     * categoría y esta a la general. {@code vigente} mira el {@code :hoy} que
     * pasa quien llama contra el rango, igual que la vista lo hace con su
     * {@code CURDATE()}.
     */
    private static final String SELECCION_REGLA = """
            SELECT r.regla_iva_id AS regla_id, r.categoria_id, c.nombre AS categoria,
                   r.producto_id, p.nombre AS producto,
                   t.codigo AS codigo_impuesto, t.nombre AS impuesto,
                   t.tratamiento, t.tarifa,
                   r.vigente_desde, r.vigente_hasta, r.fundamento,
                   CASE WHEN r.producto_id IS NOT NULL THEN 2
                        WHEN r.categoria_id IS NOT NULL THEN 1
                        ELSE 0 END AS especificidad,
                   (:hoy >= r.vigente_desde
                    AND (r.vigente_hasta IS NULL OR :hoy <= r.vigente_hasta)) AS vigente
              FROM reglas_iva r
              JOIN tipos_impuesto t ON t.tipo_impuesto_id = r.tipo_impuesto_id
              LEFT JOIN categorias c ON c.categoria_id = r.categoria_id
                                    AND c.empresa_id   = r.empresa_id
              LEFT JOIN productos  p ON p.producto_id  = r.producto_id
                                    AND p.empresa_id   = r.empresa_id
             WHERE r.empresa_id = :empresa
            """;

    public List<ReglaDeIva> listarReglas(Integer empresaId, LocalDate hoy) {
        return jdbc.sql(SELECCION_REGLA + """
                         ORDER BY especificidad DESC,
                                  r.vigente_desde DESC,
                                  r.regla_iva_id DESC
                        """)
                .param("empresa", empresaId)
                .param("hoy", hoy)
                .query(ReglaDeIva.class)
                .list();
    }

    public Optional<ReglaDeIva> buscarRegla(Integer empresaId, Integer reglaId, LocalDate hoy) {
        return jdbc.sql(SELECCION_REGLA + " AND r.regla_iva_id = :regla")
                .param("empresa", empresaId)
                .param("regla", reglaId)
                .param("hoy", hoy)
                .query(ReglaDeIva.class)
                .optional();
    }

    /**
     * Si ya hay una regla del mismo alcance cuya vigencia se solape con la
     * que se pretende.
     *
     * Dos reglas del mismo alcance a la vez no dan error —v_regla_iva
     * desempata por fecha y por identificador—, pero el resultado es
     * invisible: se corrige una y el impuesto no cambia, porque manda la
     * otra. Por eso se rechaza al crearla.
     *
     * No lo puede hacer una clave única: el alcance se expresa con nulos, y
     * los dos motores consideran distintos dos nulos en un índice único (el
     * mismo obstáculo de {@link RepositorioPrecios#haySolape}).
     *
     * Dos intervalos se solapan si cada uno empieza antes de que acabe el
     * otro, y la vigencia abierta cuenta como un final lejanísimo: sin eso,
     * dos reglas abiertas del mismo alcance —el caso más común— pasarían.
     * Un alcance es la pareja (categoría, producto) con nulo como «ninguno»;
     * la regla general es la pareja de nulos. Se acota a la empresa: la
     * regla de otro comercio con el mismo alcance no choca con esta.
     */
    public boolean haySolape(Integer empresaId, Integer categoriaId, Integer productoId,
                             LocalDate desde, LocalDate hasta) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM reglas_iva
                         WHERE empresa_id = :empresa
                           AND ((categoria_id IS NULL AND :categoria IS NULL)
                                OR categoria_id = :categoria)
                           AND ((producto_id IS NULL AND :producto IS NULL)
                                OR producto_id = :producto)
                           AND :desde <= COALESCE(vigente_hasta, '9999-12-31')
                           AND vigente_desde <= COALESCE(:hasta, '9999-12-31')
                        """)
                .param("empresa", empresaId)
                .param("categoria", categoriaId)
                .param("producto", productoId)
                .param("desde", desde)
                .param("hasta", hasta)
                .query(Integer.class)
                .single() > 0;
    }

    public Integer insertarRegla(Integer empresaId, Integer categoriaId, Integer productoId,
                                 Integer tipoImpuestoId, LocalDate desde, LocalDate hasta,
                                 String fundamento) {
        jdbc.sql("""
                        INSERT INTO reglas_iva
                               (empresa_id, categoria_id, producto_id, tipo_impuesto_id,
                                vigente_desde, vigente_hasta, fundamento)
                        VALUES (:empresa, :categoria, :producto, :tipo,
                                :desde, :hasta, :fundamento)
                        """)
                .param("empresa", empresaId)
                .param("categoria", categoriaId)
                .param("producto", productoId)
                .param("tipo", tipoImpuestoId)
                .param("desde", desde)
                .param("hasta", hasta)
                .param("fundamento", fundamento)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Integer.class).single();
    }

    /**
     * Bloquea la fila de la empresa hasta que termine la transacción.
     *
     * Sirve para que dos peticiones simultáneas de crear una regla del mismo
     * alcance se pongan en fila: sin esto las dos leen «no hay solape» antes
     * de que la otra escriba, y las dos crean. La empresa existe siempre; el
     * bloqueo se pide a la fila que ya hace de ancla de todo lo suyo, no a
     * una tabla de reglas que puede estar vacía.
     */
    public void bloquearEmpresa(Integer empresaId) {
        jdbc.sql("SELECT empresa_id FROM empresas WHERE empresa_id = :empresa FOR UPDATE")
                .param("empresa", empresaId)
                .query(Integer.class)
                .list();
    }

    /** Cierra la vigencia sin borrar la fila: una venta se explica con la regla que regía. */
    public int cerrarRegla(Integer empresaId, Integer reglaId, LocalDate hasta) {
        return jdbc.sql("""
                        UPDATE reglas_iva SET vigente_hasta = :hasta
                         WHERE empresa_id = :empresa AND regla_iva_id = :regla
                        """)
                .param("hasta", hasta)
                .param("empresa", empresaId)
                .param("regla", reglaId)
                .update();
    }

    /**
     * Borra una regla que todavía no empezó a regir: nunca explicó una venta,
     * de modo que retirarla corrige una equivocación y no borra historia.
     * Ninguna tabla apunta a {@code reglas_iva}.
     *
     * Que «aún no empieza» se vuelve a comprobar en la sentencia, contra el mismo
     * {@code hoy} con el que el servicio lo decidió, y no contra otro reloj: sin
     * esa condición, entre la lectura y el borrado una regla que ya regía y con la
     * que se explican ventas podía borrarse (M4). Devuelve 0 si no borró nada.
     */
    public int borrarRegla(Integer empresaId, Integer reglaId, LocalDate hoy) {
        return jdbc.sql("""
                        DELETE FROM reglas_iva
                         WHERE empresa_id = :empresa AND regla_iva_id = :regla
                           AND vigente_desde > :hoy
                        """)
                .param("empresa", empresaId)
                .param("regla", reglaId)
                .param("hoy", hoy)
                .update();
    }
}
