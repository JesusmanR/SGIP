package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.SolicitudCliente;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Los clientes de la caja (RF-66). La empresa sale del contexto y no de un
 * parámetro; el «Consumidor final» lo gestiona el sistema; y ningún mensaje
 * repite el dato que el cliente envió.
 */
@ExtendWith(MockitoExtension.class)
class ServicioClientesTest {

    private static final int EMPRESA = 3;
    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 9, 10, 30);

    private static final Cliente CONSUMIDOR_FINAL = new Cliente(1, "Consumidor final", TipoDocumento.CC,
            "222222222222", null, null, null, "Bucaramanga", true);
    private static final Cliente MARIA = new Cliente(7, "María Pérez", TipoDocumento.CC, "1098765432",
            "maria@correo.co", "3001234567", "Calle 5 # 6-7", "Bucaramanga", false);

    @Mock RepositorioClientes clientes;

    private ServicioClientes servicio;

    @BeforeEach
    void comoEmpresa() {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "VENDEDOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
        Clock reloj = Clock.fixed(ZonedDateTime.of(AHORA, ZONA).toInstant(), ZONA);
        servicio = new ServicioClientes(clientes, reloj);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private static SolicitudCliente solicitud(String ciudad, boolean autoriza) {
        return new SolicitudCliente("  María Pérez ", TipoDocumento.CC, " 1098765432 ",
                " maria@correo.co ", "3001234567", "Calle 5 # 6-7", ciudad, autoriza);
    }

    private void sinDuplicado() {
        when(clientes.existeOtroConLaIdentificacion(eq(EMPRESA), eq(TipoDocumento.CC), anyString(), anyInt()))
                .thenReturn(false);
    }

    // ---------------------------------------------------------------- crear

    @Test
    void crearGuardaLaAutorizacionConLaHoraDelRelojYElMedioCaja() {
        sinDuplicado();
        when(clientes.insertar(eq(EMPRESA), any(), any(), any())).thenReturn(7);
        when(clientes.buscar(EMPRESA, 7)).thenReturn(Optional.of(MARIA));

        Cliente creado = servicio.crear(solicitud("Bucaramanga", true));

        assertEquals(MARIA, creado);
        verify(clientes).insertar(eq(EMPRESA), any(), eq(AHORA), eq("CAJA"));
    }

    @Test
    void crearRecortaLosTextosYNoMarcaConsumidorFinal() {
        sinDuplicado();
        when(clientes.insertar(eq(EMPRESA), any(), any(), any())).thenReturn(7);
        when(clientes.buscar(EMPRESA, 7)).thenReturn(Optional.of(MARIA));

        servicio.crear(solicitud("  Floridablanca ", true));

        ArgumentCaptor<Cliente> datos = ArgumentCaptor.forClass(Cliente.class);
        verify(clientes).insertar(eq(EMPRESA), datos.capture(), any(), any());
        assertEquals(new Cliente(null, "María Pérez", TipoDocumento.CC, "1098765432", "maria@correo.co",
                "3001234567", "Calle 5 # 6-7", "Floridablanca", false), datos.getValue());
    }

    @Test
    void crearExigeLaAutorizacionDeDatos() {
        DatoDeClienteInvalidoException e = assertThrows(DatoDeClienteInvalidoException.class,
                () -> servicio.crear(solicitud("Bucaramanga", false)));

        assertFalse(e.getMessage().contains("1098765432"), "sin eco del dato enviado");
        verify(clientes, never()).insertar(any(), any(), any(), any());
    }

    @Test
    void laCiudadSaleDeLaEmpresaSiNoViene() {
        sinDuplicado();
        when(clientes.ciudadDeLaEmpresa(EMPRESA)).thenReturn(Optional.of("Bucaramanga"));
        when(clientes.insertar(eq(EMPRESA), any(), any(), any())).thenReturn(7);
        when(clientes.buscar(EMPRESA, 7)).thenReturn(Optional.of(MARIA));

        servicio.crear(solicitud(null, true));
        servicio.crear(solicitud("   ", true));

        ArgumentCaptor<Cliente> datos = ArgumentCaptor.forClass(Cliente.class);
        verify(clientes, org.mockito.Mockito.times(2)).insertar(eq(EMPRESA), datos.capture(), any(), any());
        assertEquals(List.of("Bucaramanga", "Bucaramanga"),
                datos.getAllValues().stream().map(Cliente::ciudad).toList());
    }

    @Test
    void laCiudadQueVieneMandaSobreLaDeLaEmpresa() {
        sinDuplicado();
        when(clientes.insertar(eq(EMPRESA), any(), any(), any())).thenReturn(7);
        when(clientes.buscar(EMPRESA, 7)).thenReturn(Optional.of(MARIA));

        servicio.crear(solicitud("Girón", true));

        verify(clientes, never()).ciudadDeLaEmpresa(any());
    }

    @Test
    void sinCiudadNiEnLaEmpresaEs400() {
        when(clientes.ciudadDeLaEmpresa(EMPRESA)).thenReturn(Optional.empty());

        DatoDeClienteInvalidoException e = assertThrows(DatoDeClienteInvalidoException.class,
                () -> servicio.crear(solicitud(null, true)));

        assertTrue(e.getMessage().contains("ciudad"));
        verify(clientes, never()).insertar(any(), any(), any(), any());
    }

    @Test
    void laIdentificacionEsUnicaPorEmpresa() {
        when(clientes.existeOtroConLaIdentificacion(EMPRESA, TipoDocumento.CC, "1098765432", 0))
                .thenReturn(true);

        RegistroRepetidoException e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.crear(solicitud("Bucaramanga", true)));

        assertFalse(e.getMessage().contains("1098765432"), "sin eco del dato enviado");
        verify(clientes, never()).insertar(any(), any(), any(), any());
    }

    @Test
    void elDocumentoGenericoNoSePuedeRegistrarComoOtroCliente() {
        // El consumidor final ya ocupa ese documento en toda empresa.
        when(clientes.existeOtroConLaIdentificacion(EMPRESA, TipoDocumento.CC, "222222222222", 0))
                .thenReturn(true);

        assertThrows(RegistroRepetidoException.class, () -> servicio.crear(new SolicitudCliente(
                "Otro", TipoDocumento.CC, "222222222222", "otro@correo.co", null, null, "Girón", true)));
    }

    // ----------------------------------------------------------- consultar

    @Test
    void porIdentificacionDevuelveLosDatosGuardados() {
        when(clientes.buscarPorIdentificacion(EMPRESA, TipoDocumento.CC, "1098765432"))
                .thenReturn(Optional.of(MARIA));

        assertEquals(Optional.of(MARIA), servicio.porIdentificacion(TipoDocumento.CC, " 1098765432 "));
    }

    @Test
    void porIdentificacionDeUnoQueNoExisteVieneVacio() {
        when(clientes.buscarPorIdentificacion(EMPRESA, TipoDocumento.CE, "555")).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), servicio.porIdentificacion(TipoDocumento.CE, "555"));
    }

    @Test
    void porIdentificacionExigeLaIdentificacion() {
        assertThrows(FiltroInvalidoException.class, () -> servicio.porIdentificacion(TipoDocumento.CC, "  "));
        assertThrows(FiltroInvalidoException.class, () -> servicio.porIdentificacion(TipoDocumento.CC, null));
    }

    // ----------------------------------------------------------- actualizar

    @Test
    void actualizarGuardaLosDatosNuevosDeUnClienteDeLaEmpresa() {
        when(clientes.buscar(EMPRESA, 7)).thenReturn(Optional.of(MARIA));
        sinDuplicadoPara(7);

        servicio.actualizar(7, solicitud("Girón", true));

        ArgumentCaptor<Cliente> datos = ArgumentCaptor.forClass(Cliente.class);
        verify(clientes).actualizar(eq(EMPRESA), eq(7), datos.capture(), eq(AHORA), eq("CAJA"));
        assertEquals("Girón", datos.getValue().ciudad());
        assertEquals("María Pérez", datos.getValue().nombre());
    }

    private void sinDuplicadoPara(int id) {
        when(clientes.existeOtroConLaIdentificacion(EMPRESA, TipoDocumento.CC, "1098765432", id))
                .thenReturn(false);
    }

    @Test
    void actualizarUnClienteDeOtraEmpresaEs404SinEcoDelId() {
        when(clientes.buscar(EMPRESA, 99)).thenReturn(Optional.empty());

        RecursoNoEncontradoException e = assertThrows(RecursoNoEncontradoException.class,
                () -> servicio.actualizar(99, solicitud("Girón", true)));

        assertEquals("El cliente no existe.", e.getMessage());
        verify(clientes, never()).actualizar(any(), any(), any(), any(), any());
    }

    @Test
    void elConsumidorFinalNoSePuedeEditar() {
        when(clientes.buscar(EMPRESA, 1)).thenReturn(Optional.of(CONSUMIDOR_FINAL));

        assertThrows(ClienteNoEditableException.class, () -> servicio.actualizar(1, solicitud("Girón", true)));

        verify(clientes, never()).actualizar(any(), any(), any(), any(), any());
    }

    @Test
    void actualizarExigeLaAutorizacionDeDatos() {
        when(clientes.buscar(EMPRESA, 7)).thenReturn(Optional.of(MARIA));

        assertThrows(DatoDeClienteInvalidoException.class, () -> servicio.actualizar(7, solicitud("Girón", false)));

        verify(clientes, never()).actualizar(any(), any(), any(), any(), any());
    }

    @Test
    void actualizarNoPuedeQuitarleAOtroClienteSuDocumento() {
        when(clientes.buscar(EMPRESA, 7)).thenReturn(Optional.of(MARIA));
        when(clientes.existeOtroConLaIdentificacion(EMPRESA, TipoDocumento.CC, "1098765432", 7))
                .thenReturn(true);

        RegistroRepetidoException e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.actualizar(7, solicitud("Girón", true)));

        assertFalse(e.getMessage().contains("1098765432"), "sin eco del dato enviado");
        verify(clientes, never()).actualizar(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------ consumidor final

    @Test
    void cadaEmpresaTieneSuConsumidorFinal() {
        when(clientes.buscarConsumidorFinal(EMPRESA)).thenReturn(Optional.of(CONSUMIDOR_FINAL));

        Cliente c = servicio.consumidorFinal();

        assertTrue(c.consumidorFinal());
        assertEquals("222222222222", c.identificacion());
    }

    @Test
    void siFaltaElConsumidorFinalEsUnErrorDeConfiguracionYNoUnDatoDelCliente() {
        when(clientes.buscarConsumidorFinal(EMPRESA)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> servicio.consumidorFinal());
    }

    // ------------------------------------------------------------ domicilio

    @Test
    void domicilioExigeTelefonoDireccionYCiudad() {
        servicio.exigirDatosDeDomicilio(MARIA);

        Cliente sinTelefono = new Cliente(7, "María", TipoDocumento.CC, "1", "m@c.co", null, "Calle 5",
                "Girón", false);
        DatosDeDomicilioIncompletosException e = assertThrows(DatosDeDomicilioIncompletosException.class,
                () -> servicio.exigirDatosDeDomicilio(sinTelefono));
        assertTrue(e.getMessage().contains("teléfono"));
        assertFalse(e.getMessage().contains("dirección"), "solo nombra lo que falta: " + e.getMessage());
    }

    @Test
    void domicilioNombraTodoLoQueFalta() {
        Cliente vacio = new Cliente(7, "María", TipoDocumento.CC, "1", "m@c.co", "  ", "", null, false);

        DatosDeDomicilioIncompletosException e = assertThrows(DatosDeDomicilioIncompletosException.class,
                () -> servicio.exigirDatosDeDomicilio(vacio));

        assertTrue(e.getMessage().contains("teléfono"));
        assertTrue(e.getMessage().contains("dirección"));
        assertTrue(e.getMessage().contains("ciudad"));
    }

    @Test
    void elConsumidorFinalNuncaSirveParaDomicilio() {
        // Aunque tuviera todos los datos: el genérico no identifica a nadie a quien entregar.
        Cliente conTodo = new Cliente(1, "Consumidor final", TipoDocumento.CC, "222222222222", "c@c.co",
                "3001234567", "Calle 1", "Girón", true);

        assertThrows(DatosDeDomicilioIncompletosException.class,
                () -> servicio.exigirDatosDeDomicilio(conTodo));
        assertThrows(DatosDeDomicilioIncompletosException.class,
                () -> servicio.exigirDatosDeDomicilio(CONSUMIDOR_FINAL));
    }
}
