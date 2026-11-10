package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.PoliticaDeBloqueo;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los clientes de la caja (RF-66) contra el esquema real: el «Consumidor final»
 * de cada empresa.
 *
 * Tres frentes:
 *
 *   1. La migración V41. Flyway ya la aplicó al arrancar el contexto, así que
 *      «al migrar» no se puede ver sobre las tablas reales: se arma el estado de
 *      antes (empresas con y sin cliente genérico, con el documento genérico en
 *      un cliente inactivo, con un NIT que parece el genérico) en un esquema
 *      desechable y se ejecuta el guion que vive en {@code db/migration}. Que el
 *      guion corre sobre las tablas de verdad lo demuestra Flyway al arrancar.
 *   2. La base real, la que dejó Flyway.
 *   3. El alta de una empresa, con el {@code darDeAlta} de verdad.
 *
 * Crea su esquema, su super administrador y sus empresas, y lo borra todo al
 * terminar, también si algo falla.
 */
@Tag("conBase")
@SpringBootTest
class ClientesConBaseTest {

    private static final String MIGRACION = "db/migration/V41__consumidor_final_y_datos_de_cliente.sql";

    private static final String GENERICO = "222222222222";

    private static final int SIN_CLIENTES = 1;
    private static final int CON_EL_GENERICO = 2;
    private static final int CON_OTROS = 3;

    @Autowired DataSource origen;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired UsuarioRepositorio usuarios;
    @Autowired RolRepositorio roles;
    @Autowired JdbcClient jdbc;

    private String marca;
    private String esquema;
    private Integer superAdminId;

    private record Consumidor(Integer id, String nombre, String tipo, String identificacion,
                               String ciudad, boolean activo, boolean consumidorFinal) {}

    @BeforeEach
    void prepararSuperAdministrador() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        esquema = "v41_" + marca;
        String correo = "plataforma-" + marca + "@ejemplo.co";
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (NULL, (SELECT rol_id FROM roles WHERE nombre = 'SUPER_ADMIN'),
                        'Plataforma', :correo, 'no-sirve-para-ingresar')
                """)
                .param("correo", correo)
                .update();
        superAdminId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correo).query(Integer.class).single();
        var u = new UsuarioAutenticado(superAdminId, correo, "SUPER_ADMIN", null);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        jdbc.sql("DROP DATABASE IF EXISTS " + esquema).update();
        jdbc.sql("DELETE FROM clientes WHERE nit = :nit AND empresa_id = 1").param("nit", "X" + marca).update();
        List<Integer> empresas = jdbc.sql("SELECT empresa_id FROM empresas WHERE nit = :nit")
                .param("nit", nit()).query(Integer.class).list();
        // De las hojas a la raíz: lo sembrado cuelga de la empresa.
        for (Integer empresa : empresas) {
            for (String tabla : List.of("auditoria_plataforma", "clientes", "medios_pago", "empresa_config",
                    "suscripciones", "usuarios", "empresas")) {
                jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                        .param("empresa", empresa).update();
            }
        }
        jdbc.sql("DELETE FROM usuarios WHERE usuario_id = :id").param("id", superAdminId).update();
    }

    private String nit() {
        return "K" + marca.toUpperCase();
    }

    private static void exigirLaMigracion() {
        assertNotNull(ClientesConBaseTest.class.getClassLoader().getResource(MIGRACION),
                "falta la migración " + MIGRACION);
    }

    // ------------------------------------------------------------ la migración

    @Test
    void laMigracionSiembraElConsumidorFinalEnLaEmpresaSinClientes() throws Exception {
        migrarElEstadoDeAntes();

        List<Consumidor> propios = consumidoresDe(SIN_CLIENTES);
        assertEquals(1, propios.size());
        Consumidor c = propios.get(0);
        assertEquals("Consumidor final", c.nombre());
        assertEquals("CC", c.tipo());
        assertEquals(GENERICO, c.identificacion());
        assertEquals("Cali", c.ciudad(), "la ciudad sale de la empresa");
        assertTrue(c.activo());
        assertTrue(c.consumidorFinal());
    }

    @Test
    void laMigracionMarcaElClienteConElDocumentoGenerico() throws Exception {
        Integer original = migrarElEstadoDeAntes();

        List<Consumidor> propios = consumidoresDe(CON_EL_GENERICO);
        assertEquals(1, propios.size(), "se marca el que había: no se inserta un segundo");
        Consumidor c = propios.get(0);
        assertEquals(original, c.id(), "es el mismo cliente, no uno nuevo");
        assertEquals("Consumidor final", c.nombre());
        assertTrue(c.activo(), "el consumidor final siempre está activo");
        assertEquals(1, cuantosClientes(CON_EL_GENERICO), "sin duplicados");
        assertEquals("mostrador@tienda.co", correoDe(original), "lo demás del cliente se conserva");
    }

    @Test
    void laMigracionNoTocaAlRestoDeLosClientesNiConfundeUnNitConElDocumentoGenerico() throws Exception {
        migrarElEstadoDeAntes();

        // Ana, el NIT que se parece al genérico y el consumidor final recién sembrado.
        assertEquals(3, cuantosClientes(CON_OTROS));
        assertEquals(1, consumidoresDe(CON_OTROS).size());
        assertEquals("CC", consumidoresDe(CON_OTROS).get(0).tipo());
        try (Escritorio e = escritorio()) {
            assertEquals(0, e.jdbc().sql("""
                            SELECT COUNT(*) FROM clientes
                             WHERE empresa_id = :e AND tipo_documento = 'NIT' AND consumidor_final = 1
                            """).param("e", CON_OTROS).query(Integer.class).single());
        }
    }

    @Test
    void cadaEmpresaQuedaConUnSoloConsumidorFinalYNoSePuedeTenerOtro() throws Exception {
        migrarElEstadoDeAntes();

        for (int empresa : List.of(SIN_CLIENTES, CON_EL_GENERICO, CON_OTROS)) {
            assertEquals(1, consumidoresDe(empresa).size(), "empresa " + empresa);
        }
        try (Escritorio e = escritorio()) {
            // Marcar a otro cliente de la empresa: el marcado ha de ser el del documento genérico.
            assertThrows(DataIntegrityViolationException.class, () -> e.jdbc().sql("""
                    UPDATE clientes SET consumidor_final = 1
                     WHERE empresa_id = :e AND nit = '1000'
                    """).param("e", CON_OTROS).update());
            // Y un segundo con el documento genérico choca con la clave del documento.
            assertThrows(DataIntegrityViolationException.class, () -> e.jdbc().sql("""
                    INSERT INTO clientes (empresa_id, razon_social, tipo_documento, nit, consumidor_final)
                    VALUES (:e, 'Otro', 'CC', '222222222222', 1)
                    """).param("e", CON_OTROS).update());
        }
    }

    // ------------------------------------------------------------- la base real

    @Test
    void laBaseRealTieneUnConsumidorFinalEnLaEmpresaDeDemostracion() {
        exigirLaMigracion();

        List<Consumidor> propios = jdbc.sql("""
                SELECT cliente_id AS id, razon_social AS nombre, tipo_documento AS tipo,
                       nit AS identificacion, ciudad, activo, consumidor_final AS consumidorFinal
                  FROM clientes WHERE empresa_id = 1 AND consumidor_final = 1
                """).query(Consumidor.class).list();
        assertEquals(1, propios.size());
        assertEquals("Consumidor final", propios.get(0).nombre());
        assertEquals(GENERICO, propios.get(0).identificacion());
        // Ninguna empresa de la base tiene más de uno.
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM (SELECT empresa_id FROM clientes WHERE consumidor_final = 1
                                       GROUP BY empresa_id HAVING COUNT(*) > 1) x
                """).query(Integer.class).single());
    }

    @Test
    void laBaseRealNoAdmiteUnSegundoConsumidorFinalEnLaMismaEmpresa() {
        exigirLaMigracion();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO clientes (empresa_id, razon_social, tipo_documento, nit, consumidor_final)
                VALUES (1, 'Otro genérico', 'CC', :nit, 1)
                """).param("nit", "X" + marca).update());
    }

    // ------------------------------------------------------------------- el alta

    @Test
    void elAltaSiembraElConsumidorFinalConLaCiudadDeLaEmpresa() {
        exigirLaMigracion();
        darDeAlta("Cúcuta");

        List<Consumidor> propios = jdbc.sql("""
                SELECT c.cliente_id AS id, c.razon_social AS nombre, c.tipo_documento AS tipo,
                       c.nit AS identificacion, c.ciudad, c.activo, c.consumidor_final AS consumidorFinal
                  FROM clientes c JOIN empresas e ON e.empresa_id = c.empresa_id
                 WHERE e.nit = :nit
                """).param("nit", nit()).query(Consumidor.class).list();

        assertEquals(1, propios.size(), "el alta deja exactamente un cliente");
        Consumidor c = propios.get(0);
        assertEquals("Consumidor final", c.nombre());
        assertEquals("CC", c.tipo());
        assertEquals(GENERICO, c.identificacion());
        assertEquals("Cúcuta", c.ciudad());
        assertTrue(c.activo());
        assertTrue(c.consumidorFinal());
    }

    @Test
    void elAltaSinCiudadSiembraElConsumidorFinalSinCiudad() {
        exigirLaMigracion();
        darDeAlta(null);

        List<String> ciudades = jdbc.sql("""
                SELECT c.ciudad FROM clientes c JOIN empresas e ON e.empresa_id = c.empresa_id
                 WHERE e.nit = :nit AND c.consumidor_final = 1
                """).param("nit", nit()).query((rs, n) -> rs.getString(1)).list();
        assertEquals(1, ciudades.size(), "el consumidor final se siembra aunque no haya ciudad");
        assertEquals(null, ciudades.get(0));
    }

    private void darDeAlta(String ciudad) {
        new ServicioPlataforma(plataforma, usuarios, roles, Clock.systemUTC(), new PoliticaDeBloqueo(15))
                .darDeAlta(new SolicitudAltaEmpresa(nit(), "Clientes " + marca, null, null, ciudad,
                        null, "Administración", "admin-" + marca + "@ejemplo.co", "ClaveDePrueba2026"));
    }

    // ------------------------------------------- el esquema desechable de la V41

    private List<Consumidor> consumidoresDe(int empresa) throws Exception {
        try (Escritorio e = escritorio()) {
            return e.jdbc().sql("""
                    SELECT cliente_id AS id, razon_social AS nombre, tipo_documento AS tipo,
                           nit AS identificacion, ciudad, activo, consumidor_final AS consumidorFinal
                      FROM clientes WHERE empresa_id = :e AND consumidor_final = 1
                    """).param("e", empresa).query(Consumidor.class).list();
        }
    }

    private int cuantosClientes(int empresa) throws Exception {
        try (Escritorio e = escritorio()) {
            return e.jdbc().sql("SELECT COUNT(*) FROM clientes WHERE empresa_id = :e")
                    .param("e", empresa).query(Integer.class).single();
        }
    }

    private String correoDe(Integer cliente) throws Exception {
        try (Escritorio e = escritorio()) {
            return e.jdbc().sql("SELECT correo FROM clientes WHERE cliente_id = :c")
                    .param("c", cliente).query(String.class).single();
        }
    }

    /**
     * Una conexión apuntada al esquema desechable. Al cerrarla vuelve al esquema
     * de la aplicación, para que la conexión que regresa al pool no quede
     * apuntando a otra parte.
     */
    private Escritorio escritorio() throws SQLException {
        return new Escritorio(origen.getConnection(), esquema);
    }

    private static final class Escritorio implements AutoCloseable {
        private final Connection conexion;
        private final String esquemaOriginal;
        private final JdbcClient jdbc;

        Escritorio(Connection conexion, String esquema) throws SQLException {
            this.conexion = conexion;
            this.esquemaOriginal = conexion.getCatalog();
            conexion.setCatalog(esquema);
            this.jdbc = JdbcClient.create(new SingleConnectionDataSource(conexion, true));
        }

        JdbcClient jdbc() {
            return jdbc;
        }

        Connection conexion() {
            return conexion;
        }

        @Override
        public void close() throws SQLException {
            conexion.setCatalog(esquemaOriginal);
            conexion.close();
        }
    }

    /**
     * Arma el estado que había justo antes de la V41 y ejecuta el guion tal como
     * está en el classpath. Devuelve el identificador del cliente genérico que ya
     * existía en la empresa 2.
     *
     *   empresa 1  sin clientes, en Cali
     *   empresa 2  con un cliente CC 222222222222 llamado «Mostrador», inactivo
     *   empresa 3  con a Ana (CC 1000) y una empresa con NIT 222222222222
     */
    private Integer migrarElEstadoDeAntes() throws Exception {
        jdbc.sql("CREATE DATABASE " + esquema).update();
        try (Escritorio e = escritorio()) {
            JdbcClient bd = e.jdbc();
            bd.sql("""
                    CREATE TABLE empresas (
                      empresa_id INT NOT NULL PRIMARY KEY,
                      nit        VARCHAR(20) NOT NULL,
                      ciudad     VARCHAR(80) NULL
                    ) ENGINE=InnoDB
                    """).update();
            // Los clientes como los dejó la V31: la clave del documento incluye la empresa.
            bd.sql("""
                    CREATE TABLE clientes (
                      cliente_id          INT           NOT NULL AUTO_INCREMENT,
                      empresa_id          INT           NOT NULL,
                      razon_social        VARCHAR(160)  NOT NULL,
                      tipo_documento      ENUM('NIT','CC','CE','PASAPORTE') NOT NULL DEFAULT 'NIT',
                      nit                 VARCHAR(20)   NOT NULL,
                      correo              VARCHAR(120)  NULL,
                      telefono            VARCHAR(20)   NULL,
                      direccion           VARCHAR(200)  NULL,
                      ciudad              VARCHAR(80)   NULL,
                      autorizacion_datos  DATETIME      NULL,
                      medio_autorizacion  VARCHAR(60)   NULL,
                      activo              BOOL          NOT NULL DEFAULT 1,
                      creado_en           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT pk_clientes PRIMARY KEY (cliente_id),
                      CONSTRAINT uq_clientes_documento UNIQUE (empresa_id, tipo_documento, nit),
                      CONSTRAINT uq_clientes_empresa UNIQUE (empresa_id, cliente_id),
                      CONSTRAINT ck_clientes_autorizacion CHECK (
                        autorizacion_datos IS NULL OR medio_autorizacion IS NOT NULL)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """).update();
            bd.sql("""
                    INSERT INTO empresas (empresa_id, nit, ciudad) VALUES
                      (1, 'E1', 'Cali'), (2, 'E2', NULL), (3, 'E3', 'Bogotá')
                    """).update();
            bd.sql("""
                    INSERT INTO clientes (empresa_id, razon_social, tipo_documento, nit, correo, activo) VALUES
                      (2, 'Mostrador', 'CC', '222222222222', 'mostrador@tienda.co', 0),
                      (3, 'Ana', 'CC', '1000', NULL, 1),
                      (3, 'Empresa X', 'NIT', '222222222222', NULL, 1)
                    """).update();
            Integer original = bd.sql("SELECT cliente_id FROM clientes WHERE empresa_id = 2")
                    .query(Integer.class).single();

            var recurso = new ClassPathResource(MIGRACION);
            assertTrue(recurso.exists(), "falta la migración " + MIGRACION);
            ScriptUtils.executeSqlScript(e.conexion(), new EncodedResource(recurso, StandardCharsets.UTF_8));
            return original;
        }
    }
}
