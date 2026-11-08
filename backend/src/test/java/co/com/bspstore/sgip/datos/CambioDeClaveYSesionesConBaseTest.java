package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.AutenticacionControlador;
import co.com.bspstore.sgip.api.dto.SolicitudCambioClave;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * El cambio de contraseña y el cierre de las demás sesiones son una sola unidad
 * (M16). Si cerrar las sesiones falla, la contraseña no cambia: de otro modo
 * quedaría cambiada con las sesiones de quien la tuviera abiertas, que es justo lo
 * que el cierre quería impedir.
 *
 * Pasa por el controlador y no por el servicio: es el punto de entrada real y
 * conserva la misma firma antes y después de unir las dos transacciones.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa, usuario y sesiones y los
 * borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CambioDeClaveYSesionesConBaseTest {

    private static final String CLAVE = "SgipDev2026";
    private static final String NUEVA = "NuevaClave2026";
    private static final String TOKEN_ACTUAL = "token-actual-de-prueba";
    private static final String TOKEN_OTRO = "token-de-otro-dispositivo";

    @Autowired AutenticacionControlador controlador;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean ServicioSesion sesiones;

    private Integer empresaId;
    private Integer usuarioId;
    private String marca;

    @BeforeEach
    void prepararDatosPropios() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("K" + marca, "Prueba clave " + marca, null, null, null);
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, :hash)
                """)
                .param("empresa", empresaId)
                .param("correo", "clave-" + marca.toLowerCase() + "@ejemplo.co")
                .param("hash", Seguridad.derivar(CLAVE))
                .update();
        usuarioId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE empresa_id = :empresa")
                .param("empresa", empresaId).query(Integer.class).single();
        abrirSesion(TOKEN_ACTUAL + marca);
        abrirSesion(TOKEN_OTRO + marca);

        var quien = new UsuarioAutenticado(usuarioId, "clave-" + marca.toLowerCase() + "@ejemplo.co",
                "ADMINISTRADOR", empresaId, false);
        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(new UsernamePasswordAuthenticationToken(quien, null, List.of()));
        SecurityContextHolder.setContext(contexto);
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        if (empresaId == null) {
            return;
        }
        jdbc.sql("DELETE FROM auditoria_accesos WHERE correo LIKE :patron")
                .param("patron", "clave-" + marca.toLowerCase() + "@%").update();
        // Las sesiones se borran con su usuario (ON DELETE CASCADE).
        for (String tabla : List.of("usuarios", "empresas")) {
            jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                    .param("empresa", empresaId).update();
        }
    }

    private void abrirSesion(String token) {
        jdbc.sql("""
                INSERT INTO sesiones (usuario_id, token_hash, inicio, ultima_actividad, expira)
                VALUES (:usuario, :hash, NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 30 MINUTE))
                """)
                .param("usuario", usuarioId)
                .param("hash", Seguridad.hashToken(token))
                .update();
    }

    private String hashEnBase() {
        return jdbc.sql("SELECT clave_hash FROM usuarios WHERE usuario_id = :id")
                .param("id", usuarioId).query(String.class).single();
    }

    private boolean estaCerrada(String token) {
        return jdbc.sql("SELECT cerrada FROM sesiones WHERE token_hash = :hash")
                .param("hash", Seguridad.hashToken(token)).query(Boolean.class).single();
    }

    private void cambiarClave() {
        var peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr("10.0.0.5");
        controlador.cambiarClave(new SolicitudCambioClave(CLAVE, NUEVA),
                "Bearer " + TOKEN_ACTUAL + marca, peticion);
    }

    @Test
    void siFallaElCierreDeLasSesionesLaClaveNoCambia() {
        doThrow(new IllegalStateException("falla al cerrar las sesiones"))
                .when(sesiones).cerrarLasDemasDe(any(), anyString());

        assertThrows(IllegalStateException.class, this::cambiarClave);

        assertTrue(Seguridad.verificar(CLAVE, hashEnBase()), "la clave anterior sigue siendo la vigente");
        assertFalse(Seguridad.verificar(NUEVA, hashEnBase()), "la clave nueva no quedó guardada");
        assertFalse(estaCerrada(TOKEN_OTRO + marca));
    }

    /** El camino feliz: cambia la clave, cierra la otra sesión y conserva la actual. */
    @Test
    void sinFallasCambiaLaClaveCierraLasDemasYConservaLaActual() {
        cambiarClave();

        assertTrue(Seguridad.verificar(NUEVA, hashEnBase()));
        assertFalse(Seguridad.verificar(CLAVE, hashEnBase()));
        assertTrue(estaCerrada(TOKEN_OTRO + marca), "la otra sesión se cierra");
        assertFalse(estaCerrada(TOKEN_ACTUAL + marca), "la sesión de quien cambió la clave sigue abierta");
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM sesiones WHERE usuario_id = :id")
                .param("id", usuarioId).query(Integer.class).single());
    }
}
