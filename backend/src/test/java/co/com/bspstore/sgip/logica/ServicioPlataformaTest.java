package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.EmpresaEnPlataforma;
import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.api.dto.UsuarioEnPlataforma;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.datos.RolRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.AccionPlataforma;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import co.com.bspstore.sgip.modelo.TipoMedio;
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
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private static final int MINUTOS_DE_BLOQUEO = 15;

    @Mock RepositorioPlataforma plataforma;
    @Mock UsuarioRepositorio usuarios;
    @Mock RolRepositorio roles;

    private ServicioPlataforma servicio;

    @BeforeEach
    void preparar() {
        Clock reloj = Clock.fixed(
                ZonedDateTime.of(HOY.atTime(10, 0), ZoneId.of("America/Bogota")).toInstant(),
                ZoneId.of("America/Bogota"));
        servicio = new ServicioPlataforma(plataforma, usuarios, roles, reloj, new PoliticaDeBloqueo(MINUTOS_DE_BLOQUEO));
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

    private void altaViable(int empresaId, int diasPrueba) {
        when(plataforma.existeNit(anyString())).thenReturn(false);
        when(usuarios.existsByCorreo(anyString())).thenReturn(false);
        when(plataforma.insertarEmpresa(anyString(), anyString(), any(), any(), any()))
                .thenReturn(empresaId);
        when(plataforma.diasDePrueba(any())).thenReturn(diasPrueba);
        when(roles.findByNombre("ADMINISTRADOR"))
                .thenReturn(Optional.of(new Rol("ADMINISTRADOR", "Operación del negocio")));
        when(plataforma.buscar(empresaId)).thenReturn(Optional.of(empresaEnPlataforma(empresaId)));
    }

    private EmpresaEnPlataforma empresaEnPlataforma(int id) {
        return new EmpresaEnPlataforma(id, "900123456-1", "Ferretería El Tornillo S.A.S.",
                "contacto@tornillo.co", "3001234567", "Bucaramanga", "PRUEBA",
                HOY.atStartOfDay(), "BASICO", HOY, HOY.plusDays(30), "PRUEBA", 1);
    }

    // ------------------------------------------------------------ RF-01

    @Test
    void daDeAltaLaEmpresaEnPruebaConSuSuscripcionYSuConfiguracion() {
        altaViable(12, 30);

        EmpresaEnPlataforma creada = servicio.darDeAlta(solicitud());

        assertEquals(Integer.valueOf(12), creada.empresaId());
        verify(plataforma).insertarEmpresa("900123456-1", "Ferretería El Tornillo S.A.S.",
                "contacto@tornillo.co", "3001234567", "Bucaramanga");
        // El plan llegó nulo y la suscripción arranca hoy. La prueba son
        // treinta días contados desde mañana (RF-04 de la v4, ADR-0031):
        // con HOY = 17 de octubre, el último día es el 16 de noviembre.
        verify(plataforma).insertarSuscripcion(12, "BASICO", HOY, LocalDate.of(2026, 11, 16));
        verify(plataforma).insertarConfiguracion(12);
    }

    /**
     * Una empresa nueva tiene que poder cobrar desde el primer día, y el
     * punto de venta pide un medio de pago en cada venta. Sin estas filas el
     * comercio abre su primera pantalla de venta con la lista vacía y nada
     * que hacer (RF-43, M17).
     */
    @Test
    void elAltaSiembraLosMediosDePago() {
        altaViable(12, 30);

        servicio.darDeAlta(solicitud());

        verify(plataforma).sembrarMediosDePago(12, ServicioMediosPago.MEDIOS_DEL_ALTA);
    }

    @Test
    void elAltaSiembraEfectivoTarjetaYTransferencia() {
        altaViable(12, 30);

        servicio.darDeAlta(solicitud());

        ArgumentCaptor<List<MedioDePago>> sembrados = ArgumentCaptor.captor();
        verify(plataforma).sembrarMediosDePago(eq(12), sembrados.capture());
        assertEquals(List.of(
                        new MedioDePago(null, "Efectivo", TipoMedio.EFECTIVO, true),
                        new MedioDePago(null, "Tarjeta", TipoMedio.TARJETA, true),
                        new MedioDePago(null, "Transferencia", TipoMedio.TRANSFERENCIA, true)),
                sembrados.getValue().stream().filter(MedioDePago::activo).toList(),
                "los activos del alta son exactamente estos tres");
    }

    @Test
    void elAltaSiembraElSaldoAFavorInactivo() {
        altaViable(12, 30);

        servicio.darDeAlta(solicitud());

        ArgumentCaptor<List<MedioDePago>> sembrados = ArgumentCaptor.captor();
        verify(plataforma).sembrarMediosDePago(eq(12), sembrados.capture());
        assertEquals(List.of(new MedioDePago(null, "Saldo a favor", TipoMedio.SALDO_A_FAVOR, false)),
                sembrados.getValue().stream().filter(m -> !m.activo()).toList());
        assertEquals(4, sembrados.getValue().size());
    }

    /**
     * La primera venta del mostrador suele ser sin datos del comprador, y la venta
     * exige un cliente: el alta deja el «Consumidor final» (CC 222222222222, el
     * documento genérico de la DIAN) con la ciudad de la empresa (RF-66).
     */
    @Test
    void elAltaSiembraElConsumidorFinalConLaCiudadDeLaEmpresa() {
        altaViable(12, 30);

        servicio.darDeAlta(solicitud());

        verify(plataforma).sembrarConsumidorFinal(12, "Consumidor final", TipoDocumento.CC,
                "222222222222", "Bucaramanga");
    }

    @Test
    void elAltaSinCiudadSiembraElConsumidorFinalSinCiudad() {
        altaViable(12, 30);
        var sinCiudad = new SolicitudAltaEmpresa(
                "900123456-1", "Ferretería El Tornillo S.A.S.", "contacto@tornillo.co",
                "3001234567", "  ", null, "Ana Pérez", "Ana@Tornillo.CO", "clave-de-prueba");

        servicio.darDeAlta(sinCiudad);

        verify(plataforma).sembrarConsumidorFinal(12, "Consumidor final", TipoDocumento.CC,
                "222222222222", null);
    }

    @Test
    void siElConsumidorFinalFallaElAltaSePropagaYNoSeAudita() {
        altaViable(12, 1);
        doThrow(new IllegalStateException("siembra rota"))
                .when(plataforma).sembrarConsumidorFinal(eq(12), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> servicio.darDeAlta(solicitud()));

        verify(plataforma, never()).registrar(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Lo que el alta deliberadamente NO siembra. El margen lo decide el
     * comercio producto por producto y el impuesto es opcional (ADR-0024):
     * sembrar reglas «por omisión» pondría a la empresa nueva a cobrar un
     * margen y un IVA que nadie le pidió. La configuración nace como fila
     * vacía, una sola vez, y ninguna otra escritura de reglas ocurre.
     */
    @Test
    void elAltaNoSiembraMargenesNiImpuestos() {
        altaViable(12, 30);

        servicio.darDeAlta(solicitud());

        verify(plataforma, times(1)).insertarConfiguracion(12);
        List<String> escrituras = mockingDetails(plataforma).getInvocations().stream()
                .map(i -> i.getMethod().getName())
                .filter(n -> n.startsWith("insertar") || n.startsWith("sembrar"))
                .toList();
        assertEquals(
                List.of("insertarEmpresa", "insertarSuscripcion", "insertarConfiguracion",
                        "sembrarMediosDePago", "sembrarConsumidorFinal"),
                escrituras,
                "el alta solo escribe la empresa, la suscripción, la configuración vacía, "
                        + "los medios de pago y el consumidor final; ni márgenes ni reglas de IVA");
    }

    /**
     * La siembra va en la misma transacción que el resto del alta: si
     * falla, la excepción sube sin que nadie la trague (y el
     * {@code @Transactional} deshace la empresa entera), y la bitácora no
     * anota un alta que no ocurrió.
     */
    @Test
    void siLaSiembraFallaElAltaSePropagaYNoSeAudita() {
        altaViable(12, 1);
        doThrow(new IllegalStateException("siembra rota"))
                .when(plataforma).sembrarMediosDePago(eq(12), any());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> servicio.darDeAlta(solicitud()));

        assertEquals("siembra rota", e.getMessage());
        verify(plataforma, never()).registrar(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void elAdministradorInicialQuedaLigadoALaEmpresaRecienCreada() {
        altaViable(12, 30);

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
        altaViable(12, 30);

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
        altaViable(12, 30);

        servicio.darDeAlta(solicitud());

        verify(plataforma).registrar(eq(USUARIO_SUPER_ADMIN), eq(12),
                eq(AccionPlataforma.EMPRESA_ALTA), eq("empresas"), eq("900123456-1"),
                isNull(), eq("PRUEBA"), anyString());
    }

    @Test
    void elPeriodoDePruebaSaleDelParametroVigenteYNoDeUnaConstante() {
        // Cuarenta y cinco, que no es el valor por omisión: si el alta lo
        // ignorara y usara la constante, la fecha saldría a treinta días.
        altaViable(12, 45);

        servicio.darDeAlta(solicitud());

        verify(plataforma).insertarSuscripcion(12, "BASICO", HOY, LocalDate.of(2026, 12, 1));
    }

    /**
     * Los siete casos del RF-04 por el alta de verdad, con el reloj fijo de
     * noche en Bogotá y en UTC, cuando la JVM de la CI ya está en el día
     * siguiente: el «hoy» del alta es el de Bogotá.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "alta el {0}, vence el {1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "2026-01-29, 2026-02-28",
            "2026-01-30, 2026-03-01",
            "2026-01-31, 2026-03-02",
            "2028-01-30, 2028-02-29",
            "2028-01-31, 2028-03-01",
            "2026-03-15, 2026-04-14",
            "2026-03-31, 2026-04-30"})
    void elAltaVenceTreintaDiasDespuesContandoDesdeElDiaSiguiente(LocalDate alta, LocalDate vence) {
        Clock deNoche = Clock.fixed(
                ZonedDateTime.of(alta.atTime(22, 0), ZoneId.of("America/Bogota")).toInstant(),
                java.time.ZoneOffset.UTC);
        servicio = new ServicioPlataforma(plataforma, usuarios, roles, deNoche,
                new PoliticaDeBloqueo(MINUTOS_DE_BLOQUEO));
        altaViable(12, 30);

        servicio.darDeAlta(solicitud());

        verify(plataforma).insertarSuscripcion(12, "BASICO", alta, vence);
        verify(plataforma).diasDePrueba(alta);
    }

    @Test
    void rechazaLaIdentificacionTributariaYaRegistrada() {
        when(plataforma.existeNit("900123456-1")).thenReturn(true);

        RegistroRepetidoException e = assertThrows(NitRepetidoException.class,
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

        RegistroRepetidoException e = assertThrows(CorreoRepetidoException.class,
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

    // ------------------------------------------- desbloqueo de un usuario (I2)

    private Usuario usuarioBloqueado() {
        Usuario u = new Usuario(new Rol("ADMINISTRADOR", "Operación del negocio"),
                "Ana Pérez", "ana@tornillo.co", "hash");
        u.registrarIntentoFallido(1, java.time.LocalDateTime.of(2026, 10, 17, 9, 50));
        assertTrue(u.isBloqueado(), "el usuario llega bloqueado");
        return u;
    }

    @Test
    void desbloqueaAUnUsuarioDeLaEmpresaIndicadaYLoDejaEnLaBitacora() {
        Usuario u = usuarioBloqueado();
        when(usuarios.findByIdAndEmpresaId(20, 4)).thenReturn(Optional.of(u));

        servicio.desbloquearUsuario(4, 20);

        assertTrue(!u.isBloqueado());
        assertEquals(0, u.getIntentosFallidos());
        assertNull(u.getBloqueadoEn());
        verify(plataforma).registrar(USUARIO_SUPER_ADMIN, 4, AccionPlataforma.USUARIO_DESBLOQUEO,
                "usuarios", "20", "BLOQUEADO", "DESBLOQUEADO", null);
    }

    /** La referencia es el identificador: el correo mide hasta 120 y la columna admite 60. */
    @Test
    void laBitacoraNombraAlUsuarioPorSuIdentificadorYNoPorSuCorreo() {
        when(usuarios.findByIdAndEmpresaId(20, 4)).thenReturn(Optional.of(usuarioBloqueado()));

        servicio.desbloquearUsuario(4, 20);

        verify(plataforma).registrar(anyInt(), anyInt(), any(), anyString(), eq("20"),
                anyString(), anyString(), any());
    }

    @Test
    void unUsuarioDeOtraEmpresaSeTrataComoUnoInexistente() {
        when(usuarios.findByIdAndEmpresaId(20, 4)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class, () -> servicio.desbloquearUsuario(4, 20));

        verify(usuarios, never()).findById(any());
        verify(plataforma, never()).registrar(anyInt(), any(), any(), anyString(), anyString(),
                any(), any(), any());
    }

    /** Como activar una empresa que ya está activa: no cambia nada y no ensucia la bitácora. */
    @Test
    void desbloquearAQuienNoEstaBloqueadoNoDejaRastro() {
        Usuario u = new Usuario(new Rol("ADMINISTRADOR", "Operación del negocio"),
                "Ana Pérez", "ana@tornillo.co", "hash");
        when(usuarios.findByIdAndEmpresaId(20, 4)).thenReturn(Optional.of(u));

        servicio.desbloquearUsuario(4, 20);

        verify(plataforma, never()).registrar(anyInt(), any(), any(), anyString(), anyString(),
                any(), any(), any());
    }

    // ------------------------------------------- usuarios de una empresa

    /** El reloj fijo marca las 10:00; el instante del bloqueo se da en minutos antes de eso. */
    private Usuario usuarioBloqueadoHace(String nombre, Integer minutos) {
        Usuario u = new Usuario(new Rol("VENDEDOR", "Ventas"), nombre,
                nombre.toLowerCase() + "@tornillo.co", "hash-que-no-sale");
        if (minutos != null) {
            LocalDateTime instante = HOY.atTime(10, 0).minusMinutes(minutos);
            for (int i = 0; i < 5; i++) {
                u.registrarIntentoFallido(5, instante);
            }
        }
        return u;
    }

    @Test
    void sinBloqueoNoApareceBloqueado() {
        when(usuarios.listarConRolDeLaEmpresa(4)).thenReturn(List.of(usuarioBloqueadoHace("Ana", null)));

        List<UsuarioEnPlataforma> lista = servicio.usuariosDe(4);

        assertEquals(1, lista.size());
        assertEquals(false, lista.get(0).bloqueado());
        assertEquals("VENDEDOR", lista.get(0).rol());
        assertEquals("ana@tornillo.co", lista.get(0).correo());
    }

    @Test
    void unBloqueoDentroDelPlazoApareceBloqueado() {
        when(usuarios.listarConRolDeLaEmpresa(4)).thenReturn(List.of(usuarioBloqueadoHace("Ana", 1)));

        assertTrue(servicio.usuariosDe(4).get(0).bloqueado());
    }

    /** El último minuto del plazo: a los 15 exactos ya puede entrar, como en el ingreso. */
    @Test
    void elBloqueoCaducaAlCumplirseElPlazoExacto() {
        when(usuarios.listarConRolDeLaEmpresa(4)).thenReturn(List.of(
                usuarioBloqueadoHace("Dentro", 14), usuarioBloqueadoHace("Justo", 15)));

        List<UsuarioEnPlataforma> lista = servicio.usuariosDe(4);

        assertTrue(lista.get(0).bloqueado());
        assertEquals(false, lista.get(1).bloqueado());
    }

    @Test
    void unBloqueoVencidoYaNoApareceBloqueado() {
        when(usuarios.listarConRolDeLaEmpresa(4)).thenReturn(List.of(usuarioBloqueadoHace("Ana", 16)));

        assertEquals(false, servicio.usuariosDe(4).get(0).bloqueado());
    }

    /** Un bloqueo anterior a la V37 no dice desde cuándo: se toma por vencido, como en el ingreso. */
    @Test
    void unBloqueoSinInstanteSeTomaPorVencido() {
        Usuario u = usuarioBloqueadoHace("Ana", 1);
        org.springframework.test.util.ReflectionTestUtils.setField(u, "bloqueadoEn", null);
        when(usuarios.listarConRolDeLaEmpresa(4)).thenReturn(List.of(u));

        assertEquals(false, servicio.usuariosDe(4).get(0).bloqueado());
    }

    @Test
    void laListaNoModificaALosUsuarios() {
        Usuario u = usuarioBloqueadoHace("Ana", 16);
        when(usuarios.listarConRolDeLaEmpresa(4)).thenReturn(List.of(u));

        servicio.usuariosDe(4);

        // Vencido pero aún marcado: leer la lista no lo limpia, eso lo hace el ingreso.
        assertTrue(u.isBloqueado());
        verify(usuarios, never()).save(any());
    }

    @Test
    void sinUsuariosEnUnaEmpresaQueNoExisteEsUn404() {
        when(usuarios.listarConRolDeLaEmpresa(99)).thenReturn(List.of());
        when(plataforma.estadoDe(99)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class, () -> servicio.usuariosDe(99));
    }

    @Test
    void unaEmpresaSinUsuariosDevuelveUnaListaVacia() {
        when(usuarios.listarConRolDeLaEmpresa(4)).thenReturn(List.of());
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));

        assertTrue(servicio.usuariosDe(4).isEmpty());
    }

    /** Con usuarios, la empresa existe: no hace falta una segunda consulta para saberlo. */
    @Test
    void conUsuariosNoPreguntaPorLaEmpresa() {
        when(usuarios.listarConRolDeLaEmpresa(4)).thenReturn(List.of(usuarioBloqueadoHace("Ana", null)));

        servicio.usuariosDe(4);

        verify(plataforma, never()).estadoDe(any());
    }
}
