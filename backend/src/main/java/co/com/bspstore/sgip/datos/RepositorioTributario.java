package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.TipoImpuestoNacional;
import co.com.bspstore.sgip.api.dto.ValorUvt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * El catálogo tributario de alcance nacional: {@code uvt_anual} y
 * {@code tipos_impuesto} (RF-05).
 *
 * Son las dos tablas que el ADR-0016 puso del lado de la plataforma. No
 * llevan {@code empresa_id} y no lo llevan por diseño: una tarifa que
 * dependiera del comercio no sería la tarifa del país.
 *
 * Como {@link RepositorioPlataforma}, va con sentencias explícitas en
 * lugar de JPA. Aquí el motivo es más simple: lo único que se escribe
 * son dos o tres columnas de una fila que ya existe, y una entidad
 * mapeada daría además la posibilidad de cambiar el código o el
 * tratamiento, que es justo lo que no se quiere poder hacer desde esta
 * pantalla.
 */
@Repository
public class RepositorioTributario {

    private final JdbcClient jdbc;

    public RepositorioTributario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ UVT

    public List<ValorUvt> listarUvt() {
        return jdbc.sql("SELECT anio, valor, resolucion FROM uvt_anual ORDER BY anio DESC")
                .query(ValorUvt.class)
                .list();
    }

    public Optional<ValorUvt> buscarUvt(Integer anio) {
        return jdbc.sql("SELECT anio, valor, resolucion FROM uvt_anual WHERE anio = :anio")
                .param("anio", anio)
                .query(ValorUvt.class)
                .optional();
    }

    public void insertarUvt(Integer anio, BigDecimal valor, String resolucion) {
        jdbc.sql("INSERT INTO uvt_anual (anio, valor, resolucion) "
                        + "VALUES (:anio, :valor, :resolucion)")
                .param("anio", anio)
                .param("valor", valor)
                .param("resolucion", resolucion)
                .update();
    }

    public void actualizarUvt(Integer anio, BigDecimal valor, String resolucion) {
        jdbc.sql("UPDATE uvt_anual SET valor = :valor, resolucion = :resolucion "
                        + "WHERE anio = :anio")
                .param("valor", valor)
                .param("resolucion", resolucion)
                .param("anio", anio)
                .update();
    }

    // -------------------------------------------------------------- impuestos

    private static final String SELECCION_IMPUESTO = """
            SELECT tipo_impuesto_id, codigo, nombre, tratamiento, tarifa,
                   umbral_uvt, fundamento, vigente_desde, vigente_hasta, activo
              FROM tipos_impuesto
            """;

    public List<TipoImpuestoNacional> listarImpuestos() {
        return jdbc.sql(SELECCION_IMPUESTO + " ORDER BY codigo")
                .query(TipoImpuestoNacional.class)
                .list();
    }

    public Optional<TipoImpuestoNacional> buscarImpuesto(String codigo) {
        return jdbc.sql(SELECCION_IMPUESTO + " WHERE codigo = :codigo")
                .param("codigo", codigo)
                .query(TipoImpuestoNacional.class)
                .optional();
    }

    /**
     * Cambia solo lo que el RF-05 permite cambiar. El código y el
     * tratamiento no aparecen en el SET, de modo que no hay forma de
     * tocarlos por esta vía ni equivocándose.
     */
    public void actualizarImpuesto(String codigo, BigDecimal tarifa,
                                   BigDecimal umbralUvt, String fundamento) {
        jdbc.sql("""
                        UPDATE tipos_impuesto
                           SET tarifa     = :tarifa,
                               umbral_uvt = :umbral,
                               fundamento = :fundamento
                         WHERE codigo = :codigo
                        """)
                .param("tarifa", tarifa)
                .param("umbral", umbralUvt)
                .param("fundamento", fundamento)
                .param("codigo", codigo)
                .update();
    }
}
