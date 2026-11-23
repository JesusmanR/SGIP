package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ClienteNoEditableException;
import co.com.bspstore.sgip.logica.DatoDeClienteInvalidoException;
import co.com.bspstore.sgip.logica.DatosDeDomicilioIncompletosException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import co.com.bspstore.sgip.logica.ServicioClientes;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién llega a los clientes de la caja (RF-66): el administrador y el vendedor.
 * El cliente y el super administrador reciben 403 en las tres rutas.
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA, de
 * modo que {@code FiltroEmpresaActiva} deja pasar las escrituras y un 403 solo
 * puede venir de {@code @PreAuthorize}; la prueba de la empresa en consulta lo
 * cambia a propósito. Cada denegación comprueba que el servicio ni se enteró.
 */
@WebMvcTest(controllers = ClientesControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class ClientesAutorizacionTest {

    private static final String RUTA = "/api/v1/caja/clientes";
    private static final String CUERPO = """
            {"nombre":"María Pérez","tipoDocumento":"CC","identificacion":"1098765432",
             "correo":"maria@correo.co","telefono":"3001234567","direccion":"Calle 5 # 6-7",
             "ciudad":"Bucaramanga","autorizacionDatos":true}""";

    private static final Cliente MARIA = new Cliente(7, "María Pérez", TipoDocumento.CC, "1098765432",
            "maria@correo.co", "3001234567", "Calle 5 # 6-7", "Bucaramanga", false);

    @Autowired MockMvc mvc;

    @MockitoBean ServicioClientes servicio;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(u, null, autoridades));
    }

    private static MockHttpServletRequestBuilder consultar() {
        return get(RUTA).param("tipo", "CC").param("identificacion", "1098765432");
    }

    private static MockHttpServletRequestBuilder crear(String cuerpo) {
        return post(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static MockHttpServletRequestBuilder editar(int id, String cuerpo) {
        return put(RUTA + "/" + id).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorConsultanUnClientePorSuDocumento(String rol) throws Exception {
        when(servicio.porIdentificacion(TipoDocumento.CC, "1098765432")).thenReturn(Optional.of(MARIA));

        mvc.perform(consultar().with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.nombre").value("María Pérez"))
                .andExpect(jsonPath("$.tipoDocumento").value("CC"))
                .andExpect(jsonPath("$.identificacion").value("1098765432"))
                .andExpect(jsonPath("$.correo").value("maria@correo.co"))
                .andExpect(jsonPath("$.telefono").value("3001234567"))
                .andExpect(jsonPath("$.direccion").value("Calle 5 # 6-7"))
                .andExpect(jsonPath("$.ciudad").value("Bucaramanga"))
                .andExpect(jsonPath("$.consumidorFinal").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorCreanUnClienteYRecibenUn201(String rol) throws Exception {
        when(servicio.crear(any())).thenReturn(MARIA);

        mvc.perform(crear(CUERPO).with(como(rol)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorEditanUnCliente(String rol) throws Exception {
        when(servicio.actualizar(org.mockito.ArgumentMatchers.eq(7), any())).thenReturn(MARIA);

        mvc.perform(editar(7, CUERPO).with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("María Pérez"));
    }

    // ------------------------------------------------------ lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        for (MockHttpServletRequestBuilder llamada : List.of(
                consultar(), crear(CUERPO), editar(7, CUERPO))) {
            mvc.perform(llamada.with(como(rol)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        }

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(consultar()).andExpect(status().isUnauthorized());
        mvc.perform(crear(CUERPO)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void unaEmpresaEnConsultaLeeLosClientesPeroNoEscribe() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
        when(servicio.porIdentificacion(TipoDocumento.CC, "1098765432")).thenReturn(Optional.of(MARIA));

        mvc.perform(consultar().with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(crear(CUERPO).with(como("VENDEDOR"))).andExpect(status().isForbidden());
        mvc.perform(editar(7, CUERPO).with(como("VENDEDOR"))).andExpect(status().isForbidden());

        org.mockito.Mockito.verify(servicio, org.mockito.Mockito.never()).crear(any());
        org.mockito.Mockito.verify(servicio, org.mockito.Mockito.never()).actualizar(any(), any());
    }

    // ------------------------------------------------------ los datos inválidos

    @ParameterizedTest
    @ValueSource(strings = {
            // falta el nombre
            "{\"tipoDocumento\":\"CC\",\"identificacion\":\"1\",\"correo\":\"a@b.co\",\"autorizacionDatos\":true}",
            // nombre en blanco
            "{\"nombre\":\"  \",\"tipoDocumento\":\"CC\",\"identificacion\":\"1\",\"correo\":\"a@b.co\"}",
            // falta la identificación
            "{\"nombre\":\"Ana\",\"tipoDocumento\":\"CC\",\"correo\":\"a@b.co\",\"autorizacionDatos\":true}",
            // identificación en blanco
            "{\"nombre\":\"Ana\",\"tipoDocumento\":\"CC\",\"identificacion\":\" \",\"correo\":\"a@b.co\"}",
            // falta el correo
            "{\"nombre\":\"Ana\",\"tipoDocumento\":\"CC\",\"identificacion\":\"1\",\"autorizacionDatos\":true}",
            // correo en blanco
            "{\"nombre\":\"Ana\",\"tipoDocumento\":\"CC\",\"identificacion\":\"1\",\"correo\":\"\"}",
            // correo mal escrito
            "{\"nombre\":\"Ana\",\"tipoDocumento\":\"CC\",\"identificacion\":\"1\",\"correo\":\"sin-arroba\"}",
            // falta el tipo de documento
            "{\"nombre\":\"Ana\",\"identificacion\":\"1\",\"correo\":\"a@b.co\",\"autorizacionDatos\":true}",
            // tipo de documento desconocido
            "{\"nombre\":\"Ana\",\"tipoDocumento\":\"LICENCIA\",\"identificacion\":\"1\",\"correo\":\"a@b.co\"}",
            // identificación más larga que la columna
            "{\"nombre\":\"Ana\",\"tipoDocumento\":\"CC\",\"identificacion\":\"123456789012345678901\",\"correo\":\"a@b.co\"}"})
    void crearExigeNombreIdentificacionYCorreo(String cuerpo) throws Exception {
        mvc.perform(crear(cuerpo).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());
        mvc.perform(editar(7, cuerpo).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void elDetalleDelCuerpoInvalidoNoRepiteLoEnviado() throws Exception {
        mvc.perform(crear("{\"nombre\":\"Ana\",\"tipoDocumento\":\"CC\",\"identificacion\":\"SECRETO-77\","
                        + "\"correo\":\"sin-arroba-SECRETO\"}").with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRETO"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tipo", "identificacion"})
    void consultarSinTipoOSinIdentificacionEsUn400(String falta) throws Exception {
        MockHttpServletRequestBuilder llamada = get(RUTA);
        if (!falta.equals("tipo")) {
            llamada.param("tipo", "CC");
        }
        if (!falta.equals("identificacion")) {
            llamada.param("identificacion", "1098765432");
        }

        mvc.perform(llamada.with(como("VENDEDOR"))).andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void consultarConUnTipoDesconocidoEsUn400SinRepetirLoEscrito() throws Exception {
        mvc.perform(get(RUTA).param("tipo", "LICENCIA-77").param("identificacion", "1")
                        .with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("LICENCIA-77"))));
    }

    // ----------------------------------------------------- los errores, en español

    @Test
    void consultarUnoQueNoExisteEsUn404SinRepetirElDocumento() throws Exception {
        when(servicio.porIdentificacion(TipoDocumento.CC, "1098765432")).thenReturn(Optional.empty());

        mvc.perform(consultar().with(como("VENDEDOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("El cliente no existe."));
    }

    @Test
    void editarUnClienteQueNoEsDeLaEmpresaEsUn404() throws Exception {
        doThrow(new RecursoNoEncontradoException("El cliente no existe."))
                .when(servicio).actualizar(org.mockito.ArgumentMatchers.eq(99), any());

        mvc.perform(editar(99, CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("El cliente no existe."));
    }

    @Test
    void unDocumentoRepetidoEsUn409() throws Exception {
        doThrow(new RegistroRepetidoException("Ya existe un cliente con ese tipo y número de documento."))
                .when(servicio).crear(any());

        mvc.perform(crear(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya existe un cliente con ese tipo y número de documento."));
    }

    @Test
    void editarElConsumidorFinalEsUn409() throws Exception {
        doThrow(new ClienteNoEditableException("El Consumidor final lo gestiona el sistema y no se puede editar."))
                .when(servicio).actualizar(org.mockito.ArgumentMatchers.eq(1), any());

        mvc.perform(editar(1, CUERPO).with(como("ADMINISTRADOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("El Consumidor final lo gestiona el sistema y no se puede editar."));
    }

    @Test
    void sinAutorizacionDeDatosOSinCiudadEsUn400() throws Exception {
        doThrow(new DatoDeClienteInvalidoException("Hace falta la autorización del cliente para tratar sus datos."))
                .when(servicio).crear(any());

        mvc.perform(crear(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Hace falta la autorización del cliente para tratar sus datos."));
    }

    /**
     * El domicilio no se pide por estas rutas: lo exige la venta (tarea 8). Se prueba
     * aquí la traducción del error porque vive en el mismo {@code ManejadorErrores}.
     */
    @Test
    void losDatosDeDomicilioIncompletosSeTraducenA400() throws Exception {
        doThrow(new DatosDeDomicilioIncompletosException("Para un domicilio faltan datos del cliente: teléfono."))
                .when(servicio).crear(any());

        mvc.perform(crear(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos de domicilio incompletos"))
                .andExpect(jsonPath("$.detail").value("Para un domicilio faltan datos del cliente: teléfono."));
    }
}
