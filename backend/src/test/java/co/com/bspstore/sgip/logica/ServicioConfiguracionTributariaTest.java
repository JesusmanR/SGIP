package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ConfiguracionTributaria;
import co.com.bspstore.sgip.api.dto.ReglaDeIva;
import co.com.bspstore.sgip.api.dto.SolicitudConfiguracionTributaria;
import co.com.bspstore.sgip.api.dto.SolicitudReglaIva;
import co.com.bspstore.sgip.api.dto.TipoImpuestoNacional;
import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.datos.RepositorioConfiguracionTributaria;
import co.com.bspstore.sgip.modelo.Categoria;
import co.com.bspstore.sgip.modelo.Producto;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioConfiguracionTributariaTest {

    private static final int EMPRESA = 3;
    private static final int TIPO_IVA_19 = 11;
    private static final LocalDate HOY = LocalDate.of(2026, 11, 3);
    private static final LocalDate AYER = HOY.minusDays(1);
    private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);

    private static final Validator VALIDADOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Mock RepositorioConfiguracionTributaria repositorio;
    @Mock CategoriaRepositorio categorias;
    @Mock ProductoRepositorio productos;

    private ServicioConfiguracionTributaria servicio;

    @BeforeEach
    void preparar() {
        Clock reloj = Clock.fixed(
                ZonedDateTime.of(HOY.atTime(9, 0), ZoneId.of("America/Bogota")).toInstant(),
                ZoneId.of("America/Bogota"));
        servicio = new ServicioConfiguracionTributaria(repositorio, categorias, productos, reloj);
        comoAdministradorDe(EMPRESA);

        when(categorias.findByEmpresaIdAndId(eq(EMPRESA), anyInt()))
                .thenReturn(Optional.of(mock(Categoria.class)));
        when(productos.findByEmpresaIdAndId(eq(EMPRESA), anyInt()))
                .thenReturn(Optional.of(mock(Producto.class)));
        when(repositorio.buscarImpuestoActivo("IVA_19")).thenReturn(Optional.of(impuesto()));
        when(repositorio.haySolape(anyInt(), any(), any(), any(), any())).thenReturn(false);
        when(repositorio.insertarRegla(anyInt(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(50);
        when(repositorio.buscarRegla(EMPRESA, 50))
                .thenReturn(Optional.of(regla(50, null, null, ENERO, null)));
        when(repositorio.buscarConfiguracion(EMPRESA))
                .thenReturn(Optional.of(new ConfiguracionTributaria(
                        true, false, false, null, false, 5, true)));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void comoAdministradorDe(Integer empresaId) {
        comoUsuario(new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", empresaId));
    }

    private void comoUsuario(UsuarioAutenticado u) {
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private static TipoImpuestoNacional impuesto() {
        return new TipoImpuestoNacional(TIPO_IVA_19, "IVA_19", "IVA tarifa general (19 %)",
                "GRAVADO", new BigDecimal("0.1900"), null, "Estatuto Tributario, art. 468",
                ENERO, null, true);
    }

    private static ReglaDeIva regla(int id, Integer categoria, Integer producto,
                                    LocalDate desde, LocalDate hasta) {
        int especificidad = producto != null ? 2 : categoria != null ? 1 : 0;
        return new ReglaDeIva(id, categoria, null, producto, null, "IVA_19",
                "IVA tarifa general (19 %)", "GRAVADO", new BigDecimal("0.1900"),
                desde, hasta, null, especificidad, hasta == null);
    }

    private static SolicitudConfiguracionTributaria configuracion(
            boolean retenciones, BigDecimal ica, int dias) {
        return new SolicitudConfiguracionTributaria(true, false, retenciones, ica, true, dias);
    }

    private static SolicitudReglaIva reglaNueva(Integer categoria, Integer producto,
                                                LocalDate desde, LocalDate hasta) {
        return new SolicitudReglaIva(categoria, producto, "IVA_19", desde, hasta, null);
    }

    private void nadaSeInserto() {
        verify(repositorio, never()).insertarRegla(anyInt(), any(), any(), anyInt(), any(),
                any(), any());
    }

    // ------------------------------------------------- la configuración

    @Test
    void guardarLaConfiguracionLaMarcaCompleta() {
        var solicitud = new SolicitudConfiguracionTributaria(
                true, true, true, new BigDecimal("0.009660"), true, 10);

        var guardada = servicio.guardar(solicitud);

        verify(repositorio).guardarConfiguracion(EMPRESA, true, true, true,
                new BigDecimal("0.009660"), true, 10);
        // La marca de «completa» la pone el repositorio en la misma
        // sentencia; el servicio devuelve lo que quedó en la base.
        assertTrue(guardada.configuracionCompleta());
    }

    @Test
    void sinFilaDeConfiguracionSeDevuelveLaDeUnaEmpresaQueNadieHaElegido() {
        when(repositorio.buscarConfiguracion(EMPRESA)).thenReturn(Optional.empty());

        var c = servicio.configuracion();

        assertFalse(c.responsableIva());
        assertFalse(c.configuracionCompleta());
        assertEquals(5, c.cotizacionVigenciaDias());
    }

    @Test
    void elIcaExigePracticarRetenciones() {
        var solicitud = configuracion(false, new BigDecimal("0.009660"), 5);

        var e = assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.guardar(solicitud));

        assertTrue(e.getMessage().toLowerCase().contains("retenciones"));
        verify(repositorio, never()).guardarConfiguracion(anyInt(), anyBoolean(), anyBoolean(),
                anyBoolean(), any(), anyBoolean(), anyInt());
    }

    @Test
    void elIcaConRetencionesSeGuarda() {
        servicio.guardar(configuracion(true, new BigDecimal("0.009660"), 5));

        verify(repositorio).guardarConfiguracion(EMPRESA, true, false, true,
                new BigDecimal("0.009660"), true, 5);
    }

    @Test
    void laVigenciaDeCotizacionVaDeUnoA365() {
        for (int dias : new int[] {0, 366, -1}) {
            assertFalse(VALIDADOR.validate(configuracion(false, null, dias)).isEmpty(),
                    dias + " días no deberían pasar la validación");
        }
        for (int dias : new int[] {1, 5, 365}) {
            assertTrue(VALIDADOR.validate(configuracion(false, null, dias)).isEmpty(),
                    dias + " días deberían pasar la validación");
        }
    }

    @Test
    void ningunaOpcionPuedeFaltarNiElIcaSalirseDeCeroAUno() {
        // Un campo ausente no puede convertirse en «falso» sin que nadie lo note.
        var sinInterruptor = new SolicitudConfiguracionTributaria(
                null, false, false, null, false, 5);
        assertFalse(VALIDADOR.validate(sinInterruptor).isEmpty());

        assertFalse(VALIDADOR.validate(
                configuracion(true, new BigDecimal("1.5"), 5)).isEmpty());
        assertFalse(VALIDADOR.validate(
                configuracion(true, new BigDecimal("-0.01"), 5)).isEmpty());
        // El esquema guarda seis decimales: uno más se perdería al redondear.
        assertFalse(VALIDADOR.validate(
                configuracion(true, new BigDecimal("0.0096601"), 5)).isEmpty());
    }

    // ------------------------------------------------------- las reglas

    @Test
    void laReglaSeCreaEnLaEmpresaDeLaSesionYConElTipoDelCatalogo() {
        var s = new SolicitudReglaIva(4, null, "IVA_19", ENERO, null, "  Art. 468  ");

        var creada = servicio.crearRegla(s);

        verify(repositorio).insertarRegla(EMPRESA, 4, null, TIPO_IVA_19, ENERO, null,
                "Art. 468");
        assertEquals(50, creada.reglaId());
    }

    @Test
    void unaReglaSinCategoriaNiProductoEsLaGeneral() {
        servicio.crearRegla(reglaNueva(null, null, ENERO, null));

        verify(repositorio).insertarRegla(EMPRESA, null, null, TIPO_IVA_19, ENERO, null, null);
        verify(categorias, never()).findByEmpresaIdAndId(anyInt(), anyInt());
        verify(productos, never()).findByEmpresaIdAndId(anyInt(), anyInt());
    }

    @Test
    void unaReglaNoPuedeSerDeCategoriaYDeProductoALaVez() {
        var e = assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.crearRegla(reglaNueva(4, 9, ENERO, null)));

        assertTrue(e.getMessage().contains("categoría") && e.getMessage().contains("producto"));
        nadaSeInserto();
    }

    @Test
    void unaVigenciaQueTerminaAntesDeEmpezarSeRechaza() {
        assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.crearRegla(reglaNueva(null, null, HOY, AYER)));

        nadaSeInserto();
    }

    @Test
    void unaCategoriaOProductoAjenosSonReferenciaAjena() {
        when(categorias.findByEmpresaIdAndId(EMPRESA, 99)).thenReturn(Optional.empty());
        when(productos.findByEmpresaIdAndId(EMPRESA, 98)).thenReturn(Optional.empty());

        var categoria = assertThrows(ReferenciaAjenaException.class,
                () -> servicio.crearRegla(reglaNueva(99, null, ENERO, null)));
        var producto = assertThrows(ReferenciaAjenaException.class,
                () -> servicio.crearRegla(reglaNueva(null, 98, ENERO, null)));

        assertTrue(categoria.getMessage().contains("99"));
        assertTrue(producto.getMessage().contains("98"));
        nadaSeInserto();
    }

    @Test
    void unImpuestoInexistenteOInactivoSeRechaza() {
        // `buscarImpuestoActivo` no devuelve los inactivos: para el
        // servicio, «inactivo» y «no existe» son la misma respuesta.
        when(repositorio.buscarImpuestoActivo("INVENTADO")).thenReturn(Optional.empty());
        var s = new SolicitudReglaIva(null, null, "INVENTADO", ENERO, null, null);

        var e = assertThrows(ImpuestoNoDisponibleException.class, () -> servicio.crearRegla(s));

        assertTrue(e.getMessage().contains("INVENTADO"));
        nadaSeInserto();
    }

    @Test
    void dosReglasDelMismoAlcanceConVigenciaSolapadaChocan() {
        when(repositorio.haySolape(EMPRESA, 4, null, ENERO, null)).thenReturn(true);

        var e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.crearRegla(reglaNueva(4, null, ENERO, null)));

        assertTrue(e.getMessage().contains("categoría 4"));
        nadaSeInserto();
    }

    @Test
    void elSolapeSeConsultaConLaVigenciaAbiertaYEnLaEmpresaDeLaSesion() {
        servicio.crearRegla(reglaNueva(null, 9, ENERO, null));

        // La vigencia abierta viaja como nula: es el repositorio quien la
        // trata como un final lejanísimo.
        verify(repositorio).haySolape(EMPRESA, null, 9, ENERO, null);
    }

    @Test
    void sinEmpresaEnLaSesionNoSeHaceNada() {
        comoUsuario(new UsuarioAutenticado(1, "root@plataforma.co", "SUPER_ADMIN", null));

        assertThrows(SinEmpresaException.class, () -> servicio.reglas());
        assertThrows(SinEmpresaException.class,
                () -> servicio.crearRegla(reglaNueva(null, null, ENERO, null)));
    }

    @Test
    void lasValidacionesDeLaReglaExigenCodigoYFechaYAcotanElFundamento() {
        assertFalse(VALIDADOR.validate(
                new SolicitudReglaIva(null, null, " ", ENERO, null, null)).isEmpty());
        assertFalse(VALIDADOR.validate(
                new SolicitudReglaIva(null, null, "IVA_19", null, null, null)).isEmpty());
        assertFalse(VALIDADOR.validate(
                new SolicitudReglaIva(null, null, "IVA_19", ENERO, null, "x".repeat(161)))
                .isEmpty());
        assertTrue(VALIDADOR.validate(
                new SolicitudReglaIva(null, null, "IVA_19", ENERO, null, "x".repeat(160)))
                .isEmpty());
    }

    // ------------------------------------------------------ la carrera

    @Test
    void creaLaReglaConLaEmpresaBloqueadaAntesDeMirarNada() {
        InOrder orden = inOrder(repositorio, categorias);

        servicio.crearRegla(reglaNueva(4, null, ENERO, null));

        // Sin el bloqueo, dos peticiones a la vez leerían «no hay solape» las
        // dos y crearían dos reglas que se pisan. El bloqueo va antes de la
        // primera lectura, y la comprobación de solape después de él.
        orden.verify(repositorio).bloquearEmpresa(EMPRESA);
        orden.verify(categorias).findByEmpresaIdAndId(EMPRESA, 4);
        orden.verify(repositorio).haySolape(EMPRESA, 4, null, ENERO, null);
        orden.verify(repositorio).insertarRegla(anyInt(), any(), any(), anyInt(), any(),
                any(), any());
    }

    @Test
    void elBloqueoSoloSirveDentroDeUnaTransaccion() throws Exception {
        var metodo = ServicioConfiguracionTributaria.class
                .getMethod("crearRegla", SolicitudReglaIva.class);

        assertNotNull(metodo.getAnnotation(Transactional.class));
    }

    // ------------------------------------------------- cerrar una regla

    private static final LocalDate EN_CINCO_DIAS = HOY.plusDays(5);

    @Test
    void cerrarUnaReglaAjenaDevuelveVacio() {
        when(repositorio.buscarRegla(EMPRESA, 77)).thenReturn(Optional.empty());

        assertTrue(servicio.cerrarRegla(77, AYER).isEmpty());

        verify(repositorio, never()).cerrarRegla(anyInt(), anyInt(), any());
        verify(repositorio, never()).borrarRegla(anyInt(), anyInt());
    }

    @Test
    void cerrarSinFechaCierraAyer() {
        var resultado = servicio.cerrarRegla(50, null).orElseThrow();

        verify(repositorio).cerrarRegla(EMPRESA, 50, AYER);
        verify(repositorio, never()).borrarRegla(anyInt(), anyInt());
        assertFalse(resultado.retirada());
        assertNotNull(resultado.regla());
    }

    /**
     * El servicio decide con su reloj que la regla «aún no empieza»; si el
     * DELETE no borra nada porque la base ya la tiene vigente, no puede
     * responder que la retiró.
     */
    @Test
    void siLaBaseYaTieneVigenteLaReglaNoSeDaPorRetirada() {
        when(repositorio.buscarRegla(EMPRESA, 50))
                .thenReturn(Optional.of(regla(50, null, null, EN_CINCO_DIAS, null)));
        when(repositorio.borrarRegla(EMPRESA, 50)).thenReturn(0);

        assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.cerrarRegla(50, null));
    }

    @Test
    void cerrarConFechaCierraEsaFecha() {
        servicio.cerrarRegla(50, LocalDate.of(2026, 12, 31));

        verify(repositorio).cerrarRegla(EMPRESA, 50, LocalDate.of(2026, 12, 31));
    }

    @Test
    void unaReglaQueTodaviaNoEmpezoYSeCierraSinFechaSeRetira() {
        // Una regla por estrenar nunca explicó una venta: retirarla no borra
        // historia, corrige una equivocación.
        when(repositorio.buscarRegla(EMPRESA, 50))
                .thenReturn(Optional.of(regla(50, null, null, EN_CINCO_DIAS, null)));

        when(repositorio.borrarRegla(EMPRESA, 50)).thenReturn(1);

        var resultado = servicio.cerrarRegla(50, null).orElseThrow();

        verify(repositorio).borrarRegla(EMPRESA, 50);
        verify(repositorio, never()).cerrarRegla(anyInt(), anyInt(), any());
        assertTrue(resultado.retirada());
    }

    @Test
    void unaReglaFuturaConFechaSeCierraNormalmenteSinBorrarse() {
        when(repositorio.buscarRegla(EMPRESA, 50))
                .thenReturn(Optional.of(regla(50, null, null, EN_CINCO_DIAS, null)));

        // El mismo día de inicio vale: la regla rige un día.
        servicio.cerrarRegla(50, EN_CINCO_DIAS);
        servicio.cerrarRegla(50, EN_CINCO_DIAS.plusDays(10));

        verify(repositorio).cerrarRegla(EMPRESA, 50, EN_CINCO_DIAS);
        verify(repositorio).cerrarRegla(EMPRESA, 50, EN_CINCO_DIAS.plusDays(10));
        verify(repositorio, never()).borrarRegla(anyInt(), anyInt());
    }

    @Test
    void unaReglaQueEmpiezaHoySinFechaSeRechazaDiciendoComoCerrarla() {
        when(repositorio.buscarRegla(EMPRESA, 50))
                .thenReturn(Optional.of(regla(50, null, null, HOY, null)));

        var e = assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.cerrarRegla(50, null));

        // Rige hoy: cerrarla ayer sería cerrarla antes de empezar, y borrarla
        // sería borrar lo que ya está explicando precios.
        assertTrue(e.getMessage().contains("hoy"), e.getMessage());
        assertTrue(e.getMessage().contains(HOY.toString()), e.getMessage());
        assertTrue(e.getMessage().contains("hasta"), e.getMessage());
        verify(repositorio, never()).cerrarRegla(anyInt(), anyInt(), any());
        verify(repositorio, never()).borrarRegla(anyInt(), anyInt());
    }

    @Test
    void noSePuedeCerrarUnaReglaAntesDeQueEmpiece() {
        when(repositorio.buscarRegla(EMPRESA, 50))
                .thenReturn(Optional.of(regla(50, null, null, EN_CINCO_DIAS, null)));

        var e = assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.cerrarRegla(50, HOY));

        assertTrue(e.getMessage().contains(EN_CINCO_DIAS.toString()), e.getMessage());
        verify(repositorio, never()).cerrarRegla(anyInt(), anyInt(), any());
        verify(repositorio, never()).borrarRegla(anyInt(), anyInt());
    }

    @Test
    void cerrarNoAmpliaUnaVigenciaQueYaTerminaba() {
        // Alargar el cierre de una regla vencida podría solaparla con la
        // que la sustituyó: cerrar solo acorta.
        when(repositorio.buscarRegla(EMPRESA, 50)).thenReturn(Optional.of(
                regla(50, null, null, ENERO, LocalDate.of(2026, 6, 30))));

        assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.cerrarRegla(50, LocalDate.of(2026, 12, 31)));
        verify(repositorio, never()).cerrarRegla(anyInt(), anyInt(), any());

        servicio.cerrarRegla(50, LocalDate.of(2026, 3, 31));
        verify(repositorio).cerrarRegla(EMPRESA, 50, LocalDate.of(2026, 3, 31));
    }
}
