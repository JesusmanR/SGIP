package co.com.bspstore.sgip.datos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * El histórico de costos de un producto cuando alguien edita su costo a mano
 * (RF-28, M15).
 *
 * Escribe el mismo formato que {@link RepositorioImportacion} al aplicar una
 * lista: cierra ayer el periodo que venía rigiendo y abre uno desde hoy, con
 * una fila por periodo y sin solapes. Se distingue de la importación en un
 * solo dato: {@code carga_id} queda nulo, porque la edición no viene de ninguna
 * carga. Así el histórico sigue diciendo el costo real y, con él, los avisos de
 * umbral de las cargas y cualquier informe de margen de ventas.
 *
 * «Hoy» lo pasa quien llama, con {@code Hoy.de(reloj)}, igual que la
 * importación (M5).
 */
@Repository
public class RepositorioCostos {

    private final JdbcClient jdbc;

    public RepositorioCostos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deja el periodo de costo de hoy con este costo.
     *
     * Tres pasos, como la importación, y en este orden:
     * <ol>
     *   <li>Cierra ayer el periodo abierto que empezó antes de hoy.
     *       {@code ck_historico_rango} no admite que el fin sea anterior al
     *       comienzo, de modo que el que se abrió hoy no se cierra ayer.</li>
     *   <li>Retira el periodo que empezó hoy, si lo hay: dos cambios el mismo
     *       día dejan un solo periodo con el último costo, porque
     *       {@code uq_historico_vigencia} es única por producto y fecha de
     *       comienzo y una vigencia de cero días no explica nada.</li>
     *   <li>Abre el periodo de hoy, sin {@code carga_id}.</li>
     * </ol>
     */
    public void registrarCambio(Integer empresaId, Integer productoId, BigDecimal costo,
                                LocalDate hoy) {
        jdbc.sql("""
                        UPDATE historico_costos
                           SET vigente_hasta = :ayer
                         WHERE empresa_id = :empresa AND producto_id = :producto
                           AND vigente_hasta IS NULL
                           AND vigente_desde < :hoy
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("hoy", hoy)
                .param("ayer", hoy.minusDays(1))
                .update();
        jdbc.sql("""
                        DELETE FROM historico_costos
                         WHERE empresa_id = :empresa AND producto_id = :producto
                           AND vigente_desde = :hoy
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("hoy", hoy)
                .update();
        jdbc.sql("""
                        INSERT INTO historico_costos
                               (empresa_id, producto_id, costo, vigente_desde, carga_id)
                        VALUES (:empresa, :producto, :costo, :hoy, NULL)
                        """)
                .param("empresa", empresaId)
                .param("producto", productoId)
                .param("costo", costo)
                .param("hoy", hoy)
                .update();
    }
}
