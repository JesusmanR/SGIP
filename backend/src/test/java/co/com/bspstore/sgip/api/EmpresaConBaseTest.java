package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.EmpresaRepositorio;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.NitRepetidoException;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
import co.com.bspstore.sgip.modelo.Empresa;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.TipoDocumentoEmpresa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los datos de la empresa y su identidad, contra el esquema real: lo que el administrador edita de su
 * empresa (PUT /api/v1/empresa) y lo que solo el super administrador cambia (PUT
 * /api/v1/plataforma/empresas/{id}/identidad), con su bitácora.
 *
 * Crea sus empresas (A, la que se edita; B, la otra), sus usuarios y su super administrador, con
 * documentos de solo dígitos para poder mandarlos de vuelta como número, y lo borra todo al terminar,
 * de las hojas a la raíz.
 */
@Tag("conBase")
@SpringBootTest
@AutoConfigureMockMvc
class EmpresaConBaseTest {

    private static final String MOTIVO = "Corrección pedida por la tienda";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired EmpresaRepositorio empresas;
    @Autowired ServicioPlataforma servicioPlataforma;
    @Autowired TransactionTemplate transaccion;

    private String marca;
    private String nitA;
    private String nitB;
    private Integer empresaA;
    private Integer empresaB;
    private Integer adminA;
    private Integer adminB;
    private Integer superAdmin;

    private record Fila(String nit, String razon, String tipo, String nombre, String direccion, String ciudad,
                        String telefono, String correo) {}

    private record Auditoria(Integer usuarioId, Integer empresaId, String accion, String campo, String entidad,
                             String referencia, String anterior, String nuevo, String motivo,
                             LocalDateTime fecha) {}

    @BeforeEach
    void crearEmpresasYUsuarios() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        nitA = numeroAlAzar();
        nitB = numeroAlAzar();
        empresaA = plataforma.insertarEmpresa(nitA, "Empresa A " + marca, "a@ejemplo.co", "3000000001", "Cúcuta");
        empresaB = plataforma.insertarEmpresa(nitB, "Empresa B " + marca, "b@ejemplo.co", "3000000002", "Pamplona");
        plataforma.cambiarEstado(empresaA, EstadoEmpresa.ACTIVA);
        plataforma.cambiarEstado(empresaB, EstadoEmpresa.ACTIVA);
        adminA = insertarUsuario(empresaA, "ADMINISTRADOR", "admin-a-" + marca);
        adminB = insertarUsuario(empresaB, "ADMINISTRADOR", "admin-b-" + marca);
        superAdmin = insertarUsuario(null, "SUPER_ADMIN", "plataforma-" + marca);
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        List<Integer> todas = jdbc.sql("SELECT empresa_id FROM empresas WHERE razon_social LIKE :patron "
                        + "OR nit IN (:a, :b) OR empresa_id IN (:ea, :eb)")
                .param("patron", "%" + marca + "%").param("a", nitA).param("b", nitB)
                .param("ea", empresaA).param("eb", empresaB)
                .query(Integer.class).list();
        // De las hojas a la raíz: la bitácora, lo que el alta siembra y los usuarios cuelgan de la empresa.
        for (Integer empresa : todas) {
            for (String tabla : List.of("auditoria_plataforma", "clientes", "medios_pago", "proveedores",
                    "categorias", "empresa_config", "suscripciones", "usuarios", "empresas")) {
                jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                        .param("empresa", empresa).update();
            }
        }
        jdbc.sql("DELETE FROM auditoria_plataforma WHERE usuario_id = :id").param("id", superAdmin).update();
        jdbc.sql("DELETE FROM usuarios WHERE usuario_id = :id").param("id", superAdmin).update();
    }

    private static String numeroAlAzar() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_999_999_999_999L));
    }

    private Integer insertarUsuario(Integer empresa, String rol, String alias) {
        String correo = alias + "@ejemplo.co";
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = :rol),
                        :nombre, :correo, 'no-sirve-para-ingresar')
                """)
                .param("empresa", empresa).param("rol", rol).param("nombre", "Persona " + alias)
                .param("correo", correo).update();
        return jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correo).query(Integer.class).single();
    }

    private RequestPostProcessor como(Integer usuario, String rol, Integer empresa) {
        var u = new UsuarioAutenticado(usuario, "u" + usuario + "@ejemplo.co", rol, empresa);
        return authentication(new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_" + rol))));
    }

    private RequestPostProcessor comoAdminA() {
        return como(adminA, "ADMINISTRADOR", empresaA);
    }

    private RequestPostProcessor comoSuperAdmin() {
        return como(superAdmin, "SUPER_ADMIN", null);
    }

    private Fila fila(Integer empresa) {
        return jdbc.sql("""
                SELECT nit, razon_social AS razon, tipo_documento AS tipo, nombre_comercial AS nombre,
                       direccion, ciudad, telefono, correo_contacto AS correo
                  FROM empresas WHERE empresa_id = :empresa
                """).param("empresa", empresa).query(Fila.class).single();
    }

    private List<Auditoria> auditoria(Integer empresa) {
        return jdbc.sql("""
                SELECT usuario_id, empresa_id, accion, campo, entidad, referencia,
                       valor_anterior AS anterior, valor_nuevo AS nuevo, motivo, fecha
                  FROM auditoria_plataforma
                 WHERE empresa_id = :empresa AND accion = 'CAMBIO_IDENTIDAD_EMPRESA'
                 ORDER BY campo
                """).param("empresa", empresa).query(Auditoria.class).list();
    }

    private ResultActions editar(RequestPostProcessor quien, String cuerpo) throws Exception {
        return mvc.perform(put("/api/v1/empresa").contentType(MediaType.APPLICATION_JSON).content(cuerpo)
                .with(quien));
    }

    private ResultActions identidad(Integer empresa, RequestPostProcessor quien, String tipo, String numero,
                                    String razon, String motivo) throws Exception {
        String cuerpo = "{\"tipoDocumento\":\"" + tipo + "\",\"numero\":\"" + numero + "\",\"razonSocial\":\""
                + razon + "\",\"motivo\":\"" + motivo + "\"}";
        return mvc.perform(put("/api/v1/plataforma/empresas/" + empresa + "/identidad")
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo).with(quien));
    }

    // ------------------------------------------------ el administrador edita su empresa

    @Test
    void adminEditaDatosYSeVenEnElResumen() throws Exception {
        editar(comoAdminA(), """
                {"nombreComercial":"  Ferretería El Tornillo ","direccion":"Calle 5 # 6-7",
                 "ciudad":"Bucaramanga","telefono":" 3001234567","correoContacto":"contacto@tornillo.co "}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(empresaA))
                .andExpect(jsonPath("$.nombreComercial").value("Ferretería El Tornillo"))
                .andExpect(jsonPath("$.direccion").value("Calle 5 # 6-7"))
                .andExpect(jsonPath("$.ciudad").value("Bucaramanga"))
                .andExpect(jsonPath("$.telefono").value("3001234567"))
                .andExpect(jsonPath("$.correoContacto").value("contacto@tornillo.co"));

        // El resumen de siempre (GET /empresa) ya trae lo nuevo, y la identidad no se movió.
        mvc.perform(get("/api/v1/empresa").with(comoAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreComercial").value("Ferretería El Tornillo"))
                .andExpect(jsonPath("$.direccion").value("Calle 5 # 6-7"))
                .andExpect(jsonPath("$.ciudad").value("Bucaramanga"))
                .andExpect(jsonPath("$.nit").value(nitA))
                .andExpect(jsonPath("$.tipoDocumento").value("NIT"))
                .andExpect(jsonPath("$.razonSocial").value("Empresa A " + marca));

        assertEquals(new Fila(nitA, "Empresa A " + marca, "NIT", "Ferretería El Tornillo", "Calle 5 # 6-7",
                "Bucaramanga", "3001234567", "contacto@tornillo.co"), fila(empresaA));
    }

    @Test
    void vaciosQuedanNull() throws Exception {
        editar(comoAdminA(), """
                {"nombreComercial":"Tornillo","direccion":"Calle 5","ciudad":"Bucaramanga",
                 "telefono":"3001234567","correoContacto":"contacto@tornillo.co"}""").andExpect(status().isOk());

        // Texto vacío, solo espacios, null explícito y campo ausente: los cinco quedan en null.
        editar(comoAdminA(), """
                {"nombreComercial":"","direccion":"   ","ciudad":null,"correoContacto":"  "}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreComercial").doesNotExist())
                .andExpect(jsonPath("$.direccion").doesNotExist());

        Fila f = fila(empresaA);
        assertNull(f.nombre());
        assertNull(f.direccion());
        assertNull(f.ciudad());
        assertNull(f.telefono());
        assertNull(f.correo());
        assertEquals(nitA, f.nit());
    }

    @Test
    void elIdDeOtraEmpresaEnElCuerpoNoCambiaNada() throws Exception {
        Fila antesA = fila(empresaA);
        Fila antesB = fila(empresaB);

        // Ningún campo de identificación se acepta en el cuerpo, ni «id» ni «empresaId»: 400 y nada escrito.
        editar(comoAdminA(), "{\"empresaId\":" + empresaB + ",\"nombreComercial\":\"Secuestrada\"}")
                .andExpect(status().isBadRequest());
        editar(comoAdminA(), "{\"id\":" + empresaB + ",\"nombreComercial\":\"Secuestrada\"}")
                .andExpect(status().isBadRequest());
        assertEquals(antesA, fila(empresaA));
        assertEquals(antesB, fila(empresaB));

        // Y una edición válida toca solo a la empresa de la sesión.
        editar(comoAdminA(), "{\"nombreComercial\":\"Solo la mía\"}").andExpect(status().isOk());
        assertEquals("Solo la mía", fila(empresaA).nombre());
        assertEquals(antesB, fila(empresaB));
    }

    /**
     * Hibernate escribe todas las columnas de una entidad en cada UPDATE. Si la identidad fuera
     * escribible, guardar el contacto con una copia vieja de la empresa pisaría un cambio de NIT hecho
     * entremedias por la plataforma.
     */
    @Test
    void unaEdicionDeContactoNoPisaLaIdentidadCambiadaEntremedias() {
        String nitNuevo = numeroAlAzar();
        transaccion.executeWithoutResult(estado -> {
            Empresa copiaVieja = empresas.findById(empresaA).orElseThrow();
            jdbc.sql("UPDATE empresas SET nit = :nit, razon_social = 'Razón nueva ' WHERE empresa_id = :id")
                    .param("nit", nitNuevo).param("id", empresaA).update();
            copiaVieja.actualizarContacto("Tornillo", null, null, null, null);
            empresas.flush();
        });

        Fila f = fila(empresaA);
        assertEquals(nitNuevo, f.nit());
        assertEquals("Razón nueva ", f.razon());
        assertEquals("Tornillo", f.nombre());
    }

    // ------------------------------------------------ el super administrador cambia la identidad

    @Test
    void superAdminCambiaIdentidadYAuditaUnaFilaPorCampo() throws Exception {
        String nuevo = numeroAlAzar();
        String conPuntos = nuevo.substring(0, 4) + "." + nuevo.substring(4, 8) + "." + nuevo.substring(8);

        identidad(empresaA, comoSuperAdmin(), "CC", conPuntos, "Nueva Razón Social", MOTIVO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empresaId").value(empresaA))
                .andExpect(jsonPath("$.tipoDocumento").value("CC"))
                .andExpect(jsonPath("$.nit").value(nuevo))
                .andExpect(jsonPath("$.razonSocial").value("Nueva Razón Social"));

        Fila f = fila(empresaA);
        assertEquals(nuevo, f.nit());
        assertEquals("CC", f.tipo());
        assertEquals("Nueva Razón Social", f.razon());

        List<Auditoria> filas = auditoria(empresaA);
        assertEquals(3, filas.size(), "una fila por cada campo que cambió");
        // Ordenadas por campo: nit, razon_social, tipo_documento.
        assertEquals(List.of("nit", "razon_social", "tipo_documento"),
                filas.stream().map(Auditoria::campo).toList());
        for (Auditoria a : filas) {
            assertEquals(superAdmin, a.usuarioId());
            assertEquals(empresaA, a.empresaId());
            assertEquals("CAMBIO_IDENTIDAD_EMPRESA", a.accion());
            assertEquals("empresas", a.entidad());
            assertEquals(nitA, a.referencia(), "la referencia es el NIT anterior");
            assertEquals(MOTIVO, a.motivo());
            assertNotNull(a.fecha());
        }
        assertEquals(nitA, filas.get(0).anterior());
        assertEquals(nuevo, filas.get(0).nuevo());
        assertEquals("Empresa A " + marca, filas.get(1).anterior());
        assertEquals("Nueva Razón Social", filas.get(1).nuevo());
        assertEquals("NIT", filas.get(2).anterior());
        assertEquals("CC", filas.get(2).nuevo());
    }

    @Test
    void soloRazonSocialNoDaFalso409() throws Exception {
        // Mismo tipo, mismo número (escrito con puntos): el número ya es de esta empresa y no choca consigo.
        String conPuntos = nitA.substring(0, 4) + "." + nitA.substring(4);

        identidad(empresaA, comoSuperAdmin(), "NIT", conPuntos, "Solo cambia la razón", MOTIVO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razonSocial").value("Solo cambia la razón"));

        assertEquals(nitA, fila(empresaA).nit());
        List<Auditoria> filas = auditoria(empresaA);
        assertEquals(1, filas.size());
        assertEquals("razon_social", filas.get(0).campo());
    }

    /**
     * Las semillas con guion (como 900000000-0) se quedan como están: si el número es el mismo, no hay
     * cambio de número ni se reescribe la columna.
     */
    @Test
    void unNitSembradoConGuionNoSeReescribeSiSoloCambiaLaRazonSocial() throws Exception {
        String sembrado = nitA + "-7";
        jdbc.sql("UPDATE empresas SET nit = :nit WHERE empresa_id = :id")
                .param("nit", sembrado).param("id", empresaA).update();

        identidad(empresaA, comoSuperAdmin(), "NIT", nitA, "Razón con semilla", MOTIVO)
                .andExpect(status().isOk());

        assertEquals(sembrado, fila(empresaA).nit());
        assertEquals(List.of("razon_social"), auditoria(empresaA).stream().map(Auditoria::campo).toList());
    }

    @Test
    void sinCambiosNoAudita() throws Exception {
        identidad(empresaA, comoSuperAdmin(), "NIT", nitA, "Empresa A " + marca, MOTIVO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nit").value(nitA));

        assertEquals(0, auditoria(empresaA).size());
    }

    @Test
    void numeroRepetidoEs409() throws Exception {
        // La otra empresa guarda su NIT con el dígito de verificación, como las semillas (NNNNNNNNN-D): el
        // choque es por el número sin el dígito, no por el texto.
        String sembrado = nitB + "-1";
        jdbc.sql("UPDATE empresas SET nit = :nit WHERE empresa_id = :id")
                .param("nit", sembrado).param("id", empresaB).update();
        Fila antes = fila(empresaA);

        identidad(empresaA, comoSuperAdmin(), "NIT", nitB, "Empresa A " + marca, MOTIVO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:sgip:nit-repetido"));

        assertEquals(antes, fila(empresaA));
        assertEquals(0, auditoria(empresaA).size(), "un cambio rechazado no deja huella");
    }

    @Test
    void empresaInexistenteEs404() throws Exception {
        identidad(2_000_000_000, comoSuperAdmin(), "NIT", numeroAlAzar(), "Nada", MOTIVO)
                .andExpect(status().isNotFound());
    }

    @Test
    void unNumeroConGuionYDigitoEs400ConMensajeClaroYNoCambiaNada() throws Exception {
        Fila antes = fila(empresaA);

        identidad(empresaA, comoSuperAdmin(), "NIT", numeroAlAzar() + "-1", "Nueva", MOTIVO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:sgip:empresa:documento-invalido"))
                .andExpect(jsonPath("$.detail").value("Escribe solo el número, sin el dígito de verificación."));

        assertEquals(antes, fila(empresaA));
        assertEquals(0, auditoria(empresaA).size());
    }

    @Test
    void unDocumentoConLetrasEs400ConSuTipoYNoCambiaNada() throws Exception {
        Fila antes = fila(empresaA);

        identidad(empresaA, comoSuperAdmin(), "NIT", "90012345A", "Nueva", MOTIVO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:sgip:empresa:documento-invalido"));

        assertEquals(antes, fila(empresaA));
        assertEquals(0, auditoria(empresaA).size());
    }

    @Test
    void elAdministradorNoAlcanzaLaIdentidadNiConSuPropiaEmpresa() throws Exception {
        Fila antes = fila(empresaA);

        identidad(empresaA, comoAdminA(), "CC", numeroAlAzar(), "Nueva", MOTIVO)
                .andExpect(status().isForbidden());

        assertEquals(antes, fila(empresaA));
    }

    // ------------------------------------------------ el alta

    @Test
    void elAltaGuardaElTipoYElNumeroNormalizadoYChocaPorElNumero() {
        String numero = numeroAlAzar();
        String conPuntos = numero.substring(0, 4) + "." + numero.substring(4, 8) + "." + numero.substring(8);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(
                new UsuarioAutenticado(superAdmin, "p@ejemplo.co", "SUPER_ADMIN", null), null, List.of()));
        SecurityContextHolder.setContext(c);

        var creada = servicioPlataforma.darDeAlta(new SolicitudAltaEmpresa(TipoDocumentoEmpresa.CC, conPuntos,
                "Persona natural " + marca, null, null, null, null,
                "Administración", "alta-" + marca + "@ejemplo.co", "ClaveDePrueba2026"));

        assertEquals("CC", creada.tipoDocumento());
        assertEquals(numero, creada.nit());
        assertEquals("CC", fila(creada.empresaId()).tipo());

        // Con otro formato y otro tipo, es el mismo número: choca.
        assertThrows(NitRepetidoException.class, () -> servicioPlataforma.darDeAlta(new SolicitudAltaEmpresa(
                TipoDocumentoEmpresa.NIT, numero + " ", "Otra " + marca, null, null, null, null,
                "Otro", "otro-" + marca + "@ejemplo.co", "ClaveDePrueba2026")));

        // Una semilla con su dígito (NNNNNNNNN-D) bloquea el mismo número escrito sin el dígito.
        String conDigito = numeroAlAzar();
        jdbc.sql("UPDATE empresas SET nit = :nit WHERE empresa_id = :id")
                .param("nit", conDigito + "-4").param("id", empresaB).update();
        assertThrows(NitRepetidoException.class, () -> servicioPlataforma.darDeAlta(new SolicitudAltaEmpresa(
                TipoDocumentoEmpresa.NIT, conDigito, "Tercera " + marca, null, null, null, null,
                "Tercero", "tercero-" + marca + "@ejemplo.co", "ClaveDePrueba2026")));
    }
}
