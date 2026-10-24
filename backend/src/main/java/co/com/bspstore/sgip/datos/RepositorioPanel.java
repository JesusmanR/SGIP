package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.Panel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Las lecturas del panel de indicadores (P-02), que es la primera
 * pantalla tras el acceso del administrador.
 *
 * Esta clase no calcula nada: lee. Las cuatro cifras, la tabla de
 * críticas y el gráfico salen de vistas y tablas que ya existían desde
 * la V6 y la V19, y que llevaban semanas calculándose sin que nadie las
 * leyera. Eso no es casualidad: el panel es, por diseño, el consumidor
 * de `v_inventario_valorizado`, `v_existencias_criticas` y
 * `v_rotacion_inventario`.
 *
 * Toda sentencia lleva `empresa_id` en su propio WHERE. No se apoya en
 * que la vista ya filtre ni en que la pantalla pida lo suyo: la RN-16
 * dice que el aislamiento se aplica en la consulta, y una consulta de
 * panel que se olvide del filtro devolvería el inventario de todos los
 * comercios con aspecto de ser el propio.
 */
@Repository
public class RepositorioPanel {

    private final JdbcClient jdbc;

    public RepositorioPanel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Las cuatro cifras, en una sola sentencia.
     *
     * Son cuatro conteos sobre la misma tabla y un par de sumas sobre la
     * vista valorizada. Partirlos en cuatro consultas haría cuatro
     * recorridos de `productos` para contestar lo que un recorrido
     * contesta, y además dejaría las cifras tomadas en cuatro instantes
     * distintos.
     *
     * Los conteos se hacen con SUM de una condición y no con COUNT y un
     * WHERE: así los cuatro caben en el mismo recorrido. `estado_stock`
     * es columna generada STORED con índice
     * (`ix_productos_estado`), de modo que es un valor almacenado y no
     * una expresión que se recalcule por fila.
     *
     * La vista valorizada se consulta aparte dentro de la misma
     * sentencia, como subconsulta escalar, porque cuelga de
     * `v_precios_producto` y unirla al conteo multiplicaría filas.
     * COALESCE sobre las sumas porque una empresa sin productos devuelve
     * nulo, y un panel recién instalado debe mostrar cero, no vacío: es
     * el primero de los tres estados que el mockup pide.
     */
    public Panel.Cifras cifras(Integer empresaId) {
        return jdbc.sql("""
                        SELECT CAST(SUM(p.activo = TRUE) AS SIGNED)
                                 AS referencias_activas,
                               CAST(SUM(p.activo = TRUE
                                        AND p.creado_en >= NOW() - INTERVAL 7 DAY) AS SIGNED)
                                 AS altas_de_la_semana,
                               CAST(SUM(p.activo = TRUE
                                        AND p.estado_stock = 'CRITICO') AS SIGNED)
                                 AS en_existencia_critica,
                               CAST(SUM(p.activo = TRUE
                                        AND p.estado_stock = 'AGOTADO') AS SIGNED)
                                 AS agotadas,
                               (SELECT COALESCE(SUM(i.valor_al_costo), 0)
                                  FROM v_inventario_valorizado i
                                 WHERE i.empresa_id = :empresa)
                                 AS capital_al_costo,
                               (SELECT COALESCE(SUM(i.valor_a_precio_venta), 0)
                                  FROM v_inventario_valorizado i
                                 WHERE i.empresa_id = :empresa)
                                 AS capital_a_precio_venta,
                               (SELECT CAST(COALESCE(SUM(i.unidades_pendientes), 0) AS SIGNED)
                                  FROM v_inventario_valorizado i
                                 WHERE i.empresa_id = :empresa)
                                 AS unidades_pendientes
                          FROM productos p
                         WHERE p.empresa_id = :empresa
                        """)
                .param("empresa", empresaId)
                .query(Panel.Cifras.class)
                .single();
    }

    /**
     * La tabla corta de existencias críticas.
     *
     * Agotadas primero y críticas después, porque son dos urgencias
     * distintas: una referencia agotada ya está perdiendo ventas y una
     * crítica todavía no. Dentro de cada grupo, la que está más lejos de
     * su mínimo primero.
     *
     * El límite lo pone quien llama. El mockup enseña tres filas y un
     * «Ver todas»; el número exacto es decisión de la pantalla, no del
     * repositorio.
     */
    public List<Panel.ExistenciaCritica> criticas(Integer empresaId, int limite) {
        return jdbc.sql("""
                        SELECT c.producto_id, c.codigo, c.nombre, c.presentacion,
                               c.categoria, c.proveedor, c.contacto_proveedor,
                               c.existencia, c.existencia_minima, c.estado_stock
                          FROM v_existencias_criticas c
                         WHERE c.empresa_id = :empresa
                         ORDER BY c.estado_stock = 'AGOTADO' DESC,
                                  c.existencia - c.existencia_minima ASC,
                                  c.codigo ASC
                         LIMIT :limite
                        """)
                .param("empresa", empresaId)
                .param("limite", limite)
                .query(Panel.ExistenciaCritica.class)
                .list();
    }

    /**
     * La última carga de precios registrada, aplicada o no.
     *
     * Por `fecha` y no por `fecha_aplicacion`: la tarjeta informa de lo
     * último que pasó con las listas de precios, y una carga que se
     * subió ayer y sigue sin decidir es justamente lo que hay que
     * recordarle a quien entra. Ordenar por la fecha de aplicación
     * dejaría las pendientes invisibles, que es lo contrario de lo que
     * la tarjeta sirve.
     *
     * `carga_id` desempata: dos cargas del mismo instante son posibles
     * y un ORDER BY sin desempate devolvería cualquiera de las dos, con
     * lo que la pantalla cambiaría de contenido entre dos recargas sin
     * que nada haya cambiado.
     */
    public Optional<Panel.CargaEnPanel> ultimaCarga(Integer empresaId) {
        return jdbc.sql("""
                        SELECT c.carga_id, p.razon_social AS proveedor,
                               c.nombre_archivo, c.estado, c.fecha, c.fecha_aplicacion,
                               c.registros_leidos     AS leidos,
                               c.registros_aceptados  AS aceptados,
                               c.registros_rechazados AS rechazados
                          FROM cargas_precios c
                          JOIN proveedores p
                            ON p.empresa_id = c.empresa_id
                           AND p.proveedor_id = c.proveedor_id
                         WHERE c.empresa_id = :empresa
                         ORDER BY c.fecha DESC, c.carga_id DESC
                         LIMIT 1
                        """)
                .param("empresa", empresaId)
                .query(Panel.CargaEnPanel.class)
                .optional();
    }

    /**
     * El precio que daba cada referencia con el costo que tenía antes de
     * la carga.
     *
     * Tercer uso de {@link RepositorioPrecios#PRECIO}, y el que el
     * ADR-0021 encargó: la fórmula no se vuelve a escribir. Las tres
     * preguntas son distintas —una regla nueva sobre el costo actual, un
     * costo nuevo sobre la regla actual, y ahora el costo anterior sobre
     * la regla actual— y lo único común es el álgebra, que es
     * exactamente lo que se habría duplicado por tercera vez.
     */
    private static final String PRECIO_CON_COSTO_ANTERIOR =
            RepositorioPrecios.PRECIO.formatted(
                    "h.costo", "v.factor_contado", "v.base_contado");

    /**
     * Qué referencias cambiaron de tratamiento tributario por culpa de
     * una carga ya aplicada (RF-35 visto desde después).
     *
     * `cruzarianAlAplicar` contesta lo mismo antes de aplicar, con el
     * costo que trae el archivo. Esta contesta después, con el costo que
     * la carga desplazó, y las dos salen de la misma fórmula. No se
     * pueden unificar: la de antes compara contra una hipótesis y esta
     * contra un hecho registrado.
     *
     * El costo anterior sale de `historico_costos`. La V28 y el propio
     * `RepositorioImportacion` fijan la convención: aplicar una carga
     * cierra el periodo vigente con `vigente_hasta = CURDATE() - 1 día`
     * y abre uno nuevo con `vigente_desde = CURDATE()` y el `carga_id`
     * de la carga. El periodo anterior es, por tanto, el que termina el
     * día antes de que empiece el nuevo.
     *
     * Una referencia cuyo costo era el primero de su historia no tiene
     * periodo anterior y no aparece: no cambió de tratamiento, lo
     * estrenó. El JOIN interno lo resuelve sin tener que decirlo.
     *
     * Solo miran al umbral las referencias cuyo tratamiento lo usa; para
     * las demás `umbral_pesos` es nulo, la comparación da nulo y la fila
     * no entra. Sin cruces, lista vacía, que es lo que el criterio del
     * RF-35 exige en lugar de un error.
     */
    public List<CruceDeUmbral> cruzaronEnLaCarga(Integer empresaId, Long cargaId) {
        return jdbc.sql("""
                        SELECT v.producto_id, v.codigo, v.nombre, v.costo,
                               %1$s                                    AS precio_antes,
                               v.precio_contado                        AS precio_despues,
                               v.umbral_pesos                          AS umbral,
                               CASE WHEN %1$s > v.umbral_pesos
                                    THEN 'GRAVADO' ELSE 'EXCLUIDO' END AS tratamiento_antes,
                               CASE WHEN v.tarifa_contado > 0
                                    THEN 'GRAVADO' ELSE 'EXCLUIDO' END AS tratamiento_despues
                          FROM historico_costos nuevo
                          JOIN historico_costos h
                            ON h.empresa_id = nuevo.empresa_id
                           AND h.producto_id = nuevo.producto_id
                           AND h.vigente_hasta = nuevo.vigente_desde - INTERVAL 1 DAY
                          JOIN v_precios_producto v
                            ON v.empresa_id = nuevo.empresa_id
                           AND v.producto_id = nuevo.producto_id
                         WHERE nuevo.empresa_id = :empresa
                           AND nuevo.carga_id = :carga
                           AND v.tratamiento_iva = 'EXCLUIDO_HASTA_UMBRAL'
                           AND (%1$s > v.umbral_pesos) <> (v.tarifa_contado > 0)
                         ORDER BY v.codigo
                        """.formatted(PRECIO_CON_COSTO_ANTERIOR))
                .param("empresa", empresaId)
                .param("carga", cargaId)
                .query(CruceDeUmbral.class)
                .list();
    }

    /**
     * Ingresos y egresos por día de los últimos treinta.
     *
     * Devuelve solo los días con movimiento. El hueco lo rellena la
     * pantalla: es ella la que sabe cuántos puntos dibuja y qué aspecto
     * tiene un día sin nada, y una consulta que generase treinta filas
     * de ceros necesitaría una tabla de calendario o una recursiva para
     * no decir nada.
     *
     * La ventana se mide en días y no en horas: `CURDATE() - 29 DAY`
     * incluye hoy, de modo que son treinta días naturales contando el de
     * hoy, que es lo que «últimos 30 días» significa para quien mira un
     * panel. Con `NOW() - 30 DAY` el primer día aparecería recortado a
     * la hora de la consulta y el gráfico cambiaría de forma a lo largo
     * de la jornada.
     *
     * `cantidad` es siempre positiva —lo garantiza
     * `ck_movimientos_cantidad`— y el signo lo da el tipo, de modo que
     * los dos SUM salen positivos y la pantalla decide hacia dónde
     * dibuja cada barra.
     */
    public List<Panel.DiaDeMovimiento> movimientosPorDia(Integer empresaId) {
        return jdbc.sql("""
                        SELECT DATE(m.fecha) AS fecha,
                               CAST(COALESCE(SUM(CASE WHEN m.tipo = 'INGRESO'
                                                      THEN m.cantidad END), 0) AS SIGNED)
                                 AS ingresos,
                               CAST(COALESCE(SUM(CASE WHEN m.tipo = 'EGRESO'
                                                      THEN m.cantidad END), 0) AS SIGNED)
                                 AS egresos
                          FROM movimientos_inventario m
                         WHERE m.empresa_id = :empresa
                           AND m.fecha >= CURDATE() - INTERVAL 29 DAY
                         GROUP BY DATE(m.fecha)
                         ORDER BY fecha ASC
                        """)
                .param("empresa", empresaId)
                .query(Panel.DiaDeMovimiento.class)
                .list();
    }
}
