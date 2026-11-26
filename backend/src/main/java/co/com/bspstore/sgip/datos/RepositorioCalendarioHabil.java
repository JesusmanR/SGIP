package co.com.bspstore.sgip.datos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Lo que la vigencia de una cotización necesita de la base (RF-37): los festivos del calendario, que son
 * del sistema, y los días de vigencia de cada empresa, que son suyos.
 *
 * {@code calendario_habil} no tiene {@code empresa_id}: los festivos de Colombia son los mismos para todas
 * las tiendas. {@code empresa_config} sí, y la lectura de la vigencia lleva la empresa.
 */
@Repository
public class RepositorioCalendarioHabil {

    /** Los días hábiles de vigencia de una empresa que todavía no tiene fila en {@code empresa_config}. */
    static final int VIGENCIA_POR_OMISION = 5;

    private final JdbcClient jdbc;

    public RepositorioCalendarioHabil(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Las fechas de {@code calendario_habil} entre las dos dadas, ambas inclusive. */
    public Set<LocalDate> festivosEntre(LocalDate desde, LocalDate hasta) {
        return new HashSet<>(jdbc.sql("""
                        SELECT fecha FROM calendario_habil
                         WHERE fecha >= :desde AND fecha <= :hasta
                        """)
                .param("desde", desde)
                .param("hasta", hasta)
                .query(LocalDate.class)
                .list());
    }

    /** Los años, entre los dos dados y ambos inclusive, que tienen al menos un festivo cargado. */
    public Set<Integer> aniosConFestivos(int desde, int hasta) {
        return new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT YEAR(fecha) FROM calendario_habil
                         WHERE fecha >= :inicio AND fecha < :fin
                        """)
                .param("inicio", LocalDate.of(desde, 1, 1))
                .param("fin", LocalDate.of(hasta + 1, 1, 1))
                .query(Integer.class)
                .list());
    }

    /** Los días hábiles de vigencia de las cotizaciones de la empresa; 5 si aún no tiene configuración. */
    public int vigenciaDeLaEmpresa(Integer empresaId) {
        return jdbc.sql("SELECT cotizacion_vigencia_dias FROM empresa_config WHERE empresa_id = :empresa")
                .param("empresa", empresaId)
                .query(Integer.class)
                .optional()
                .orElse(VIGENCIA_POR_OMISION);
    }
}
