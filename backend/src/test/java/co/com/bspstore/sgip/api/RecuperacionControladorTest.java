package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.recuperacion.ServicioRecuperacion;
import co.com.bspstore.sgip.logica.recuperacion.TareaDeRecuperacion;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La solicitud del código de recuperación (RF-65, §5.2 y §5.3 de la spec) a través de la
 * cadena de seguridad real.
 *
 * {@code ServicioRecuperacion} es el real y la tarea asíncrona es la simulada: lo que se
 * comprueba es lo que ve quien pide (siempre el mismo 200) y lo que recibe la tarea (el
 * correo normalizado), sin tocar la base.
 */
@WebMvcTest(controllers = AutenticacionControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class, ServicioRecuperacion.class})
class RecuperacionControladorTest {

    private static final String SOLICITAR = "/api/v1/auth/recuperacion";
    private static final String CONFIRMAR = "/api/v1/auth/recuperacion/confirmar";
    private static final String MENSAJE =
            "Si el correo está registrado, te enviamos un código. Revisa también la carpeta de spam.";

    @Autowired MockMvc mvc;

    @MockitoBean TareaDeRecuperacion tarea;
    @MockitoBean ServicioAutenticacion autenticacion;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    private static String cuerpo(String correo) {
        return correo == null ? "{\"correo\":null}"
                : "{\"correo\":\"" + correo + "\"}";
    }

    private static RequestPostProcessor como(String rol, Integer empresa, boolean debeCambiarClave) {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa, debeCambiarClave);
        return authentication(new UsernamePasswordAuthenticationToken(u, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + rol))));
    }

    // ------------------------------------------------------------- sin token

    @Test
    void sinTokenResponde200ConElMensajeExacto() throws Exception {
        mvc.perform(post(SOLICITAR).contentType(MediaType.APPLICATION_JSON).content(cuerpo("ana@tienda.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE));
    }

    @Test
    void laConfirmacionTambienEsPublica() throws Exception {
        // Esta tarea no crea la ruta de confirmar (es la 4): lo que importa aquí es que la
        // cadena no pida credenciales. Un 404 sin controlador o un 400 con él valen; un 401 o un
        // 403 no.
        MvcResult resultado = mvc.perform(post(CONFIRMAR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"ana@tienda.com\",\"codigo\":\"123456\",\"nueva\":\"Nueva2026xyz\"}"))
                .andReturn();

        assertNotEquals(401, resultado.getResponse().getStatus());
        assertNotEquals(403, resultado.getResponse().getStatus());
    }

    @Test
    void soloSonPublicasLasDosRutasExactas() throws Exception {
        // Otro método, una barra final o una ruta que solo empieza igual no se abren.
        mvc.perform(get(SOLICITAR)).andExpect(status().isUnauthorized());
        mvc.perform(post(SOLICITAR + "/").contentType(MediaType.APPLICATION_JSON).content(cuerpo("a@b.co")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(SOLICITAR + "/otra").contentType(MediaType.APPLICATION_JSON).content(cuerpo("a@b.co")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(CONFIRMAR + "x").contentType(MediaType.APPLICATION_JSON).content(cuerpo("a@b.co")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(tarea);
    }

    // ------------------------------------------- un token viejo no la bloquea

    @Test
    void conElTokenDeUnUsuarioConCambioDeClavePendienteResponde200YNoUn403() throws Exception {
        mvc.perform(post(SOLICITAR).contentType(MediaType.APPLICATION_JSON).content(cuerpo("ana@tienda.com"))
                        .with(como("VENDEDOR", 4, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE));
    }

    @Test
    void conUnTokenQueYaNoVale() throws Exception {
        // ServicioSesion simulado: validar() devuelve vacío, como con una sesión cerrada.
        mvc.perform(post(SOLICITAR).header("Authorization", "Bearer token-viejo")
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo("ana@tienda.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE));
    }

    @Test
    void enUnaEmpresaEnModoConsultaResponde200() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));

        mvc.perform(post(SOLICITAR).contentType(MediaType.APPLICATION_JSON).content(cuerpo("ana@tienda.com"))
                        .with(como("VENDEDOR", 4, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE));
    }

    // --------------------------------------------- la respuesta no distingue

    @Test
    void elMismoCuerpoParaUnCorreoExistenteInexistenteVacioNuloOLargo() throws Exception {
        String largo = "a".repeat(300) + "@tienda.com";
        for (String correo : new String[]{"ana@tienda.com", "no-existe@tienda.com", "", null, "sin-arroba", largo}) {
            mvc.perform(post(SOLICITAR).contentType(MediaType.APPLICATION_JSON).content(cuerpo(correo)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mensaje").value(MENSAJE))
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    @Test
    void sinElCampoCorreoTambienResponde200() throws Exception {
        mvc.perform(post(SOLICITAR).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE));

        verify(tarea).procesar(eq("(vacío)"), any());
    }

    @Test
    void unCuerpoMalFormadoDaElBadRequestNormalYNoLlegaALaTarea() throws Exception {
        mvc.perform(post(SOLICITAR).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());

        verify(tarea, never()).procesar(any(), any());
    }

    // ------------------------------------------------- lo que recibe la tarea

    @Test
    void laTareaRecibeElCorreoNormalizadoYElOrigenDeLaPeticion() throws Exception {
        mvc.perform(post(SOLICITAR).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(" Ana@Tienda.COM "))
                        .with(request -> {
                            request.setRemoteAddr("10.1.2.3");
                            return request;
                        }))
                .andExpect(status().isOk());

        verify(tarea).procesar("ana@tienda.com", "10.1.2.3");
    }
}
