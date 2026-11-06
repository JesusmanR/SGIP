package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién aparece como bloqueado en la lista de usuarios de una empresa
 * (GET /plataforma/empresas/{id}/usuarios), contra el esquema real.
 *
 * El bloqueo se crea por la entidad, el mismo camino que usa la aplicación: un
 * UPDATE con JdbcClient guarda el instante tal cual y Hibernate lo lee
 * convertido de la zona de la conexión a la de la JVM, que en la CI son
 * distintas (UTC y America/Bogota). La columna {@code bloqueado} sigue en 1 pasado
 * el plazo; lo que cuenta para «hoy no puede entrar» es el instante.
 *
 * Necesita la base con el esquema aplicado. Crea su propia empresa y usuarios y
 * los borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
@AutoConfigureMockMvc
class UsuariosDeEmpresaEnPlataformaConBaseTest {

    private static final String CLAVE = "SgipDev2026";

    @Autowired MockMvc mvc;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired UsuarioRepositorio usuarios;
    @Autowired JdbcClient jdbc;

    private String marca;
    private Integer empresaId;
    private Integer superAdminId;
    private Integer recienteId;
    private Integer vencidoId;
    private Integer libreId;

    @BeforeEach
    void prepararDatosPropios() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        empresaId = plataforma.insertarEmpresa("U" + marca.toUpperCase(), "Prueba usuarios " + marca,
                null, null, null);
        recienteId = insertarUsuario(empresaId, "ADMINISTRADOR", "reciente-" + marca + "@ejemplo.co", 1);
        vencidoId = insertarUsuario(empresaId, "VENDEDOR", "vencido-" + marca + "@ejemplo.co", 1);
        libreId = insertarUsuario(empresaId, "VENDEDOR", "libre-" + marca + "@ejemplo.co", 1);
        superAdminId = insertarUsuario(null, "SUPER_ADMIN", "plataforma-" + marca + "@ejemplo.co", 1);
        bloquearHace(recienteId, 1);
        bloquearHace(vencidoId, 16);
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresaId == null) {
            return;
        }
        jdbc.sql("DELETE FROM usuarios WHERE correo LIKE :patron")
                .param("patron", "%-" + marca + "@ejemplo.co").update();
        jdbc.sql("DELETE FROM empresas WHERE empresa_id = :empresa")
                .param("empresa", empresaId).update();
    }

    private Integer insertarUsuario(Integer empresa, String rol, String correo, int activo) {
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, activo, cambio_clave_req)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = :rol),
                        :nombre, :correo, :hash, :activo, 0)
                """)
                .param("empresa", empresa)
                .param("rol", rol)
                .param("nombre", "Persona " + correo.substring(0, correo.indexOf('-')))
                .param("correo", correo)
                .param("hash", Seguridad.derivar(CLAVE))
                .param("activo", activo)
                .update();
        return jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correo).query(Integer.class).single();
    }

    private void bloquearHace(Integer usuarioId, int minutos) {
        Usuario usuario = usuarios.findById(usuarioId).orElseThrow();
        LocalDateTime hace = LocalDateTime.now().minusMinutes(minutos);
        for (int i = 0; i < 5; i++) {
            usuario.registrarIntentoFallido(5, hace);
        }
        usuarios.save(usuario);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor comoSuperAdministrador() {
        var u = new UsuarioAutenticado(superAdminId, "plataforma@ejemplo.co", "SUPER_ADMIN", null);
        return authentication(new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    private String ruta() {
        return "/api/v1/plataforma/empresas/" + empresaId + "/usuarios";
    }

    @Test
    void unBloqueoDentroDelPlazoApareceBloqueado() throws Exception {
        mvc.perform(get(ruta()).with(comoSuperAdministrador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + recienteId + ")].bloqueado").value(true));
    }

    @Test
    void unBloqueoVencidoNoApareceBloqueadoAunqueLaColumnaSigaEnUno() throws Exception {
        mvc.perform(get(ruta()).with(comoSuperAdministrador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + vencidoId + ")].bloqueado").value(false));
    }

    @Test
    void quienNuncaSeBloqueoNoApareceBloqueado() throws Exception {
        mvc.perform(get(ruta()).with(comoSuperAdministrador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + libreId + ")].bloqueado").value(false));
    }

    @Test
    void traeSoloALosTresDeLaEmpresaYNuncaLaClave() throws Exception {
        mvc.perform(get(ruta()).with(comoSuperAdministrador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].claveHash").doesNotExist())
                .andExpect(jsonPath("$[0].clave").doesNotExist());
    }

    @Test
    void unaEmpresaQueNoExisteEsUn404() throws Exception {
        mvc.perform(get("/api/v1/plataforma/empresas/" + (empresaId + 1_000_000) + "/usuarios")
                        .with(comoSuperAdministrador()))
                .andExpect(status().isNotFound());
    }
}
