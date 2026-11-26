package co.com.bspstore.sgip.logica.recuperacion;

import co.com.bspstore.sgip.datos.AuditoriaAccesoRepositorio;
import co.com.bspstore.sgip.datos.RepositorioCodigosRecuperacion;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.logica.correo.EnviadorDeCorreo;
import co.com.bspstore.sgip.modelo.Usuario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Detalles de la tarea que no necesitan la base. */
class TareaDeRecuperacionTest {

    private Locale localeAnterior;

    @BeforeEach
    void guardarLocale() {
        localeAnterior = Locale.getDefault();
    }

    @AfterEach
    void restaurarLocale() {
        Locale.setDefault(localeAnterior);
    }

    @Test
    void elCodigoSiempreLlevaDigitosAsciiAunqueElServidorEsteEnOtroIdioma() {
        // En un servidor con la región en árabe, hindi o similar, «%06d» sin Locale escribe los
        // dígitos de esa escritura y el código no se podría teclear ni comparar.
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
        UsuarioRepositorio usuarios = mock(UsuarioRepositorio.class);
        Usuario usuario = mock(Usuario.class);
        when(usuario.getId()).thenReturn(7);
        when(usuario.isActivo()).thenReturn(true);
        when(usuario.getCorreo()).thenReturn("ana@tienda.com");
        when(usuarios.findByCorreo("ana@tienda.com")).thenReturn(Optional.of(usuario));
        EnviadorDeCorreo enviador = mock(EnviadorDeCorreo.class);
        var tarea = new TareaDeRecuperacion(usuarios, mock(AuditoriaAccesoRepositorio.class),
                mock(RepositorioCodigosRecuperacion.class), enviador,
                Clock.fixed(Instant.parse("2026-11-26T15:00:00Z"), ZoneOffset.UTC));

        // Sin transacción de Spring en esta prueba, el correo sale en el acto.
        for (int i = 0; i < 20; i++) {
            tarea.procesar("ana@tienda.com", "10.0.0.5");
        }

        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(enviador, times(20))
                .enviar(eq("ana@tienda.com"), anyString(), texto.capture());
        for (String t : texto.getAllValues()) {
            assertThat(t).matches("Tu código es [0-9]{6}[.] .*");
        }
    }
}
