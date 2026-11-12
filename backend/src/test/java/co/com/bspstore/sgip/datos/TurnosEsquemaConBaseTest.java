package co.com.bspstore.sgip.datos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El esquema del turno de caja (V43, RF-45): la base de efectivo con que se abre y
 * la unicidad del turno abierto por cajero, que la garantiza el motor y no una
 * lectura previa de la aplicación.
 *
 * Solo toca el esquema con {@code JdbcClient}, sin ninguna clase de producción de
 * la tarea: así compila antes de que exista y la CI lo ve fallar por la aserción.
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa y sus usuarios, y los
 * borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class TurnosEsquemaConBaseTest {

    private static final LocalDateTime APERTURA = LocalDateTime.of(2026, 11, 12, 8, 0);

    @Autowired JdbcClient jdbc;
    @Autowired RepositorioPlataforma plataforma;

    private Integer empresaId;
    private Integer otraEmpresaId;
    private Integer cajeroId;
    private Integer otroCajeroId;
    private Integer cajeroDeOtraEmpresaId;

    @BeforeEach
    void prepararDatosPropios() {
        String marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("T" + marca, "Prueba turnos " + marca, null, null, null);
        otraEmpresaId = plataforma.insertarEmpresa("U" + marca, "Otra prueba turnos " + marca, null, null, null);
        cajeroId = usuario(empresaId, "a-" + marca);
        otroCajeroId = usuario(empresaId, "b-" + marca);
        cajeroDeOtraEmpresaId = usuario(otraEmpresaId, "c-" + marca);
    }

    @AfterEach
    void borrarLoQueCreo() {
        for (Integer empresa : new Integer[]{empresaId, otraEmpresaId}) {
            if (empresa == null) {
                continue;
            }
            jdbc.sql("DELETE FROM cierres_caja WHERE empresa_id = :empresa").param("empresa", empresa).update();
            jdbc.sql("DELETE FROM usuarios WHERE empresa_id = :empresa").param("empresa", empresa).update();
            jdbc.sql("DELETE FROM empresas WHERE empresa_id = :empresa").param("empresa", empresa).update();
        }
    }

    private Integer usuario(Integer empresa, String correo) {
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, cambio_clave_req)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'VENDEDOR'),
                        'Cajero', :correo, 'no-sirve-para-ingresar', 0)
                """)
                .param("empresa", empresa)
                .param("correo", correo + "@ejemplo.co")
                .update();
        return jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correo + "@ejemplo.co").query(Integer.class).single();
    }

    private void abrir(Integer empresa, Integer cajero) {
        jdbc.sql("INSERT INTO cierres_caja (empresa_id, usuario_id, apertura) VALUES (:empresa, :cajero, :apertura)")
                .param("empresa", empresa).param("cajero", cajero).param("apertura", APERTURA).update();
    }

    private void cerrarLosDe(Integer cajero) {
        jdbc.sql("UPDATE cierres_caja SET cierre = :cierre WHERE usuario_id = :cajero AND cierre IS NULL")
                .param("cierre", APERTURA.plusHours(8)).param("cajero", cajero).update();
    }

    private int abiertosDe(Integer cajero) {
        return jdbc.sql("SELECT COUNT(*) FROM cierres_caja WHERE usuario_id = :cajero AND cierre IS NULL")
                .param("cajero", cajero).query(Integer.class).single();
    }

    @Test
    void laMigracionV43SeAplico() {
        Boolean exito = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '43'")
                .query(Boolean.class).optional().orElse(null);

        assertEquals(Boolean.TRUE, exito, "Flyway debe haber aplicado la V43");
    }

    @Test
    void cierresCajaGanaLaBaseDeEfectivoQueNaceEnCero() {
        Map<String, Object> columna = jdbc.sql("""
                SELECT column_type AS tipo, is_nullable AS nulo, column_default AS defecto
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'cierres_caja'
                   AND column_name = 'base_efectivo'
                """)
                .query().singleRow();

        assertEquals("decimal(14,2)", columna.get("tipo"));
        assertEquals("NO", columna.get("nulo"));
        assertEquals("0", columna.get("defecto"));

        abrir(empresaId, cajeroId);
        BigDecimal base = jdbc.sql("SELECT base_efectivo FROM cierres_caja WHERE usuario_id = :cajero")
                .param("cajero", cajeroId).query(BigDecimal.class).single();
        assertEquals(0, base.compareTo(BigDecimal.ZERO));
    }

    @Test
    void elMotorRechazaUnaBaseNegativa() {
        DataAccessException e = assertThrows(DataAccessException.class, () ->
                jdbc.sql("""
                        INSERT INTO cierres_caja (empresa_id, usuario_id, apertura, base_efectivo)
                        VALUES (:empresa, :cajero, :apertura, -1)
                        """)
                        .param("empresa", empresaId).param("cajero", cajeroId)
                        .param("apertura", APERTURA).update());

        assertTrue(String.valueOf(e.getMessage()).contains("ck_cierre_base"),
                "debe fallar por la restricción de la base: " + e.getMessage());
    }

    @Test
    void elMotorNoAdmiteDosTurnosAbiertosDelMismoCajero() {
        abrir(empresaId, cajeroId);

        DuplicateKeyException e = assertThrows(DuplicateKeyException.class, () -> abrir(empresaId, cajeroId));

        assertTrue(String.valueOf(e.getMessage()).contains("uq_cierres_abierto"),
                "debe chocar con la unicidad del turno abierto: " + e.getMessage());
        assertEquals(1, abiertosDe(cajeroId));
    }

    @Test
    void otroCajeroDeLaMismaEmpresaSiPuedeTenerSuTurnoAbierto() {
        abrir(empresaId, cajeroId);
        abrir(empresaId, otroCajeroId);

        assertEquals(1, abiertosDe(cajeroId));
        assertEquals(1, abiertosDe(otroCajeroId));
    }

    @Test
    void despuesDeCerrarSePuedeAbrirOtroYSeConservanLosCerrados() {
        abrir(empresaId, cajeroId);
        cerrarLosDe(cajeroId);
        abrir(empresaId, cajeroId);
        cerrarLosDe(cajeroId);
        abrir(empresaId, cajeroId);

        assertEquals(1, abiertosDe(cajeroId));
        int todos = jdbc.sql("SELECT COUNT(*) FROM cierres_caja WHERE usuario_id = :cajero")
                .param("cajero", cajeroId).query(Integer.class).single();
        assertEquals(3, todos, "los turnos cerrados no estorban a la unicidad y siguen ahí");
    }

    @Test
    void laUnicidadEsPorEmpresaYElTurnoDeUnaNoEstorbaALaOtra() {
        abrir(empresaId, cajeroId);
        abrir(otraEmpresaId, cajeroDeOtraEmpresaId);

        assertEquals(1, abiertosDe(cajeroId));
        assertEquals(1, abiertosDe(cajeroDeOtraEmpresaId));
    }
}
