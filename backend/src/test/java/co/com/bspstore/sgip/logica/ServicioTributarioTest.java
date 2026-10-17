package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudTipoImpuesto;
import co.com.bspstore.sgip.api.dto.SolicitudValorUvt;
import co.com.bspstore.sgip.api.dto.TipoImpuestoNacional;
import co.com.bspstore.sgip.api.dto.ValorUvt;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.datos.RepositorioTributario;
import co.com.bspstore.sgip.modelo.AccionPlataforma;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioTributarioTest {

    private static final int SUPER_ADMIN = 9;

    @Mock RepositorioTributario tributario;
    @Mock RepositorioPlataforma plataforma;

    private ServicioTributario servicio;

    @BeforeEach
    void preparar() {
        servicio = new ServicioTributario(tributario, plataforma);
        var u = new UsuarioAutenticado(SUPER_ADMIN, "plataforma@bsp.co", "SUPER_ADMIN", null);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private SolicitudValorUvt uvt(String valor) {
        return new SolicitudValorUvt(new BigDecimal(valor), "Resolución 000123", "UVT oficial");
    }

    private TipoImpuestoNacional tipo(String tratamiento, String tarifa, String umbral) {
        return new TipoImpuestoNacional(1, "EXCL_UMBRAL_PC", "Computadores", tratamiento,
                new BigDecimal(tarifa), umbral == null ? null : new BigDecimal(umbral),
                "Art. 424 E.T.", LocalDate.of(2026, 1, 1), null, true);
    }

    // -------------------------------------------------------------- UVT

    @Test
    void unAnioNuevoSeDaDeAltaYSeAnotaSinValorAnterior() {
        when(tributario.buscarUvt(2027)).thenReturn(Optional.empty(),
                Optional.of(new ValorUvt(2027, new BigDecimal("55000.00"), "Resolución 000123")));

        ValorUvt resultado = servicio.fijarUvt(2027, uvt("55000.00"));

        assertEquals(2027, resultado.anio());
        verify(tributario).insertarUvt(2027, new BigDecimal("55000.00"), "Resolución 000123");
        verify(tributario, never()).actualizarUvt(any(), any(), any());
        // Alcance nacional: la bitácora exige empresa nula.
        verify(plataforma).registrar(eq(SUPER_ADMIN), isNull(), eq(AccionPlataforma.UVT_ALTA),
                eq("uvt_anual"), eq("2027"), isNull(), eq("55000.00"), eq("UVT oficial"));
    }

    @Test
    void unAnioQueYaExisteSeActualizaYLaBitacoraGuardaElValorAnterior() {
        when(tributario.buscarUvt(2026)).thenReturn(
                Optional.of(new ValorUvt(2026, new BigDecimal("52374.00"), null)),
                Optional.of(new ValorUvt(2026, new BigDecimal("53000.00"), "Resolución 000123")));

        servicio.fijarUvt(2026, uvt("53000.00"));

        verify(tributario).actualizarUvt(2026, new BigDecimal("53000.00"), "Resolución 000123");
        verify(tributario, never()).insertarUvt(any(), any(), any());
        verify(plataforma).registrar(eq(SUPER_ADMIN), isNull(), eq(AccionPlataforma.UVT_CAMBIO),
                eq("uvt_anual"), eq("2026"), eq("52374.00"), eq("53000.00"), any());
    }

    // --------------------------------------------------------- impuestos

    @Test
    void cambiarLaTarifaDejaEnLaBitacoraLoQueRegiaYLoQueRige() {
        when(tributario.buscarImpuesto("EXCL_UMBRAL_PC")).thenReturn(
                Optional.of(tipo("EXCLUIDO_HASTA_UMBRAL", "0.1900", "50.00")),
                Optional.of(tipo("EXCLUIDO_HASTA_UMBRAL", "0.1600", "60.00")));

        var solicitud = new SolicitudTipoImpuesto(
                new BigDecimal("0.1600"), new BigDecimal("60.00"), "Art. 424 E.T.", "reforma");
        assertTrue(servicio.cambiarImpuesto("EXCL_UMBRAL_PC", solicitud).isPresent());

        verify(tributario).actualizarImpuesto("EXCL_UMBRAL_PC",
                new BigDecimal("0.1600"), new BigDecimal("60.00"), "Art. 424 E.T.");
        // Las dos cifras van juntas porque cambian a la vez y se leen juntas.
        verify(plataforma).registrar(eq(SUPER_ADMIN), isNull(),
                eq(AccionPlataforma.IMPUESTO_CAMBIO), eq("tipos_impuesto"), eq("EXCL_UMBRAL_PC"),
                eq("tarifa 0.1900 hasta 50.00 UVT"), eq("tarifa 0.1600 hasta 60.00 UVT"),
                eq("reforma"));
    }

    @Test
    void unTipoDeUmbralSinUmbralSeRechazaConSuMotivo() {
        when(tributario.buscarImpuesto("EXCL_UMBRAL_PC"))
                .thenReturn(Optional.of(tipo("EXCLUIDO_HASTA_UMBRAL", "0.1900", "50.00")));

        var solicitud = new SolicitudTipoImpuesto(new BigDecimal("0.1900"), null, null, null);
        var e = assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.cambiarImpuesto("EXCL_UMBRAL_PC", solicitud));

        assertTrue(e.getMessage().contains("obligatorio"));
        verify(tributario, never()).actualizarImpuesto(any(), any(), any(), any());
        verify(plataforma, never()).registrar(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Un umbral en un tipo que no lo usa hace creer que hay un tope donde
     * no lo hay, y la base lo aceptaría sin protestar: la restricción
     * {@code ck_tipos_impuesto_umbral} solo exige el umbral en los tipos
     * de umbral, no prohíbe el de más.
     */
    @Test
    void unUmbralEnUnTipoQueNoLoUsaSeRechaza() {
        when(tributario.buscarImpuesto("GRAVADO_GRAL"))
                .thenReturn(Optional.of(tipo("GRAVADO", "0.1900", null)));

        var solicitud = new SolicitudTipoImpuesto(
                new BigDecimal("0.1900"), new BigDecimal("50.00"), null, null);
        var e = assertThrows(DatoTributarioInvalidoException.class,
                () -> servicio.cambiarImpuesto("GRAVADO_GRAL", solicitud));

        assertTrue(e.getMessage().contains("no usa umbral"));
        verify(tributario, never()).actualizarImpuesto(any(), any(), any(), any());
    }

    @Test
    void unCodigoQueNoExisteDevuelveVacioYNoEscribeNada() {
        when(tributario.buscarImpuesto("NO_EXISTE")).thenReturn(Optional.empty());

        var solicitud = new SolicitudTipoImpuesto(new BigDecimal("0.1900"), null, null, null);
        assertTrue(servicio.cambiarImpuesto("NO_EXISTE", solicitud).isEmpty());

        verify(tributario, never()).actualizarImpuesto(any(), any(), any(), any());
        verify(plataforma, never()).registrar(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void elTipoSinUmbralSeAnotaSinMencionarUnTopeQueNoTiene() {
        when(tributario.buscarImpuesto("GRAVADO_GRAL")).thenReturn(
                Optional.of(tipo("GRAVADO", "0.1900", null)),
                Optional.of(tipo("GRAVADO", "0.0500", null)));

        var solicitud = new SolicitudTipoImpuesto(new BigDecimal("0.0500"), null, null, null);
        servicio.cambiarImpuesto("GRAVADO_GRAL", solicitud);

        verify(plataforma).registrar(eq(SUPER_ADMIN), isNull(),
                eq(AccionPlataforma.IMPUESTO_CAMBIO), eq("tipos_impuesto"), eq("GRAVADO_GRAL"),
                eq("tarifa 0.1900"), eq("tarifa 0.0500"), isNull());
    }
}
