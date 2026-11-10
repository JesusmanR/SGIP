package co.com.bspstore.sgip.datos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El esquema que guarda el PIN del administrador (V42, ADR-0032): qué columnas
 * añade a {@code usuarios}, con qué valores nace cada usuario y qué rechaza el
 * propio motor aunque la aplicación se equivoque.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa y sus usuarios, y los
 * borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class PinEsquemaConBaseTest {

    @Autowired JdbcClient jdbc;
    @Autowired RepositorioPlataforma plataforma;

    private String marca;
    private Integer empresaId;
    private Integer usuarioId;

    @BeforeEach
    void prepararDatosPropios() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("P" + marca, "Prueba PIN " + marca, null, null, null);
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, cambio_clave_req)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, 'no-sirve-para-ingresar', 0)
                """)
                .param("empresa", empresaId)
                .param("correo", "pin-" + marca.toLowerCase() + "@ejemplo.co")
                .update();
        usuarioId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE empresa_id = :empresa")
                .param("empresa", empresaId).query(Integer.class).single();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresaId == null) {
            return;
        }
        jdbc.sql("DELETE FROM usuarios WHERE empresa_id = :empresa").param("empresa", empresaId).update();
        jdbc.sql("DELETE FROM empresas WHERE empresa_id = :empresa").param("empresa", empresaId).update();
    }

    private Map<String, Object> columna(String nombre) {
        return jdbc.sql("""
                SELECT column_type AS tipo, is_nullable AS nulo, column_default AS defecto
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'usuarios' AND column_name = :nombre
                """)
                .param("nombre", nombre)
                .query().singleRow();
    }

    @Test
    void laMigracionV42SeAplico() {
        Boolean exito = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '42'")
                .query(Boolean.class).optional().orElse(null);

        assertEquals(Boolean.TRUE, exito, "Flyway debe haber aplicado la V42");
    }

    @Test
    void usuariosGanaLasCincoColumnasDelPin() {
        // El PIN derivado: nulo mientras el administrador no lo ha puesto.
        assertEquals("varchar(255)", columna("pin_hash").get("tipo"));
        assertEquals("YES", columna("pin_hash").get("nulo"));

        // Los fallos contra el PIN de este administrador.
        assertEquals("tinyint unsigned", columna("pin_intentos").get("tipo"));
        assertEquals("NO", columna("pin_intentos").get("nulo"));
        assertEquals("0", columna("pin_intentos").get("defecto"));
        assertEquals("datetime", columna("pin_bloqueado_en").get("tipo"));
        assertEquals("YES", columna("pin_bloqueado_en").get("nulo"));

        // Los fallos de este usuario contra un objetivo que no vale.
        assertEquals("tinyint unsigned", columna("pin_errados_emitidos").get("tipo"));
        assertEquals("NO", columna("pin_errados_emitidos").get("nulo"));
        assertEquals("0", columna("pin_errados_emitidos").get("defecto"));
        assertEquals("datetime", columna("pin_emision_bloqueada_en").get("tipo"));
        assertEquals("YES", columna("pin_emision_bloqueada_en").get("nulo"));
    }

    @Test
    void unUsuarioNuevoNaceSinPinYConLosContadoresEnCero() {
        Map<String, Object> fila = jdbc.sql("""
                SELECT pin_hash, pin_intentos, pin_bloqueado_en,
                       pin_errados_emitidos, pin_emision_bloqueada_en
                  FROM usuarios WHERE usuario_id = :id
                """)
                .param("id", usuarioId).query().singleRow();

        assertNull(fila.get("pin_hash"));
        assertEquals(0, ((Number) fila.get("pin_intentos")).intValue());
        assertNull(fila.get("pin_bloqueado_en"));
        assertEquals(0, ((Number) fila.get("pin_errados_emitidos")).intValue());
        assertNull(fila.get("pin_emision_bloqueada_en"));
    }

    @Test
    void sinPinNoHayPinBloqueado() {
        // El bloqueo del PIN solo tiene sentido si hay un PIN que bloquear.
        DataAccessException e = assertThrows(DataAccessException.class, () ->
                jdbc.sql("UPDATE usuarios SET pin_bloqueado_en = :cuando WHERE usuario_id = :id")
                        .param("cuando", LocalDateTime.of(2026, 11, 9, 10, 0))
                        .param("id", usuarioId).update());

        assertTrue(String.valueOf(e.getMessage()).contains("ck_usuarios_pin_bloqueo"),
                "debe fallar por la restricción del bloqueo: " + e.getMessage());
    }

    @Test
    void conPinElBloqueoSiSeAdmite() {
        jdbc.sql("UPDATE usuarios SET pin_hash = 'pbkdf2$1$a$b' WHERE usuario_id = :id")
                .param("id", usuarioId).update();

        int filas = jdbc.sql("UPDATE usuarios SET pin_bloqueado_en = :cuando WHERE usuario_id = :id")
                .param("cuando", LocalDateTime.of(2026, 11, 9, 10, 0))
                .param("id", usuarioId).update();

        assertEquals(1, filas);
    }

    @Test
    void losContadoresDelPinTienenTope() {
        // Como intentos_fallidos (ck_usuarios_intentos): 0 a 50.
        for (String columna : List.of("pin_intentos", "pin_errados_emitidos")) {
            DataAccessException e = assertThrows(DataAccessException.class, () ->
                    jdbc.sql("UPDATE usuarios SET " + columna + " = 51 WHERE usuario_id = :id")
                            .param("id", usuarioId).update());

            assertTrue(String.valueOf(e.getMessage()).contains("ck_usuarios_pin_intentos"),
                    columna + " debe fallar por la restricción del tope: " + e.getMessage());
        }
        assertEquals(1, jdbc.sql("UPDATE usuarios SET pin_intentos = 50, pin_errados_emitidos = 50 "
                + "WHERE usuario_id = :id").param("id", usuarioId).update());
    }
}
