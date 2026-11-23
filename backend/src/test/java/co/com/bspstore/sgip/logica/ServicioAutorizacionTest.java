package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.AdministradorParaAutorizar;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPin;
import co.com.bspstore.sgip.modelo.MotivoRechazo;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La autorización del administrador con PIN (ADR-0032), sin base: el repositorio
 * es uno en memoria que hace lo que hacen sus sentencias (filtrar por empresa,
 * activo, rol y PIN) y apunta qué filas «bloqueó» y qué rechazos anotó. Lo que la
 * base añade (el bloqueo real de la fila, la transacción propia) lo prueban
 * {@code PinConBaseTest} y {@code PinEsquemaConBaseTest}.
 */
class ServicioAutorizacionTest {

    private static final int EMPRESA = 4;
    private static final int OTRA_EMPRESA = 5;

    private static final int ANA = 10;          // administradora con PIN
    private static final int BETO = 11;         // administrador con PIN
    private static final int SIN_PIN = 12;      // administrador sin PIN
    private static final int INACTIVO = 13;     // administrador con PIN, inactivo
    private static final int NUEVO = 14;        // administrador que aún no fija su PIN
    private static final int VENDEDORA = 20;
    private static final int OTRO_VENDEDOR = 21;
    private static final int AJENO = 30;        // administrador de otra empresa, con PIN

    private static final String PIN_ANA = "4821";
    private static final String PIN_BETO = "7391";
    private static final String PIN_AJENO = "5555";
    private static final String ERRADO = "0000";

    private static String hashAna;
    private static String hashBeto;
    private static String hashAjeno;
    private static String hashDeClave;

    @BeforeAll
    static void derivarUnaVez() {
        // PBKDF2 es lento a propósito: se deriva una vez y no en cada prueba.
        hashAna = Seguridad.derivar(PIN_ANA);
        hashBeto = Seguridad.derivar(PIN_BETO);
        hashAjeno = Seguridad.derivar(PIN_AJENO);
        hashDeClave = Seguridad.derivar("SgipDev2026");
    }

    // ------------------------------------------------------------ los dobles

    /** Un reloj que la prueba adelanta a mano; un mock devolvería nulo en instant(). */
    private static final class RelojAjustable extends Clock {
        private Instant ahora = Instant.parse("2026-11-09T15:00:00Z");

        void adelantar(long segundos) {
            ahora = ahora.plusSeconds(segundos);
        }

        LocalDateTime ahoraLocal() {
            return LocalDateTime.now(this);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    private static final class Cuenta {
        final int id;
        final int empresa;
        final String rol;
        final String nombre;
        boolean activo = true;
        String claveHash = hashDeClave;
        String pinHash;
        int intentos;
        LocalDateTime bloqueadoEn;
        int errados;
        LocalDateTime emisionBloqueadaEn;

        Cuenta(int id, int empresa, String rol, String nombre) {
            this.id = id;
            this.empresa = empresa;
            this.rol = rol;
            this.nombre = nombre;
        }

        String correo() {
            return nombre.toLowerCase().replace(' ', '.') + "@tienda.co";
        }
    }

    private record Rechazo(Integer usuarioId, MotivoRechazo motivo, String origen) {
    }

    /** El repositorio de PIN con las mismas condiciones que sus sentencias, en memoria. */
    private static final class PinesEnMemoria extends RepositorioPin {
        final Map<Integer, Cuenta> cuentas = new HashMap<>();
        final List<Rechazo> rechazos = new ArrayList<>();
        /** Las filas que se tomaron con FOR UPDATE, en orden. */
        final List<Integer> bloqueadas = new ArrayList<>();
        int accesos;

        PinesEnMemoria() {
            super(null);
        }

        Cuenta cuenta(int id) {
            return cuentas.get(id);
        }

        private static UsuarioConPin fila(Cuenta c) {
            return new UsuarioConPin(c.id, c.nombre, c.correo(), c.claveHash, c.pinHash,
                    c.intentos, c.bloqueadoEn, c.errados, c.emisionBloqueadaEn);
        }

        private boolean esAdminConPin(Cuenta c, Integer empresa) {
            return c != null && c.empresa == empresa && c.activo
                    && "ADMINISTRADOR".equals(c.rol) && c.pinHash != null;
        }

        @Override
        public Optional<UsuarioConPin> bloquearUsuario(Integer usuarioId, Integer empresaId) {
            accesos++;
            Cuenta c = cuentas.get(usuarioId);
            if (c == null || c.empresa != empresaId) {
                return Optional.empty();
            }
            bloqueadas.add(usuarioId);
            return Optional.of(fila(c));
        }

        @Override
        public boolean esAdministradorConPin(Integer usuarioId, Integer empresaId) {
            accesos++;
            return esAdminConPin(cuentas.get(usuarioId), empresaId);
        }

        @Override
        public Optional<UsuarioConPin> bloquearAdministradorConPin(Integer usuarioId, Integer empresaId) {
            accesos++;
            Cuenta c = cuentas.get(usuarioId);
            if (!esAdminConPin(c, empresaId)) {
                return Optional.empty();
            }
            bloqueadas.add(usuarioId);
            return Optional.of(fila(c));
        }

        @Override
        public boolean tienePin(Integer usuarioId, Integer empresaId) {
            accesos++;
            Cuenta c = cuentas.get(usuarioId);
            return c != null && c.empresa == empresaId && c.pinHash != null;
        }

        @Override
        public List<AdministradorParaAutorizar> administradoresConPin(Integer empresaId) {
            accesos++;
            return cuentas.values().stream()
                    .filter(c -> esAdminConPin(c, empresaId))
                    .sorted(java.util.Comparator.comparing((Cuenta c) -> c.nombre).thenComparingInt(c -> c.id))
                    .map(c -> new AdministradorParaAutorizar(c.id, c.nombre))
                    .toList();
        }

        @Override
        public void guardarContadorDelPin(Integer usuarioId, Integer empresaId, int intentos,
                                          LocalDateTime bloqueadoEn) {
            Cuenta c = cuentas.get(usuarioId);
            c.intentos = intentos;
            c.bloqueadoEn = bloqueadoEn;
        }

        @Override
        public void guardarContadorEmitido(Integer usuarioId, Integer empresaId, int errados,
                                           LocalDateTime bloqueadaEn) {
            Cuenta c = cuentas.get(usuarioId);
            c.errados = errados;
            c.emisionBloqueadaEn = bloqueadaEn;
        }

        @Override
        public void guardarPin(Integer usuarioId, Integer empresaId, String pinHash) {
            Cuenta c = cuentas.get(usuarioId);
            c.pinHash = pinHash;
            c.intentos = 0;
            c.bloqueadoEn = null;
        }

        @Override
        public void registrarRechazo(Integer usuarioId, String correo, MotivoRechazo motivo, String origen) {
            rechazos.add(new Rechazo(usuarioId, motivo, origen));
        }

        long rechazosDe(int usuarioId, MotivoRechazo motivo) {
            return rechazos.stream()
                    .filter(r -> r.usuarioId() == usuarioId && r.motivo() == motivo).count();
        }
    }

    private PinesEnMemoria pines;
    private RelojAjustable reloj;
    private ServicioAutorizacion servicio;

    @BeforeEach
    void prepararLaEmpresa() {
        pines = new PinesEnMemoria();
        reloj = new RelojAjustable();
        servicio = new ServicioAutorizacion(pines, new PoliticaDeBloqueo(15), reloj, 5);

        agregar(ANA, EMPRESA, "ADMINISTRADOR", "Ana Admin").pinHash = hashAna;
        agregar(BETO, EMPRESA, "ADMINISTRADOR", "Beto Admin").pinHash = hashBeto;
        agregar(SIN_PIN, EMPRESA, "ADMINISTRADOR", "Sin Pin");
        agregar(INACTIVO, EMPRESA, "ADMINISTRADOR", "Inactivo Admin").pinHash = hashAna;
        pines.cuenta(INACTIVO).activo = false;
        agregar(NUEVO, EMPRESA, "ADMINISTRADOR", "Nuevo Admin");
        agregar(VENDEDORA, EMPRESA, "VENDEDOR", "Vera Vendedora");
        agregar(OTRO_VENDEDOR, EMPRESA, "VENDEDOR", "Otto Vendedor");
        agregar(AJENO, OTRA_EMPRESA, "ADMINISTRADOR", "Ajeno Admin").pinHash = hashAjeno;

        comoVendedora();
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private Cuenta agregar(int id, int empresa, String rol, String nombre) {
        Cuenta c = new Cuenta(id, empresa, rol, nombre);
        pines.cuentas.put(id, c);
        return c;
    }

    private void como(int id) {
        Cuenta c = pines.cuenta(id);
        var u = new UsuarioAutenticado(c.id, c.correo(), c.rol, c.empresa);
        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(contexto);
    }

    private void comoVendedora() {
        como(VENDEDORA);
    }

    private static final Set<ExcepcionAutorizable> DESCUENTO = EnumSet.of(ExcepcionAutorizable.DESCUENTO);

    private Autorizacion autorizar(int administrador, String pin) {
        return servicio.autorizar(new SolicitudAutorizacion(administrador, pin), DESCUENTO);
    }

    // ------------------------------------------------- autorizar: lo que sí vale

    @Test
    void pinCorrectoAutorizaLasExcepcionesPedidas() {
        Set<ExcepcionAutorizable> pedidas = EnumSet.of(ExcepcionAutorizable.DESCUENTO,
                ExcepcionAutorizable.ENCARGO);

        Autorizacion a = servicio.autorizar(new SolicitudAutorizacion(ANA, PIN_ANA), pedidas);

        assertEquals(ANA, a.administradorId());
        assertEquals(pedidas, a.cubre());
        a.exigir(ExcepcionAutorizable.DESCUENTO);
        a.exigir(ExcepcionAutorizable.ENCARGO);
        assertThrows(AutorizacionRequeridaException.class,
                () -> a.exigir(ExcepcionAutorizable.DEVOLUCION));
    }

    @Test
    void sinNecesariasNoSeValidaNiSeCuentaElPin() {
        Autorizacion con = servicio.autorizar(new SolicitudAutorizacion(ANA, ERRADO), Set.of());
        Autorizacion sin = servicio.autorizar(null, Set.of());
        Autorizacion nulas = servicio.autorizar(new SolicitudAutorizacion(AJENO, ERRADO), null);

        for (Autorizacion a : List.of(con, sin, nulas)) {
            assertNull(a.administradorId());
            assertEquals(Set.of(), a.cubre());
            assertThrows(AutorizacionRequeridaException.class,
                    () -> a.exigir(ExcepcionAutorizable.DESCUENTO));
        }
        assertEquals(0, pines.accesos, "no toca la base");
        assertEquals(0, pines.cuenta(ANA).intentos);
        assertEquals(0, pines.cuenta(VENDEDORA).errados);
        assertEquals(List.of(), pines.rechazos);
    }

    @Test
    void elAdministradorNoNecesitaPin() {
        como(BETO);

        Autorizacion sinSolicitud = servicio.autorizar(null, DESCUENTO);
        // Si manda un PIN, se ignora: ni lo valida ni lo cuenta.
        Autorizacion conPinAjeno = servicio.autorizar(new SolicitudAutorizacion(ANA, ERRADO), DESCUENTO);

        for (Autorizacion a : List.of(sinSolicitud, conPinAjeno)) {
            assertEquals(BETO, a.administradorId());
            assertEquals(DESCUENTO, a.cubre());
        }
        assertEquals(0, pines.accesos);
        assertEquals(0, pines.cuenta(ANA).intentos);
        assertEquals(0, pines.cuenta(BETO).errados);
    }

    // ----------------------------------------------------- autorizar: un 403

    @Test
    void sinSolicitudONiAdministradorNiPinEsUn403SinContar() {
        List<SolicitudAutorizacion> incompletas = java.util.Arrays.asList(
                null, new SolicitudAutorizacion(null, PIN_ANA), new SolicitudAutorizacion(ANA, null));

        for (SolicitudAutorizacion s : incompletas) {
            assertThrows(AutorizacionRequeridaException.class, () -> servicio.autorizar(s, DESCUENTO));
        }

        assertEquals(0, pines.accesos);
        assertEquals(0, pines.cuenta(VENDEDORA).errados);
        assertEquals(List.of(), pines.rechazos);
    }

    @Test
    void quienNoEsAdministradorNiVendedorTampocoSeSaltaElPin() {
        agregar(40, EMPRESA, "CLIENTE", "Clara Cliente");
        como(40);

        assertThrows(AutorizacionRequeridaException.class, () -> servicio.autorizar(null, DESCUENTO));
    }

    @Test
    void unPinErradoCuentaContraElAdministradorYQuedaEnLaBitacora() {
        PinInvalidoException e = assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));

        assertEquals("El administrador o el PIN no son correctos.", e.getMessage());
        assertEquals(15, e.getMinutosDeBloqueo());
        assertEquals(1, pines.cuenta(ANA).intentos);
        assertEquals(0, pines.cuenta(VENDEDORA).errados, "no es culpa de quien pide un objetivo válido");
        assertEquals(1, pines.rechazosDe(VENDEDORA, MotivoRechazo.PIN_INCORRECTO));
    }

    @Test
    void elMensajeNoRepiteElPin() {
        PinInvalidoException e = assertThrows(PinInvalidoException.class, () -> autorizar(ANA, "987654"));

        assertFalse(e.getMessage().contains("987654"));
    }

    @Test
    void pinDeUnAdministradorDeOtraEmpresaEs403YCuentaContraElSolicitante() {
        // Aunque acierte el PIN del administrador ajeno.
        PinInvalidoException e = assertThrows(PinInvalidoException.class, () -> autorizar(AJENO, PIN_AJENO));

        assertEquals("El administrador o el PIN no son correctos.", e.getMessage(),
                "el mismo texto que un PIN errado");
        assertEquals(0, pines.cuenta(AJENO).intentos, "el contador del ajeno no cambia");
        assertEquals(1, pines.cuenta(VENDEDORA).errados);
        assertFalse(pines.bloqueadas.contains(AJENO), "la fila del administrador ajeno ni se toca");
        assertEquals(1, pines.rechazosDe(VENDEDORA, MotivoRechazo.PIN_OBJETIVO_INVALIDO));
    }

    @Test
    void pinDeUnUsuarioQueNoEsAdministradorEs403YCuentaContraElSolicitante() {
        assertThrows(PinInvalidoException.class, () -> autorizar(OTRO_VENDEDOR, PIN_ANA));

        assertEquals(1, pines.cuenta(VENDEDORA).errados);
        assertEquals(0, pines.cuenta(OTRO_VENDEDOR).intentos);
        assertFalse(pines.bloqueadas.contains(OTRO_VENDEDOR));
    }

    @ParameterizedTest
    @ValueSource(ints = {SIN_PIN, INACTIVO, 999})
    void unAdministradorSinPinInactivoOInexistenteTampocoVale(int objetivo) {
        PinInvalidoException e = assertThrows(PinInvalidoException.class, () -> autorizar(objetivo, PIN_ANA));

        assertEquals("El administrador o el PIN no son correctos.", e.getMessage());
        assertEquals(1, pines.cuenta(VENDEDORA).errados);
        assertEquals(0, pines.cuenta(SIN_PIN).intentos);
        assertEquals(0, pines.cuenta(INACTIVO).intentos);
    }

    @Test
    void unPinQueNoTieneFormaDePinOEsEnormeEsUnFalloComoCualquierOtro() {
        assertThrows(PinInvalidoException.class, () -> autorizar(ANA, "x".repeat(10_000)));
        assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ""));

        assertEquals(2, pines.cuenta(ANA).intentos);
    }

    // --------------------------------------------------------------- bloqueo

    @Test
    void cincoFallosBloqueanElPinQuinceMinutos() {
        LocalDateTime inicio = reloj.ahoraLocal();
        for (int i = 0; i < 5; i++) {
            assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));
        }
        assertEquals(5, pines.cuenta(ANA).intentos);
        assertEquals(inicio, pines.cuenta(ANA).bloqueadoEn);
        // El bloqueo queda en la bitácora, a nombre de la cuenta bloqueada.
        assertEquals(1, pines.rechazosDe(ANA, MotivoRechazo.PIN_BLOQUEADO));

        // El sexto, aunque el PIN sea el correcto.
        PinBloqueadoException bloqueado = assertThrows(PinBloqueadoException.class,
                () -> autorizar(ANA, PIN_ANA));
        assertEquals(15, bloqueado.getMinutosDeBloqueo());
        assertEquals(5, pines.cuenta(ANA).intentos, "bloqueado no suma ni alarga el plazo");
        assertEquals(inicio, pines.cuenta(ANA).bloqueadoEn);
        assertEquals(1, pines.rechazosDe(VENDEDORA, MotivoRechazo.PIN_BLOQUEADO),
                "el intento contra un PIN bloqueado también queda anotado");

        // Un segundo antes del plazo sigue bloqueado; con 15 min y 1 s acepta.
        reloj.adelantar(15 * 60 - 1);
        assertThrows(PinBloqueadoException.class, () -> autorizar(ANA, PIN_ANA));
        reloj.adelantar(2);
        Autorizacion a = autorizar(ANA, PIN_ANA);

        assertEquals(ANA, a.administradorId());
        assertEquals(0, pines.cuenta(ANA).intentos, "el contador parte de cero");
        assertNull(pines.cuenta(ANA).bloqueadoEn);
    }

    @Test
    void alVencerElBloqueoUnFalloVuelveAContarDesdeUno() {
        for (int i = 0; i < 5; i++) {
            assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));
        }
        reloj.adelantar(15 * 60 + 1);

        assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));

        assertEquals(1, pines.cuenta(ANA).intentos);
        assertNull(pines.cuenta(ANA).bloqueadoEn);
    }

    @Test
    void cincoObjetivosInvalidosBloqueanAlSolicitante() {
        LocalDateTime inicio = reloj.ahoraLocal();
        for (int i = 0; i < 5; i++) {
            assertThrows(PinInvalidoException.class, () -> autorizar(AJENO, PIN_AJENO));
        }
        assertEquals(5, pines.cuenta(VENDEDORA).errados);
        assertEquals(inicio, pines.cuenta(VENDEDORA).emisionBloqueadaEn);
        assertEquals(1, pines.rechazosDe(VENDEDORA, MotivoRechazo.PIN_BLOQUEADO));

        pines.bloqueadas.clear();
        // El sexto intento apunta ahora a un administrador y un PIN válidos.
        assertThrows(PinBloqueadoException.class, () -> autorizar(ANA, PIN_ANA));

        assertEquals(0, pines.cuenta(ANA).intentos);
        assertFalse(pines.bloqueadas.contains(ANA), "ni siquiera mira al administrador");

        reloj.adelantar(15 * 60 + 1);
        assertEquals(ANA, autorizar(ANA, PIN_ANA).administradorId());
        assertEquals(0, pines.cuenta(VENDEDORA).errados);
        assertNull(pines.cuenta(VENDEDORA).emisionBloqueadaEn);
    }

    @Test
    void losIntentosSeCuentanPorAdministrador() {
        for (int i = 0; i < 5; i++) {
            assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));
        }

        // Ana quedó bloqueada; Beto no, ni quien pide.
        assertThrows(PinBloqueadoException.class, () -> autorizar(ANA, PIN_ANA));
        assertEquals(BETO, autorizar(BETO, PIN_BETO).administradorId());
        assertEquals(0, pines.cuenta(BETO).intentos);
        assertEquals(0, pines.cuenta(VENDEDORA).errados);
    }

    @Test
    void unAciertoReiniciaLosIntentos() {
        for (int i = 0; i < 4; i++) {
            assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));
        }
        assertEquals(4, pines.cuenta(ANA).intentos);

        autorizar(ANA, PIN_ANA);
        assertEquals(0, pines.cuenta(ANA).intentos);

        // Cuatro más no bloquean: ya no son cinco seguidos.
        for (int i = 0; i < 4; i++) {
            assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));
        }
        assertNull(pines.cuenta(ANA).bloqueadoEn);
        assertEquals(ANA, autorizar(ANA, PIN_ANA).administradorId());
    }

    @Test
    void unAciertoTambienLimpiaLosErroresDeQuienPide() {
        assertThrows(PinInvalidoException.class, () -> autorizar(AJENO, PIN_AJENO));
        assertEquals(1, pines.cuenta(VENDEDORA).errados);

        autorizar(ANA, PIN_ANA);

        assertEquals(0, pines.cuenta(VENDEDORA).errados);
    }

    @Test
    void laBitacoraLlevaElOrigenDeLaPeticion() {
        var peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr("10.1.2.3");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(peticion));

        assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));

        assertEquals("10.1.2.3", pines.rechazos.get(0).origen());
    }

    @Test
    void sinPeticionHttpLaBitacoraNoTieneOrigen() {
        assertThrows(PinInvalidoException.class, () -> autorizar(ANA, ERRADO));

        assertNull(pines.rechazos.get(0).origen());
    }

    // ------------------------------------------------------------ administradores

    @Test
    void administradoresListaSoloLosQueTienenPinYSonDeLaEmpresa() {
        List<AdministradorParaAutorizar> lista = servicio.administradores();

        assertEquals(List.of(new AdministradorParaAutorizar(ANA, "Ana Admin"),
                new AdministradorParaAutorizar(BETO, "Beto Admin")), lista);
    }

    // --------------------------------------------------------------- fijarPin

    @ParameterizedTest
    @ValueSource(strings = {"123", "1234567", "12a4", "", "   ", "12 34", "١٢٣٤"})
    @NullSource
    void elPinTieneDeCuatroASeisDigitos(String invalido) {
        como(NUEVO);

        assertThrows(PinNuevoInvalidoException.class, () -> servicio.fijarPin(null, invalido));

        assertNull(pines.cuenta(NUEVO).pinHash);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "12345", "123456", "000000"})
    void cuatroASeisDigitosSirven(String valido) {
        como(NUEVO);

        servicio.fijarPin(null, valido);

        assertTrue(Seguridad.verificar(valido, pines.cuenta(NUEVO).pinHash));
    }

    @Test
    void elPinSeGuardaDerivadoYNuncaSeDevuelve() {
        como(NUEVO);
        assertFalse(servicio.estadoDelPin().configurado());

        servicio.fijarPin(null, "483920");

        String guardado = pines.cuenta(NUEVO).pinHash;
        assertTrue(guardado.startsWith("pbkdf2$"), "el mismo derivador que las contraseñas (ADR-0002)");
        assertFalse(guardado.contains("483920"));
        assertTrue(Seguridad.verificar("483920", guardado));
        assertTrue(servicio.estadoDelPin().configurado());
        assertFalse(servicio.estadoDelPin().toString().contains("483920"));
        assertFalse(servicio.estadoDelPin().toString().contains(guardado));
    }

    @Test
    void cadaPinLlevaSuPropiaSal() {
        como(NUEVO);
        servicio.fijarPin(null, "1234");
        String primero = pines.cuenta(NUEVO).pinHash;
        como(SIN_PIN);
        servicio.fijarPin(null, "1234");

        assertFalse(primero.equals(pines.cuenta(SIN_PIN).pinHash));
    }

    @Test
    void elPinNoPuedeSerIgualALaContrasena() {
        // Una contraseña que todavía puede ser de dígitos (de antes del mínimo de ocho).
        pines.cuenta(NUEVO).claveHash = Seguridad.derivar("123456");
        como(NUEVO);

        PinNuevoInvalidoException e = assertThrows(PinNuevoInvalidoException.class,
                () -> servicio.fijarPin(null, "123456"));

        assertFalse(e.getMessage().contains("123456"));
        assertNull(pines.cuenta(NUEVO).pinHash);
    }

    @Test
    void laPrimeraVezSeIgnoraElPinActual() {
        como(NUEVO);

        servicio.fijarPin("9999", "483920");

        assertTrue(Seguridad.verificar("483920", pines.cuenta(NUEVO).pinHash));
        assertEquals(0, pines.cuenta(NUEVO).intentos);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void laPrimeraVezUnPinActualVacioOSoloEspaciosSeTrataComoNulo(String vacio) {
        como(NUEVO);

        servicio.fijarPin(vacio, "483920");

        assertTrue(Seguridad.verificar("483920", pines.cuenta(NUEVO).pinHash));
        assertEquals(0, pines.cuenta(NUEVO).intentos);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void conPinUnActualVacioDaElMismoErrorQueSiFaltara(String vacio) {
        como(ANA);
        String antes = pines.cuenta(ANA).pinHash;
        PinNuevoInvalidoException siFaltara = assertThrows(PinNuevoInvalidoException.class,
                () -> servicio.fijarPin(null, "1111"));

        PinNuevoInvalidoException e = assertThrows(PinNuevoInvalidoException.class,
                () -> servicio.fijarPin(vacio, "1111"));

        assertEquals(siFaltara.getMessage(), e.getMessage());
        assertEquals(antes, pines.cuenta(ANA).pinHash);
        assertEquals(0, pines.cuenta(ANA).intentos, "vacío no es un intento");
    }

    @Test
    void paraCambiarElPinHaceFaltaElActual() {
        como(ANA);
        String antes = pines.cuenta(ANA).pinHash;

        assertThrows(PinNuevoInvalidoException.class, () -> servicio.fijarPin(null, "1111"));
        assertThrows(PinNuevoInvalidoException.class, () -> servicio.fijarPin("abc", "1111"));

        assertEquals(antes, pines.cuenta(ANA).pinHash);
        assertEquals(0, pines.cuenta(ANA).intentos, "faltar o venir mal formado no es un intento");
    }

    @Test
    void conElPinActualCorrectoSeCambiaYSeLimpianLosIntentos() {
        como(ANA);
        assertThrows(PinInvalidoException.class, () -> servicio.fijarPin(ERRADO, "1111"));
        assertEquals(1, pines.cuenta(ANA).intentos);

        servicio.fijarPin(PIN_ANA, "1111");

        assertTrue(Seguridad.verificar("1111", pines.cuenta(ANA).pinHash));
        assertFalse(Seguridad.verificar(PIN_ANA, pines.cuenta(ANA).pinHash));
        assertEquals(0, pines.cuenta(ANA).intentos);
    }

    @Test
    void unPinActualErradoCuentaContraElPropioPinYBloquea() {
        como(ANA);
        String antes = pines.cuenta(ANA).pinHash;
        for (int i = 0; i < 5; i++) {
            assertThrows(PinInvalidoException.class, () -> servicio.fijarPin(ERRADO, "1111"));
        }
        assertEquals(1, pines.rechazosDe(ANA, MotivoRechazo.PIN_BLOQUEADO));

        // Con la sesión robada no se adivina el PIN por esta puerta sin límite.
        assertThrows(PinBloqueadoException.class, () -> servicio.fijarPin(PIN_ANA, "1111"));
        assertEquals(antes, pines.cuenta(ANA).pinHash);
        // Y bloqueado el PIN de Ana, nadie autoriza con él.
        comoVendedora();
        assertThrows(PinBloqueadoException.class, () -> autorizar(ANA, PIN_ANA));

        reloj.adelantar(15 * 60 + 1);
        como(ANA);
        servicio.fijarPin(PIN_ANA, "1111");
        assertTrue(Seguridad.verificar("1111", pines.cuenta(ANA).pinHash));
        assertNull(pines.cuenta(ANA).bloqueadoEn);
    }

    @Test
    void soloElAdministradorFijaOConsultaSuPin() {
        comoVendedora();

        assertThrows(AutorizacionRequeridaException.class, () -> servicio.fijarPin(null, "1234"));
        assertThrows(AutorizacionRequeridaException.class, () -> servicio.estadoDelPin());

        assertEquals(0, pines.accesos);
    }

    @Test
    void elPinSeFijaSoloEnLaCuentaDeQuienPide() {
        como(NUEVO);

        servicio.fijarPin(null, "483920");

        assertNull(pines.cuenta(SIN_PIN).pinHash);
        assertEquals(hashAna, pines.cuenta(ANA).pinHash);
    }

    // ----------------------------------------------------------- Autorizacion

    @Test
    void laAutorizacionNoSePuedeAmpliarDespuesDeCreada() {
        Set<ExcepcionAutorizable> original = EnumSet.of(ExcepcionAutorizable.DESCUENTO);
        Autorizacion a = new Autorizacion(ANA, original);

        original.add(ExcepcionAutorizable.DEVOLUCION);

        assertEquals(Set.of(ExcepcionAutorizable.DESCUENTO), a.cubre());
        assertThrows(UnsupportedOperationException.class,
                () -> a.cubre().add(ExcepcionAutorizable.DEVOLUCION));
        assertNotNull(new Autorizacion(ANA, null).cubre());
    }
}
