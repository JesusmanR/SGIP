package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.AdministradorParaAutorizar;
import co.com.bspstore.sgip.api.dto.EstadoPin;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.api.dto.SolicitudPin;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.AutorizacionRequeridaException;
import co.com.bspstore.sgip.logica.PinBloqueadoException;
import co.com.bspstore.sgip.logica.PinInvalidoException;
import co.com.bspstore.sgip.logica.PinNuevoInvalidoException;
import co.com.bspstore.sgip.logica.ServicioAutorizacion;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que el PIN dice y calla por la API (ADR-0032): la forma de las respuestas, la
 * validación del cuerpo y los textos de los rechazos. Ninguno repite un PIN, y los
 * cuatro rechazos de la autorización tienen texto fijo en español.
 */
@WebMvcTest(controllers = PinControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class PinControladorTest {

    private static final String PIN = "/api/v1/perfil/pin";

    @Autowired MockMvc mvc;

    @MockitoBean ServicioAutorizacion servicio;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    private static RequestPostProcessor administrador() {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", 4);
        return authentication(new UsernamePasswordAuthenticationToken(u, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
    }

    private org.springframework.test.web.servlet.ResultActions fijar(String cuerpo) throws Exception {
        return mvc.perform(put(PIN).with(administrador())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    // ------------------------------------------------------------ las respuestas

    @Test
    void elEstadoDelPinDiceSiEstaConfigurado() throws Exception {
        when(servicio.estadoDelPin()).thenReturn(new EstadoPin(false), new EstadoPin(true));

        mvc.perform(get(PIN).with(administrador()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"configurado\":false}", true));
        mvc.perform(get(PIN).with(administrador()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"configurado\":true}", true));
    }

    @Test
    void laListaDeAdministradoresSoloTraeIdYNombre() throws Exception {
        when(servicio.administradores()).thenReturn(List.of(new AdministradorParaAutorizar(10, "Ana Admin")));

        var u = new UsuarioAutenticado(8, "vera@tienda.co", "VENDEDOR", 4);
        mvc.perform(get("/api/v1/caja/administradores").with(authentication(
                        new UsernamePasswordAuthenticationToken(u, null,
                                List.of(new SimpleGrantedAuthority("ROLE_VENDEDOR"))))))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":10,\"nombre\":\"Ana Admin\"}]", true));
    }

    @Test
    void elPinActualViajaAlServicioParaCambiarlo() throws Exception {
        fijar("{\"pinActual\":\"4821\",\"pinNuevo\":\"9999\"}").andExpect(status().isNoContent());

        verify(servicio).fijarPin("4821", "9999");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"pinActual\":\"\",\"pinNuevo\":\"9999\"}",
            "{\"pinActual\":\"   \",\"pinNuevo\":\"9999\"}",
            "{\"pinActual\":null,\"pinNuevo\":\"9999\"}"})
    void unPinActualVacioNoEsUnErrorDeFormato(String cuerpo) throws Exception {
        // Qué hacer con él (la primera vez se ignora; para cambiar el PIN falta) lo
        // decide el servicio, no la validación del cuerpo.
        fijar(cuerpo).andExpect(status().isNoContent());

        verify(servicio).fijarPin(any(), org.mockito.ArgumentMatchers.eq("9999"));
    }

    // ------------------------------------------------------------- la validación

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"pinNuevo\":\"123\"}",
            "{\"pinNuevo\":\"1234567\"}",
            "{\"pinNuevo\":\"12a4\"}",
            "{\"pinNuevo\":\"12 34\"}",
            "{\"pinNuevo\":\"\"}",
            "{\"pinNuevo\":\"   \"}",
            "{}",
            "{\"pinActual\":\"abc\",\"pinNuevo\":\"1234\"}",
            "{\"pinActual\":\"1234567\",\"pinNuevo\":\"1234\"}"})
    void unPinMalFormadoEsUn400SinTocarElServicio(String cuerpo) throws Exception {
        fijar(cuerpo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"));

        verifyNoInteractions(servicio);
    }

    @Test
    void elMensajeDeValidacionNoRepiteElPinEscrito() throws Exception {
        String respuesta = fijar("{\"pinNuevo\":\"12a4\"}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(respuesta.contains("12a4"));
    }

    // -------------------------------------------------------------- los rechazos

    @Test
    void elPinNuevoQueElServicioRechazaEsUn400() throws Exception {
        doThrow(new PinNuevoInvalidoException("El PIN debe ser distinto de tu contraseña."))
                .when(servicio).fijarPin(any(), any());

        fijar("{\"pinNuevo\":\"123456\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("PIN nuevo no válido"))
                .andExpect(jsonPath("$.detail").value("El PIN debe ser distinto de tu contraseña."));
    }

    @Test
    void unPinActualErradoEsUn403QueDiceElPlazoYNoRepiteNada() throws Exception {
        doThrow(new PinInvalidoException(15)).when(servicio).fijarPin(any(), any());

        String respuesta = fijar("{\"pinActual\":\"0000\",\"pinNuevo\":\"9999\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("PIN incorrecto"))
                .andExpect(jsonPath("$.detail").value(
                        "El administrador o el PIN no son correctos. "
                                + "Si te equivocas varias veces, la autorización se bloquea durante 15 minutos."))
                .andReturn().getResponse().getContentAsString();

        assertFalse(respuesta.contains("0000"));
        assertFalse(respuesta.contains("9999"));
    }

    @Test
    void elPlazoEnSingularDiceMinuto() {
        var p = new ManejadorErrores().pinInvalido(new PinInvalidoException(1));

        assertTrue(p.getDetail().endsWith("durante 1 minuto."), p.getDetail());
    }

    @Test
    void unPinBloqueadoEsUn403QueDiceCuantoDura() throws Exception {
        doThrow(new PinBloqueadoException(15)).when(servicio).fijarPin(any(), any());

        fijar("{\"pinActual\":\"4821\",\"pinNuevo\":\"9999\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Autorización bloqueada"))
                .andExpect(jsonPath("$.detail").value(
                        "Se superó el número de intentos con el PIN. "
                                + "Espera 15 minutos desde el bloqueo e inténtalo de nuevo."));
    }

    @Test
    void laAutorizacionQueFaltaEsUn403ConTextoFijo() {
        var p = new ManejadorErrores().autorizacionRequerida(new AutorizacionRequeridaException());

        org.junit.jupiter.api.Assertions.assertEquals(403, p.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("Autorización requerida", p.getTitle());
        org.junit.jupiter.api.Assertions.assertEquals(
                "Esta operación necesita la autorización de un administrador.", p.getDetail());
    }

    // ------------------------------------------------------ lo que no debe loguearse

    @Test
    void ningunaSolicitudImprimeElPin() {
        assertFalse(new SolicitudPin("4821", "9999").toString().contains("4821"));
        assertFalse(new SolicitudPin("4821", "9999").toString().contains("9999"));
        String anidada = new SolicitudAutorizacion(10, "483920").toString();
        assertFalse(anidada.contains("483920"), anidada);
        assertTrue(anidada.contains("10"), "el administrador sí se identifica");
    }
}
