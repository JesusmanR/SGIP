package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.Panel;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPanel;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El panel de indicadores (P-02).
 *
 * El servicio es delgado a propósito —las cinco lecturas son del
 * repositorio— de modo que lo que aquí se prueba es lo único que
 * decide: cuándo tiene sentido preguntar por los cruces de umbral, y
 * que las cinco lecturas vayan acotadas a la empresa de quien pregunta.
 *
 * Lo que las consultas devuelven se prueba contra la base en
 * `database/verificar_panel.sh`, que es el único sitio donde se puede
 * probar: un SQL mockeado devuelve lo que el mock diga.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioPanelTest {

    private static final int EMPRESA = 4;
    private static final int USUARIO = 9;
    private static final long CARGA = 77L;

    @Mock RepositorioPanel repositorio;

    private ServicioPanel servicio;

    private static final Panel.Cifras CIFRAS = new Panel.Cifras(
            12, 2, 3, 1, new BigDecimal("500000.00"), new BigDecimal("650000.00"), 0);

    private static Panel.CargaEnPanel carga(String estado) {
        return new Panel.CargaEnPanel(CARGA, "Mayorista", "lista.csv", estado,
                LocalDateTime.now(), "APLICADA".equals(estado) ? LocalDateTime.now() : null,
                100, 98, 2);
    }

    @BeforeEach
    void preparar() {
        servicio = new ServicioPanel(repositorio);
        var u = new UsuarioAutenticado(USUARIO, "ana@tienda.co", "ADMINISTRADOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);

        when(repositorio.cifras(anyInt())).thenReturn(CIFRAS);
        when(repositorio.criticas(anyInt(), anyInt())).thenReturn(List.of());
        when(repositorio.ultimaCarga(anyInt())).thenReturn(Optional.empty());
        when(repositorio.movimientosPorDia(anyInt())).thenReturn(List.of());
        when(repositorio.cruzaronEnLaCarga(anyInt(), anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }


    @Test
    void lasCincoLecturasVanAcotadasALaEmpresaDeQuienPregunta() {
        when(repositorio.ultimaCarga(EMPRESA)).thenReturn(Optional.of(carga("APLICADA")));

        servicio.ver();

        // La RN-16 dice que el aislamiento se aplica en la consulta. Si
        // alguna de las cinco se llamara sin empresa —o con otra— el
        // panel enseñaría el inventario de otro comercio con aspecto de
        // ser el propio, y ninguna restricción se enteraría porque leer
        // no viola ninguna clave.
        verify(repositorio).cifras(EMPRESA);
        verify(repositorio).criticas(EMPRESA, 5);
        verify(repositorio).ultimaCarga(EMPRESA);
        verify(repositorio).movimientosPorDia(EMPRESA);
        verify(repositorio).cruzaronEnLaCarga(EMPRESA, CARGA);
    }

    @Test
    void sobreUnaCargaAplicadaPreguntaPorLosCrucesDeEsaCarga() {
        when(repositorio.ultimaCarga(EMPRESA)).thenReturn(Optional.of(carga("APLICADA")));
        CruceDeUmbral cruce = new CruceDeUmbral(3, "PC-003", "Portátil",
                new BigDecimal("2200000.00"), 2580000, 2640000,
                new BigDecimal("2618700.00"), "EXCLUIDO", "GRAVADO");
        when(repositorio.cruzaronEnLaCarga(EMPRESA, CARGA)).thenReturn(List.of(cruce));

        Panel panel = servicio.ver();

        assertEquals(1, panel.cruces().size());
        assertSame(cruce, panel.cruces().get(0));
    }

    @Test
    void sobreUnaCargaSinAplicarNoPreguntaPorLosCruces() {
        when(repositorio.ultimaCarga(EMPRESA)).thenReturn(Optional.of(carga("VALIDADA")));

        Panel panel = servicio.ver();

        // No es lo mismo «no hay cruces» que «todavía no se ha
        // decidido». Una carga validada no ha movido ningún costo, de
        // modo que no ha cambiado el tratamiento de nada: sus cruces son
        // hipotéticos y los enseña la pantalla de importación antes de
        // aplicar. Preguntar aquí devolvería una lista vacía correcta
        // por el motivo equivocado.
        assertTrue(panel.cruces().isEmpty());
        verify(repositorio, never()).cruzaronEnLaCarga(anyInt(), anyLong());
    }

    @Test
    void unaCargaRechazadaTampocoTieneCruces() {
        when(repositorio.ultimaCarga(EMPRESA)).thenReturn(Optional.of(carga("RECHAZADA")));

        Panel panel = servicio.ver();

        assertTrue(panel.cruces().isEmpty());
        verify(repositorio, never()).cruzaronEnLaCarga(anyInt(), anyLong());
    }

    @Test
    void unaEmpresaSinCargasDevuelveLaTarjetaNulaYNingunCruce() {
        when(repositorio.ultimaCarga(EMPRESA)).thenReturn(Optional.empty());

        Panel panel = servicio.ver();

        // Nulo y no una tarjeta vacía: es uno de los tres estados que el
        // mockup pide diseñar, y la pantalla invita a configurar un
        // proveedor. Una tarjeta con ceros diría que hubo una carga que
        // no leyó nada, que es otra cosa.
        assertNull(panel.ultimaCarga());
        assertTrue(panel.cruces().isEmpty());
        verify(repositorio, never()).cruzaronEnLaCarga(anyInt(), anyLong());
    }

    @Test
    void unaEmpresaRecienInstaladaDevuelveUnPanelEnCeroYNoUnError() {
        when(repositorio.cifras(EMPRESA)).thenReturn(
                new Panel.Cifras(0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0));

        Panel panel = servicio.ver();

        assertEquals(0, panel.cifras().referenciasActivas());
        assertEquals(BigDecimal.ZERO, panel.cifras().capitalAlCosto());
        assertTrue(panel.criticas().isEmpty());
        assertTrue(panel.movimientos().isEmpty());
        assertNull(panel.ultimaCarga());
    }

    @Test
    void laTablaCortaPideCincoFilas() {
        servicio.ver();

        // El mockup dibuja tres y un «Ver todas». Van cinco, y el número
        // se fija aquí para que cambiarlo sea una decisión y no un
        // descuido: la diferencia entre llamar a un proveedor y a tres
        // es lo que esta tabla decide.
        verify(repositorio).criticas(EMPRESA, 5);
    }

    @Test
    void loQueElRepositorioDevuelveLlegaIntactoALaRespuesta() {
        Panel.ExistenciaCritica fila = new Panel.ExistenciaCritica(
                7, "SRV-001", "Servidor", "1 u", "Servidores", "Mayorista",
                "6076000000", 0, 1, "AGOTADO");
        Panel.DiaDeMovimiento dia = new Panel.DiaDeMovimiento(LocalDate.now(), 4, 2);
        when(repositorio.criticas(EMPRESA, 5)).thenReturn(List.of(fila));
        when(repositorio.movimientosPorDia(EMPRESA)).thenReturn(List.of(dia));

        Panel panel = servicio.ver();

        assertSame(CIFRAS, panel.cifras());
        assertSame(fila, panel.criticas().get(0));
        assertSame(dia, panel.movimientos().get(0));
    }
}
