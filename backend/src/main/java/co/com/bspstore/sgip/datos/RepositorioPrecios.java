package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.ReglaDeMargen;
import co.com.bspstore.sgip.logica.ReglaDePrecio;
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
 *
 * Hay dos clases de regla y no se mezclan. Las de ámbito (general, categoría,
 * proveedor) las administra la pantalla de reglas; las de un producto
 * (`producto_id`, ADR-0025) nacen y cambian solo desde el alta y la edición del
 * producto. Por eso toda consulta de la primera clase lleva
 * `producto_id IS NULL`: categoría y proveedor nulos significan «general», y
 * sin esa condición la regla de un producto pasaría por una regla general.
 */
@Repository
public class RepositorioPrecios {

    /**
     * La fórmula del precio de venta, en un solo sitio del backend.
     *
     * `v_precios_producto` la aplica con la regla vigente de cada
     * referencia. Esta copia existe para los casos en que hace falta el
     * precio de algo que la vista no puede dar: una regla que todavía no
     * existe (RF-35) o un costo que todavía no se ha aplicado (RF-27).
     * El ADR-0021 aceptó esa duplicación y la condicionó a que estuviera
     * vigilada, y el guardián de `verificar_precios.sh` la vigila
     * comparando precio contra precio en todas las referencias.
     *
     * Con el ADR-0025 esta fórmula ya no contesta sola cuando la referencia
     * tiene un precio fijo: el factor es nulo. Quien la use sobre la regla
     * vigente va envuelto en {@code COALESCE(v.precio_fijo_contado, …)}.
     *
     * Lo que no se podía aceptar eran tres copias. Esta constante nació
     * al ir a escribir la tercera, para el aviso de umbral de la
     * importación: dentro de `simularCruces` la fórmula ya aparecía
     * escrita tres veces, de modo que bajarla a una mejora también la
     * consulta que la tenía.
     *
     * Huecos: %1$s el costo, %2$s el factor, %3$s la forma de cálculo.
     */
    public static final String PRECIO = """
            (CASE WHEN %3$s = 'SOBRE_PRECIO'
                  THEN ROUND(%1$s / (2 - %2$s), 0)
                  ELSE ROUND(%1$s * %2$s, 0) END)""";

    /** El precio que daría la regla que se está simulando (RF-35). */
    private static final String PRECIO_CON_REGLA_NUEVA =
            PRECIO.formatted("v.costo", ":factor", ":calculo");

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
     * `vigente` mira el `:hoy` que pasa quien llama contra el rango, igual que
     * la vista lo hace con su `CURDATE()`.
     */
    private static final String SELECCION = """
            SELECT r.regla_id, r.categoria_id, c.nombre AS categoria,
                   r.proveedor_id, p.razon_social AS proveedor,
                   r.forma_pago, r.forma_calculo, r.factor,
                   r.vigente_desde, r.vigente_hasta,
                   (r.categoria_id IS NOT NULL) + (r.proveedor_id IS NOT NULL)
                       AS especificidad,
                   (:hoy >= r.vigente_desde
                    AND (r.vigente_hasta IS NULL OR :hoy <= r.vigente_hasta))
                       AS vigente
              FROM reglas_margen r
              LEFT JOIN categorias  c ON c.categoria_id  = r.categoria_id
                                     AND c.empresa_id    = r.empresa_id
              LEFT JOIN proveedores p ON p.proveedor_id  = r.proveedor_id
                                     AND p.empresa_id    = r.empresa_id
             WHERE r.empresa_id = :empresa
               AND r.producto_id IS NULL
            """;

    public List<ReglaDeMargen> listar(Integer empresaId, LocalDate hoy) {
        return jdbc.sql(SELECCION + """
                         ORDER BY r.forma_pago,
                                  especificidad DESC,
                                  r.vigente_desde DESC,
                                  r.regla_id DESC
                        """)
                .param("empresa", empresaId)
                .param("hoy", hoy)
                .query(ReglaDeMargen.class)
                .list();
    }

    public Optional<ReglaDeMargen> buscar(Integer empresaId, Integer reglaId, LocalDate hoy) {
        return jdbc.sql(SELECCION + " AND r.regla_id = :regla")
                .param("empresa", empresaId)
                .param("regla", reglaId)
                .param("hoy", hoy)
                .query(ReglaDeMargen.class)
                .optional();
    }

    /**
     * Pone en fila a quienes crean reglas de ámbito en una misma empresa.
     *
     * Comprobar el solape y escribir son dos pasos, y sin nada que los ponga en
     * fila dos peticiones a la vez ven las dos «no hay solape» y escriben las
     * dos (un doble clic). No hay fila de regla que bloquear antes de crearla ni
     * clave única que lo impida —el ámbito se expresa con nulos, ver
     * {@link #haySolape}—, de modo que se bloquea la fila de la empresa, igual
     * que hace {@code RepositorioConfiguracionTributaria.bloquearEmpresa} con las
     * reglas de IVA. El bloqueo se suelta al confirmar la transacción.
     */
    public void bloquearEmpresa(Integer empresaId) {
        jdbc.sql("SELECT empresa_id FROM empresas WHERE empresa_id = :empresa FOR UPDATE")
                .param("empresa", empresaId)
                .query(Integer.class)
                .list();
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
                           AND producto_id IS NULL
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

    /** Lo que hace falta saber de una regla de ámbito para reemplazarla. */
    public record ReglaBloqueada(Integer reglaId, Integer categoriaId, Integer proveedorId,
                                 String formaPago, LocalDate vigenteDesde, LocalDate vigenteHasta) {}

    /**
     * Lee la regla bloqueándola, acotada por empresa y solo de ámbito
     * (`producto_id IS NULL`).
     *
     * Lectura con bloqueo a propósito: dos reemplazos a la vez de la misma
     * regla se ponen en fila, y el segundo ve el cierre del primero en lugar
     * de la foto de cuando empezó su transacción.
     */
    public Optional<ReglaBloqueada> bloquear(Integer empresaId, Integer reglaId) {
        return jdbc.sql("""
                        SELECT regla_id, categoria_id, proveedor_id, forma_pago,
                               vigente_desde, vigente_hasta
                          FROM reglas_margen
                         WHERE empresa_id = :empresa AND regla_id = :regla
                           AND producto_id IS NULL
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("regla", reglaId)
                .query(ReglaBloqueada.class)
                .optional();
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

    // ------------------------------------------------- la regla de un producto

    /**
     * Pone en fila a quienes cambian el precio de un mismo producto.
     *
     * Sin esto, dos personas guardando a la vez ven las dos que no hay regla de
     * hoy y las dos intentan abrirla: `uq_reglas_margen_producto` deja entrar a
     * una y la otra recibe un error en vez de corregir la misma fila.
     */
    public void bloquearProducto(Integer empresaId, Integer productoId) {
        jdbc.sql("""
                        SELECT producto_id FROM productos
                         WHERE empresa_id = :empresa AND producto_id = :producto
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .query(Integer.class)
                .optional();
    }

    /**
     * La regla propia que rige hoy, para saber si lo escrito cambia algo.
     *
     * Lectura con bloqueo a propósito: una lectura normal vería la foto de
     * cuando empezó la transacción, y tras esperar a {@link #bloquearProducto}
     * esa foto ya no es la verdad.
     */
    public Optional<ReglaDePrecio> reglaPropiaVigente(Integer empresaId, Integer productoId,
                                                      String formaPago, LocalDate hoy) {
        return jdbc.sql("""
                        SELECT factor, precio_fijo, forma_calculo
                          FROM reglas_margen
                         WHERE empresa_id = :empresa AND producto_id = :producto
                           AND forma_pago = :forma
                           AND vigente_desde <= :hoy
                           AND (vigente_hasta IS NULL OR vigente_hasta >= :hoy)
                         ORDER BY vigente_desde DESC
                         LIMIT 1
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("forma", formaPago)
                .param("hoy", hoy)
                .query(ReglaDePrecio.class)
                .optional();
    }

    /** La regla que decide hoy el precio, sea propia o de un ámbito mayor. */
    public Optional<ReglaDePrecio> reglaVigente(Integer empresaId, Integer productoId,
                                                String formaPago) {
        return jdbc.sql("""
                        SELECT factor, precio_fijo, forma_calculo
                          FROM v_regla_margen
                         WHERE empresa_id = :empresa AND producto_id = :producto
                           AND forma_pago = :forma
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("forma", formaPago)
                .query(ReglaDePrecio.class)
                .optional();
    }

    /**
     * Corrige la regla propia que empezó hoy. Devuelve cuántas filas encontró.
     *
     * Dos cambios el mismo día corrigen la misma fila: una vigencia de cero
     * días no explica ninguna venta, y dos filas con la misma fecha de comienzo
     * las prohíbe `uq_reglas_margen_producto`.
     *
     * «Hoy» es el que pasa quien llama, tomado del reloj de la aplicación en la
     * hora de Bogotá ({@code Hoy.de}), en esta sentencia y en las dos siguientes.
     * Ya no es el `CURDATE()` del motor. La vista que decide qué regla rige sí lo
     * usa, de modo que las dos coinciden solo mientras la sesión de la base vaya
     * en la hora de Bogotá: si la base fuera por delante de la aplicación, una
     * regla fechada «hoy» por la aplicación todavía no regiría para la vista y el
     * producto se quedaría sin precio. Por eso la sesión se fija en esa hora
     * (ADR-0029).
     */
    public int corregirReglaPropiaDeHoy(Integer empresaId, Integer productoId, String formaPago,
                                        ReglaDePrecio regla, LocalDate hoy) {
        return jdbc.sql("""
                        UPDATE reglas_margen
                           SET forma_calculo = :calculo,
                               factor        = :factor,
                               precio_fijo   = :fijo
                         WHERE empresa_id = :empresa AND producto_id = :producto
                           AND forma_pago = :forma
                           AND vigente_desde = :hoy
                        """)
                .param("calculo", regla.formaCalculo())
                .param("factor", regla.factor())
                .param("fijo", regla.precioFijo())
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("forma", formaPago)
                .param("hoy", hoy)
                .update();
    }

    /**
     * Cierra ayer la regla propia que venía rigiendo.
     *
     * Solo las que empezaron antes de hoy: `ck_reglas_margen_rango` no admite
     * que una regla termine antes de empezar, y la que empezó hoy ya la corrigió
     * {@link #corregirReglaPropiaDeHoy}. Es el mismo cuidado que pone
     * `RepositorioImportacion.cerrarCostoVigente`.
     */
    public int cerrarReglaPropiaAyer(Integer empresaId, Integer productoId, String formaPago,
                                     LocalDate hoy) {
        return jdbc.sql("""
                        UPDATE reglas_margen
                           SET vigente_hasta = :ayer
                         WHERE empresa_id = :empresa AND producto_id = :producto
                           AND forma_pago = :forma
                           AND vigente_desde < :hoy
                           AND (vigente_hasta IS NULL OR vigente_hasta >= :hoy)
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("forma", formaPago)
                .param("hoy", hoy)
                .param("ayer", hoy.minusDays(1))
                .update();
    }

    /** Abre desde hoy la regla propia nueva. */
    public void abrirReglaPropia(Integer empresaId, Integer productoId, String formaPago,
                                 ReglaDePrecio regla, LocalDate hoy) {
        jdbc.sql("""
                        INSERT INTO reglas_margen
                               (empresa_id, producto_id, forma_pago, forma_calculo,
                                factor, precio_fijo, vigente_desde)
                        VALUES (:empresa, :producto, :forma, :calculo,
                                :factor, :fijo, :hoy)
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("forma", formaPago)
                .param("calculo", regla.formaCalculo())
                .param("factor", regla.factor())
                .param("fijo", regla.precioFijo())
                .param("hoy", hoy)
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
     *
     * Un producto con regla propia para esa forma de pago no entra: la regla
     * que se simula no le cambiaría el precio (ADR-0025).
     */
    public List<CruceDeUmbral> simularCruces(Integer empresaId, Integer categoriaId,
                                             Integer proveedorId, String formaPago,
                                             String formaCalculo, BigDecimal factor) {
        return jdbc.sql("""
                        SELECT v.producto_id, v.codigo, v.nombre, v.costo,
                               CASE WHEN :forma = 'CREDITO' THEN v.precio_credito
                                    ELSE v.precio_contado END         AS precio_antes,
                               %1$s                                   AS precio_despues,
                               v.umbral_pesos                          AS umbral,
                               CASE WHEN (CASE WHEN :forma = 'CREDITO' THEN v.tarifa_credito
                                               ELSE v.tarifa_contado END) > 0
                                    THEN 'GRAVADO' ELSE 'EXCLUIDO' END AS tratamiento_antes,
                               CASE WHEN %1$s > v.umbral_pesos
                                    THEN 'GRAVADO' ELSE 'EXCLUIDO' END AS tratamiento_despues
                          FROM v_precios_producto v
                         WHERE v.empresa_id = :empresa
                           AND v.tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL'
                           AND (CASE WHEN :forma = 'CREDITO' THEN v.regla_propia_credito
                                     ELSE v.regla_propia_contado END) = 0
                           AND (:categoria IS NULL OR v.categoria_id = :categoria)
                           AND (:proveedor IS NULL OR v.proveedor_id = :proveedor)
                           AND ((CASE WHEN :forma = 'CREDITO' THEN v.tarifa_credito
                                      ELSE v.tarifa_contado END) > 0)
                               <> (%1$s > v.umbral_pesos)
                         ORDER BY v.codigo
                        """.formatted(PRECIO_CON_REGLA_NUEVA))
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
     *
     * No cuenta los productos con regla propia en las dos formas de pago, que
     * la regla no movería (ADR-0025). El endpoint no recibe la forma de pago, de
     * modo que un producto con regla propia en una sola de las dos sí cuenta: la
     * otra le cambiaría.
     *
     * No sale de `v_precios_producto`, que ya lo sabe, porque contar con ella
     * resuelve el precio de toda la empresa para descartarlo: con 10.000 productos
     * son cientos de veces más trabajo (ADR-0025). Lo cubre
     * `PrecioDeProductoTest` contra la vigencia que usa la vista.
     */
    public int productosAlcanzados(Integer empresaId, Integer categoriaId,
                                   Integer proveedorId, LocalDate hoy) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM productos p
                         WHERE p.empresa_id = :empresa AND p.activo = 1
                           AND (:categoria IS NULL OR p.categoria_id = :categoria)
                           AND (:proveedor IS NULL OR p.proveedor_id = :proveedor)
                           AND (SELECT COUNT(DISTINCT r.forma_pago)
                                  FROM reglas_margen r
                                 WHERE r.empresa_id = p.empresa_id
                                   AND r.producto_id = p.producto_id
                                   AND r.vigente_desde <= :hoy
                                   AND (r.vigente_hasta IS NULL OR r.vigente_hasta >= :hoy)) < 2
                        """)
                .param("empresa", empresaId)
                .param("categoria", categoriaId)
                .param("proveedor", proveedorId)
                .param("hoy", hoy)
                .query(Integer.class)
                .single();
    }
}
