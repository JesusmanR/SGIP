package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.ReglaDeMargen;
import co.com.bspstore.sgip.api.dto.SolicitudReglaMargen;
import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPrecios;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioPreciosTest {

    private static final int EMPRESA = 3;
    private static final LocalDate HOY = LocalDate.of(2026, 10, 19);

    @Mock RepositorioPrecios precios;

    private ServicioPrecios servicio;

    @BeforeEach
    void preparar() {
        Clock reloj = Clock.fixed(
                ZonedDateTime.of(HOY.atTime(9, 0), ZoneId.of("America/Bogota")).toInstant(),
                ZoneId.of("America/Bogota"));
        servicio = new ServicioPrecios(precios, reloj);
        comoAdministradorDe(EMPRESA);

        when(precios.categoriaPropia(anyInt(), any())).thenReturn(true);
        when(precios.proveedorPropio(anyInt(), any())).thenReturn(true);
        when(precios.haySolape(anyInt(), any(), any(), anyString(), any(), any(), any()))
                .thenReturn(false);
        when(precios.insertar(anyInt(), any(), any(), anyString(), anyString(), any(),
                any(), any())).thenReturn(50);
        when(precios.buscar(EMPRESA, 50)).thenReturn(Optional.of(regla(50, null, null)));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void comoAdministradorDe(Integer empresaId) {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private ReglaDeMargen regla(int id, Integer categoria, Integer proveedor) {
        int esp = (categoria != null ? 1 : 0) + (proveedor != null ? 1 : 0);
        return new ReglaDeMargen(id, categoria, "Computadores", proveedor, "Mayorista",
                "CONTADO", "SOBRE_COSTO", new BigDecimal("1.2000"),
                LocalDate.of(2026, 1, 1), null, esp, true);
    }

    private SolicitudReglaMargen solicitud(String base, String factor) {
        return new SolicitudReglaMargen(null, null, "CONTADO", base,
                new BigDecimal(factor), LocalDate.of(2026, 1, 1), null);
    }

    // ----------------------------------------------------------- RF-30

    @Test
    void laReglaSeCreaConElAmbitoQueLeDanYEnLaEmpresaDelContexto() {
        var s = new SolicitudReglaMargen(4, 9, "CREDITO", "SOBRE_COSTO",
                new BigDecimal("1.3500"), LocalDate.of(2026, 1, 1), null);

        servicio.crear(s);

        verify(precios).insertar(EMPRESA, 4, 9, "CREDITO", "SOBRE_COSTO",
                new BigDecimal("1.3500"), LocalDate.of(2026, 1, 1), null);
    }

    @Test
    void unaCategoriaDeOtraEmpresaNoSirveDeAmbito() {
        when(precios.categoriaPropia(EMPRESA, 99)).thenReturn(false);
        var s = new SolicitudReglaMargen(99, null, "CONTADO", "SOBRE_COSTO",
                new BigDecimal("1.2000"), LocalDate.of(2026, 1, 1), null);

        var e = assertThrows(ReferenciaAjenaException.class, () -> servicio.crear(s));

        assertTrue(e.getMessage().contains("99"));
        verify(precios, never()).insertar(anyInt(), any(), any(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void unProveedorDeOtraEmpresaTampoco() {
        when(precios.proveedorPropio(EMPRESA, 77)).thenReturn(false);
        var s = new SolicitudReglaMargen(null, 77, "CONTADO", "SOBRE_COSTO",
                new BigDecimal("1.2000"), LocalDate.of(2026, 1, 1), null);

        assertThrows(ReferenciaAjenaException.class, () -> servicio.crear(s));
        verify(precios, never()).insertar(anyInt(), any(), any(), anyString(), anyString(),
                any(), any(), any());
    }

    /**
     * Dos reglas del mismo alcance a la vez no rompen nada —la vista
     * desempata por identificador— y por eso son peligrosas: quien
     * corrige la que no manda no ve cambiar ningún precio y no entiende
     * por qué.
     */
    @Test
    void dosReglasDelMismoAlcanceYVigenciaSolapadaSeRechazan() {
        when(precios.haySolape(eq(EMPRESA), isNull(), isNull(), eq("CONTADO"),
                any(), any(), isNull())).thenReturn(true);

        var e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.crear(solicitud("SOBRE_COSTO", "1.2000")));

        assertTrue(e.getMessage().contains("solo manda una"),
                "el mensaje tiene que explicar por qué importa, no solo que no se puede");
        verify(precios, never()).insertar(anyInt(), any(), any(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void unUsuarioDePlataformaNoConfiguraReglasDeNadie() {
        comoAdministradorDe(null);

        assertThrows(SinEmpresaException.class, () -> servicio.listar());
        assertThrows(SinEmpresaException.class,
                () -> servicio.crear(solicitud("SOBRE_COSTO", "1.2000")));
    }

    // ----------------------------------------------------------- RF-32

    @Test
    void elMargenSobreElPrecioNoLlegaAlCienPorCiento() {
        var e = assertThrows(ReglaInvalidaException.class,
                () -> servicio.crear(solicitud("SOBRE_PRECIO", "2.0000")));

        assertTrue(e.getMessage().contains("100 %"));
        assertTrue(e.getMessage().contains("costo tendría que ser cero"),
                "el mensaje explica por qué, que es lo que evita que alguien insista");
        verify(precios, never()).insertar(anyInt(), any(), any(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void elMargenSobreElPrecioJustoPorDebajoDelTopeSiEntra() {
        servicio.crear(solicitud("SOBRE_PRECIO", "1.9999"));

        verify(precios).insertar(eq(EMPRESA), isNull(), isNull(), eq("CONTADO"),
                eq("SOBRE_PRECIO"), eq(new BigDecimal("1.9999")), any(), isNull());
    }

    /** El recargo sobre el costo no tiene tope: un 200 % es caro, no imposible. */
    @Test
    void elRecargoSobreElCostoNoTieneTope() {
        servicio.crear(solicitud("SOBRE_COSTO", "3.0000"));

        verify(precios).insertar(eq(EMPRESA), isNull(), isNull(), eq("CONTADO"),
                eq("SOBRE_COSTO"), eq(new BigDecimal("3.0000")), any(), isNull());
    }

    // ------------------------------------------------- vigencia y cierre

    @Test
    void unaVigenciaQueTerminaAntesDeEmpezarSeRechaza() {
        var s = new SolicitudReglaMargen(null, null, "CONTADO", "SOBRE_COSTO",
                new BigDecimal("1.2000"), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1));

        var e = assertThrows(ReglaInvalidaException.class, () -> servicio.crear(s));

        assertTrue(e.getMessage().contains("antes de empezar"));
    }

    /**
     * Sin fecha, cierra ayer: así la regla deja de aplicar hoy. Con fecha
     * de hoy seguiría rigiendo la jornada entera, que casi nunca es lo
     * que quiere quien la está retirando.
     */
    @Test
    void cerrarSinFechaDejaLaReglaFueraDesdeHoy() {
        when(precios.buscar(EMPRESA, 50)).thenReturn(Optional.of(regla(50, null, null)));

        servicio.cerrar(50, null);

        verify(precios).cerrar(EMPRESA, 50, HOY.minusDays(1));
    }

    @Test
    void noSeCierraUnaReglaAntesDeQueEmpezaraARegir() {
        when(precios.buscar(EMPRESA, 50)).thenReturn(Optional.of(regla(50, null, null)));

        var e = assertThrows(ReglaInvalidaException.class,
                () -> servicio.cerrar(50, LocalDate.of(2025, 1, 1)));

        assertTrue(e.getMessage().contains("2026-01-01"));
        verify(precios, never()).cerrar(anyInt(), anyInt(), any());
    }

    @Test
    void loDeOtraEmpresaSeResponderaComoInexistente() {
        when(precios.buscar(EMPRESA, 404)).thenReturn(Optional.empty());

        assertTrue(servicio.buscar(404).isEmpty());
        assertTrue(servicio.cerrar(404, null).isEmpty());
        assertTrue(servicio.actualizar(404, solicitud("SOBRE_COSTO", "1.2000")).isEmpty());

        verify(precios, never()).cerrar(anyInt(), anyInt(), any());
        verify(precios, never()).actualizar(anyInt(), anyInt(), anyString(), any(), any(), any());
    }

    /**
     * El alcance se consulta antes de guardar para poder decir cuántas
     * referencias cambiarían de precio. Un cambio de margen se hace en un
     * segundo y se nota en la factura de cada cliente.
     */
    @Test
    void elAlcanceCuentaLasReferenciasQueCambiarianDePrecio() {
        when(precios.productosAlcanzados(EMPRESA, 4, null)).thenReturn(34);

        assertEquals(34, servicio.alcance(4, null));
    }

    // ----------------------------------------------------------- RF-35

    @Test
    void laSimulacionDevuelveLasReferenciasQueCruzarianElUmbral() {
        var cruce = new CruceDeUmbral(1, "PC-001", "Portátil", new BigDecimal("2100000.00"),
                2520000, 2730000, new BigDecimal("2618700.00"), "EXCLUIDO", "GRAVADO");
        when(precios.simularCruces(EMPRESA, null, null, "CONTADO", "SOBRE_COSTO",
                new BigDecimal("1.3000"))).thenReturn(List.of(cruce));

        var resultado = servicio.simular(solicitud("SOBRE_COSTO", "1.3000"));

        assertEquals(1, resultado.size());
        assertEquals("EXCLUIDO", resultado.get(0).tratamientoAntes());
        assertEquals("GRAVADO", resultado.get(0).tratamientoDespues());
    }

    /** El requisito lo pide expresamente: sin cruces, lista vacía y no error. */
    @Test
    void unaSimulacionSinCrucesDevuelveListaVaciaYNoUnError() {
        when(precios.simularCruces(anyInt(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(List.of());

        assertTrue(servicio.simular(solicitud("SOBRE_COSTO", "1.2000")).isEmpty());
    }

    @Test
    void laSimulacionCompruebaElTopeAntesDeConsultarNada() {
        assertThrows(ReglaInvalidaException.class,
                () -> servicio.simular(solicitud("SOBRE_PRECIO", "2.5000")));

        verify(precios, never()).simularCruces(anyInt(), any(), any(), anyString(),
                anyString(), any());
    }

    @Test
    void elAlcanceTambienCompruebaQueElAmbitoSeaPropio() {
        when(precios.categoriaPropia(EMPRESA, 99)).thenReturn(false);

        assertThrows(ReferenciaAjenaException.class, () -> servicio.alcance(99, null));
        verify(precios, never()).productosAlcanzados(anyInt(), any(), any());
    }
}
