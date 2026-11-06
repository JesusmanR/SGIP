package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.CredencialesInvalidasException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El bloqueo caduca y el super administrador puede levantarlo (I2, ADR-0028),
 * contra el esquema real: la V37 añade la columna y la acción de la bitácora.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa y usuarios, y los
 * borra todos al terminar.
 */
@Tag("conBase")
@SpringBootTest
class BloqueoDeCuentaConBaseTest {

    private static final String CLAVE = "SgipDev2026";

    @Autowired ServicioAutenticacion autenticacion;
    @Autowired ServicioPlataforma servicioPlataforma;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @Autowired UsuarioRepositorio usuarios;

    private String marca;
    private Integer empresaId;
    private Integer superAdminId;
    private Integer usuarioId;
    private String correo;

    @BeforeEach
    void prepararDatosPropios() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("B" + marca, "Prueba bloqueo " + marca,
                null, null, null);
        correo = "bloqueo-" + marca.toLowerCase() + "@ejemplo.co";
        usuarioId = insertarUsuario(empresaId, "ADMINISTRADOR", correo);
        superAdminId = insertarUsuario(null, "SUPER_ADMIN",
                "plataforma-" + marca.toLowerCase() + "@ejemplo.co");
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        if (empresaId == null) {
            return;
        }
        // La bitácora de la plataforma apunta a la empresa y al usuario con
        // RESTRICT: va primero.
        jdbc.sql("DELETE FROM auditoria_plataforma WHERE empresa_id = :empresa")
                .param("empresa", empresaId).update();
        jdbc.sql("DELETE FROM auditoria_accesos WHERE correo LIKE :patron")
                .param("patron", "%" + marca.toLowerCase() + "@ejemplo.co").update();
        jdbc.sql("DELETE FROM usuarios WHERE correo LIKE :patron")
                .param("patron", "%" + marca.toLowerCase() + "@ejemplo.co").update();
        jdbc.sql("DELETE FROM empresas WHERE empresa_id = :empresa")
                .param("empresa", empresaId).update();
    }

    private Integer insertarUsuario(Integer empresa, String rol, String correoUsuario) {
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, cambio_clave_req)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = :rol),
                        'Prueba', :correo, :hash, 0)
                """)
                .param("empresa", empresa)
                .param("rol", rol)
                .param("correo", correoUsuario)
                .param("hash", Seguridad.derivar(CLAVE))
                .update();
        return jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correoUsuario).query(Integer.class).single();
    }

    private void comoSuperAdministrador() {
        var u = new UsuarioAutenticado(superAdminId, "plataforma@ejemplo.co", "SUPER_ADMIN", null);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private boolean estaBloqueado() {
        return jdbc.sql("SELECT bloqueado FROM usuarios WHERE usuario_id = :id")
                .param("id", usuarioId).query(Boolean.class).single();
    }

    private LocalDateTime bloqueadoEn() {
        return jdbc.sql("SELECT bloqueado_en FROM usuarios WHERE usuario_id = :id")
                .param("id", usuarioId).query(LocalDateTime.class).optional().orElse(null);
    }

    private int intentos() {
        return jdbc.sql("SELECT intentos_fallidos FROM usuarios WHERE usuario_id = :id")
                .param("id", usuarioId).query(Integer.class).single();
    }

    private void bloquearHace(int minutos) {
        // Se bloquea por la entidad, el mismo camino que usa la aplicación. Un
        // UPDATE con JdbcClient guarda el LocalDateTime tal cual, y Hibernate lo
        // lee convertido de la zona de la conexión a la de la JVM: en la CI, con
        // la JVM en UTC y la conexión en Bogotá, el bloqueo parecía cinco horas
        // más reciente.
        Usuario usuario = usuarios.findById(usuarioId).orElseThrow();
        LocalDateTime hace = LocalDateTime.now().minusMinutes(minutos);
        for (int i = 0; i < 5; i++) {
            usuario.registrarIntentoFallido(5, hace);
        }
        usuarios.save(usuario);
    }

    @Test
    void cincoErroresBloqueanYGuardanElInstante() {
        for (int i = 0; i < 5; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> autenticacion.autenticar(correo, "incorrecta", "prueba"));
        }

        assertTrue(estaBloqueado());
        assertNotNull(bloqueadoEn(), "el bloqueo guarda cuándo ocurrió");
    }

    @Test
    void unBloqueoVencidoSeLevantaAlIngresarConLaClaveCorrecta() {
        bloquearHace(16);

        autenticacion.autenticar(correo, CLAVE, "prueba");

        assertTrue(!estaBloqueado());
        assertNull(bloqueadoEn());
        assertEquals(0, intentos());
    }

    @Test
    void unBloqueoRecienteSigueRechazandoLaClaveCorrecta() {
        bloquearHace(5);

        assertThrows(CredencialesInvalidasException.class,
                () -> autenticacion.autenticar(correo, CLAVE, "prueba"));

        assertTrue(estaBloqueado());
    }

    @Test
    void elSuperAdministradorLevantaElBloqueoYQuedaEnLaBitacora() {
        bloquearHace(1);
        comoSuperAdministrador();

        servicioPlataforma.desbloquearUsuario(empresaId, usuarioId);

        assertTrue(!estaBloqueado());
        assertNull(bloqueadoEn());
        assertEquals(0, intentos());
        var fila = jdbc.sql("""
                SELECT usuario_id, accion, entidad, referencia, valor_anterior, valor_nuevo
                  FROM auditoria_plataforma WHERE empresa_id = :empresa
                """)
                .param("empresa", empresaId)
                .query((rs, n) -> List.of(rs.getInt("usuario_id"), rs.getString("accion"),
                        rs.getString("entidad"), rs.getString("referencia"),
                        rs.getString("valor_anterior"), rs.getString("valor_nuevo")))
                .single();
        assertEquals(List.of(superAdminId, "USUARIO_DESBLOQUEO", "usuarios",
                String.valueOf(usuarioId), "BLOQUEADO", "DESBLOQUEADO"), fila);
    }

    @Test
    void unUsuarioDeOtraEmpresaNoSePuedeDesbloquearIndicandoLaAjena() {
        bloquearHace(1);
        comoSuperAdministrador();

        assertThrows(RecursoNoEncontradoException.class,
                () -> servicioPlataforma.desbloquearUsuario(empresaId + 1_000_000, usuarioId));

        assertTrue(estaBloqueado());
    }
}
