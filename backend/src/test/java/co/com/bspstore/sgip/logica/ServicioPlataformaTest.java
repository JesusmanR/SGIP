package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.EmpresaEnPlataforma;
import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.datos.RolRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.AccionPlataforma;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * El reloj no se simula con un mock de Mockito sino con
 * {@link Clock#fixed}: un mock devolvería nulo en {@code instant()} y
 * {@code LocalDate.now(reloj)} reventaría con un fallo que no tiene nada
 * que ver con lo que se está probando. Por eso el servicio se construye
 * a mano en lugar de con {@code @InjectMocks}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioPlataformaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 17);
    private static final int USUARIO_SUPER_ADMIN = 9;

    @Mock RepositorioPlataforma plataforma;
    @Mock UsuarioRepositorio usuarios;
    @Mock RolRepositorio roles;

    private ServicioPlataforma servicio;

    @BeforeEach
    void preparar() {
        Clock reloj = Clock.fixed(
                ZonedDateTime.of(HOY.atTime(10, 0), ZoneId.of("America/Bogota")).toInstant(),
                ZoneId.of("America/Bogota"));
        servicio = new ServicioPlataforma(plataforma, usuarios, roles, reloj);
        comoSuperAdmin();
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    /** El super administrador no tiene empresa: es lo que dice la RN-17. */
    private void comoSuperAdmin() {
        var u = new UsuarioAutenticado(USUARIO_SUPER_ADMIN, "plataforma@bsp.co", "SUPER_ADMIN", null);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private SolicitudAltaEmpresa solicitud() {
        return new SolicitudAltaEmpresa(
                "900123456-1", "Ferretería El Tornillo S.A.S.", "contacto@tornillo.co",
                "3001234567", "Bucaramanga", null,
                "Ana Pérez", "Ana@Tornillo.CO", "clave-de-prueba");
    }

    private void altaViable(int empresaId, int mesesPrueba) {
        when(plataforma.existeNit(anyString())).thenReturn(false);
        when(usuarios.existsByCorreo(anyString())).thenReturn(false);
        when(plataforma.insertarEmpresa(anyString(), anyString(), any(), any(), any()))
                .thenReturn(empresaId);
        when(plataforma.mesesDePrueba()).thenReturn(mesesPrueba);
        when(roles.findByNombre("ADMINISTRADOR"))
                .thenReturn(Optional.of(new Rol("ADMINISTRADOR", "Operación del negocio")));
        when(plataforma.buscar(empresaId)).thenReturn(Optional.of(empresaEnPlataforma(empresaId)));
    }

    private EmpresaEnPlataforma empresaEnPlataforma(int id) {
        return new EmpresaEnPlataforma(id, "900123456-1", "Ferretería El Tornillo S.A.S.",
                "contacto@tornillo.co", "3001234567", "Bucaramanga", "PRUEBA",
                HOY.atStartOfDay(), "BASICO", HOY, HOY.plusMonths(1), "PRUEBA", 1);
    }

    // ------------------------------------------------------------ RF-01

    @Test
    void daDeAltaLaEmpresaEnPruebaConSuSuscripcionYSuConfiguracion() {
        altaViable(12, 1);

        EmpresaEnPlataforma creada = servicio.darDeAlta(solicitud());

        assertEquals(Integer.valueOf(12), creada.empresaId());
        verify(plataforma).insertarEmpresa("900123456-1", "Ferretería El Tornillo S.A.S.",
                "contacto@tornillo.co", "3001234567", "Bucaramanga");
        // El plan llegó nulo y la suscripción arranca hoy, por un mes:
        // el RF-04 lo pide «hasta el mismo día del mes siguiente», que
        // con HOY = 17 de octubre es el 17 de noviembre. Treinta días
        // habrían dado el 16, y esta prueba lo daba por bueno porque
        // estaba escrita desde el código y no desde el criterio.
        verify(plataforma).insertarSuscripcion(12, "BASICO", HOY, HOY.plusMonths(1));
        verify(plataforma).insertarConfiguracion(12);
    }

    /**
     * El mismo servicio con el reloj puesto en otro día.
     *
     * Las fechas de frontera del RF-04 no se pueden probar con el reloj
     * de {@code preparar()}: el 17 de octubre cae en un mes de treinta y
     * un días y oculta justo los casos que importan.
     */
    private ServicioPlataforma servicioEl(LocalDate dia) {
        Clock reloj = Clock.fixed(
                ZonedDateTime.of(dia.atTime(10, 0), ZoneId.of("America/Bogota")).toInstant(),
                ZoneId.of("America/Bogota"));
        return new ServicioPlataforma(plataforma, usuarios, roles, reloj);
    }

    /**
     * Las fechas en las que treinta días y un mes no coinciden.
     *
     * De las 365 fechas de alta posibles de un año, 240 daban una fecha
     * de vencimiento distinta de la que el criterio del RF-04 pide. Estas
     * tres son las formas que toma esa diferencia: el mes de treinta y un
     * días, el salto sobre febrero y el día que no existe en el mes
     * siguiente.
     */
    @Test
    void laPruebaVenceElMismoDiaDelMesSiguiente() {
        String[][] casos = {
                // alta           vencimiento     lo que daban treinta días
                {"2026-01-01", "2026-02-01"},   // 2026-01-31
                {"2026-03-01", "2026-04-01"},   // 2026-03-31
                {"2026-05-15", "2026-06-15"},   // 2026-06-14
                // El 31 de febrero no existe: el único vencimiento posible
                // es el último día que sí existe. Treinta días daban el 2
                // de marzo, que ya es el mes de después.
                {"2026-01-31", "2026-02-28"},
                // En un mes de treinta días las dos cuentas coinciden, y
                // el criterio se sigue cumpliendo.
                {"2026-04-10", "2026-05-10"},
        };
        altaViable(12, 1);

        for (String[] caso : casos) {
            LocalDate dia = LocalDate.parse(caso[0]);
            servicioEl(dia).darDeAlta(solicitud());
            verify(plataforma).insertarSuscripcion(
                    12, "BASICO", dia, LocalDate.parse(caso[1]));
        }
    }

    @Test
    void unPeriodoDeVariosMesesTambienCaeEnElMismoDia() {
        altaViable(12, 3);

        servicioEl(LocalDate.of(2026, 11, 30)).darDeAlta(solicitud());

        verify(plataforma).insertarSuscripcion(
                12, "BASICO", LocalDate.of(2026, 11, 30), LocalDate.of(2027, 2, 28));
    }

    @Test
    void elAdministradorInicialQuedaLigadoALaEmpresaRecienCreada() {
        altaViable(12, 1);

        servicio.darDeAlta(solicitud());

        ArgumentCaptor<Usuario> capturado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).save(capturado.capture());
        Usuario administrador = capturado.getValue();
        assertEquals(Integer.valueOf(12), administrador.getEmpresaId());
        // El correo se normaliza: llegó con mayúsculas.
        assertEquals("ana@tornillo.co", administrador.getCorreo());
        // Nace obligado a cambiar la clave con la que lo dieron de alta.
        assertTrue(administrador.isCambioClaveRequerido());
    }

    @Test
    void laClaveDelAdministradorSeGuardaDerivadaYNoEnClaro() {
        altaViable(12, 1);

        servicio.darDeAlta(solicitud());

        ArgumentCaptor<Usuario> capturado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).save(capturado.capture());
        String hash = capturado.getValue().getClaveHash();
        assertNotEquals("clave-de-prueba", hash);
        assertTrue(Seguridad.verificar("clave-de-prueba", hash),
                "el hash guardado debe verificar la clave con la que se dio de alta");
    }

    @Test
    void elAltaQuedaEnLaBitacoraConSuResponsable() {
        altaViable(12, 1);

        servicio.darDeAlta(solicitud());

        verify(plataforma).registrar(eq(USUARIO_SUPER_ADMIN), eq(12),
                eq(AccionPlataforma.EMPRESA_ALTA), eq("empresas"), eq("900123456-1"),
                isNull(), eq("PRUEBA"), anyString());
    }

    @Test
    void elPeriodoDePruebaSaleDelParametroVigenteYNoDeUnaConstante() {
        altaViable(12, 45);

        servicio.darDeAlta(solicitud());

        verify(plataforma).insertarSuscripcion(12, "BASICO", HOY, HOY.plusDays(45));
    }

    @Test
    void rechazaLaIdentificacionTributariaYaRegistrada() {
        when(plataforma.existeNit("900123456-1")).thenReturn(true);

        RegistroRepetidoException e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.darDeAlta(solicitud()));

        assertTrue(e.getMessage().contains("900123456-1"),
                "el mensaje debe decir cuál de los dos datos choca");
        // Nada se escribió: ni la empresa, ni el usuario, ni la bitácora.
        verify(plataforma, never()).insertarEmpresa(anyString(), anyString(), any(), any(), any());
        verifyNoInteractions(usuarios);
    }

    @Test
    void rechazaElCorreoDeAdministradorQueYaPerteneceAOtroUsuario() {
        when(plataforma.existeNit(anyString())).thenReturn(false);
        when(usuarios.existsByCorreo("ana@tornillo.co")).thenReturn(true);

        RegistroRepetidoException e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.darDeAlta(solicitud()));

        assertTrue(e.getMessage().contains("ana@tornillo.co"));
        verify(plataforma, never()).insertarEmpresa(anyString(), anyString(), any(), any(), any());
        verify(usuarios, never()).save(any());
    }

    // ------------------------------------------------------- RF-02 y RF-03

    @Test
    void activarAnotaElEstadoAnteriorQueDespuesDelUpdateYaNoExistiria() {
        when(plataforma.estadoDe(5)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));
        when(plataforma.buscar(5)).thenReturn(Optional.of(empresaEnPlataforma(5)));

        assertTrue(servicio.activar(5, "pago recibido").isPresent());

        verify(plataforma).cambiarEstado(5, EstadoEmpresa.ACTIVA);
        verify(plataforma).registrar(USUARIO_SUPER_ADMIN, 5,
                AccionPlataforma.EMPRESA_ACTIVACION, "empresas", "5",
                "PRUEBA", "ACTIVA", "pago recibido");
    }

    @Test
    void desactivarDejaLaEmpresaEnModoDeConsulta() {
        when(plataforma.estadoDe(5)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
        when(plataforma.buscar(5)).thenReturn(Optional.of(empresaEnPlataforma(5)));

        servicio.desactivar(5, "suscripción vencida");

        verify(plataforma).cambiarEstado(5, EstadoEmpresa.DESACTIVADA);
        verify(plataforma).registrar(USUARIO_SUPER_ADMIN, 5,
                AccionPlataforma.EMPRESA_DESACTIVACION, "empresas", "5",
                "ACTIVA", "DESACTIVADA", "suscripción vencida");
    }

    @Test
    void pedirElEstadoEnElQueLaEmpresaYaEstaNoCambiaNadaNiEnsuciaLaBitacora() {
        when(plataforma.estadoDe(5)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
        when(plataforma.buscar(5)).thenReturn(Optional.of(empresaEnPlataforma(5)));

        assertTrue(servicio.activar(5, null).isPresent(), "la operación no falla, no cambia nada");

        verify(plataforma, never()).cambiarEstado(anyInt(), any());
        verify(plataforma, never()).registrar(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unaEmpresaQueNoExisteDevuelveVacioYNoEscribeNada() {
        when(plataforma.estadoDe(404)).thenReturn(Optional.empty());

        assertTrue(servicio.activar(404, null).isEmpty());
        assertTrue(servicio.desactivar(404, null).isEmpty());

        verify(plataforma, never()).cambiarEstado(anyInt(), any());
        verify(plataforma, never()).registrar(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void elMotivoVacioSeGuardaComoNuloYNoComoCadenaEnBlanco() {
        when(plataforma.estadoDe(5)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
        when(plataforma.buscar(5)).thenReturn(Optional.of(empresaEnPlataforma(5)));

        servicio.desactivar(5, "   ");

        ArgumentCaptor<String> motivo = ArgumentCaptor.forClass(String.class);
        verify(plataforma).registrar(any(), any(), any(), any(), any(), any(), any(),
                motivo.capture());
        assertNull(motivo.getValue());
    }
}
