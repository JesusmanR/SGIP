package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El plazo del bloqueo por intentos fallidos tiene un solo dueño. El ingreso, el
 * cambio de contraseña y la lista de usuarios de la plataforma tienen que
 * contestar lo mismo a «¿está bloqueado ahora?»; si cada uno leyera la propiedad
 * por su cuenta, cambiar el valor por omisión en uno los desalinearía.
 */
class PoliticaDeBloqueoTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 7, 10, 0);

    private static Usuario bloqueadoHace(int minutos) {
        var u = new Usuario(new Rol("VENDEDOR", "Vendedor"), "Ana", "ana@tienda.co",
                Seguridad.derivar("SgipDev2026"));
        u.registrarIntentoFallido(1, AHORA.minusMinutes(minutos));
        return u;
    }

    @Test
    void diceElPlazoConfigurado() {
        assertEquals(20, new PoliticaDeBloqueo(20).minutos());
    }

    @Test
    void unaCuentaBloqueadaDentroDelPlazoEstaBloqueadaHoy() {
        assertTrue(new PoliticaDeBloqueo(15).bloqueadoHoy(bloqueadoHace(14), AHORA));
    }

    @Test
    void alCumplirseElPlazoYaNoEstaBloqueadaHoy() {
        assertFalse(new PoliticaDeBloqueo(15).bloqueadoHoy(bloqueadoHace(15), AHORA));
    }

    @Test
    void unaCuentaSinMarcaNuncaEstaBloqueada() {
        var libre = new Usuario(new Rol("VENDEDOR", "Vendedor"), "Ana", "ana@tienda.co",
                Seguridad.derivar("SgipDev2026"));

        assertFalse(new PoliticaDeBloqueo(15).bloqueadoHoy(libre, AHORA));
        assertFalse(new PoliticaDeBloqueo(15).vencido(libre, AHORA));
    }

    @Test
    void elPlazoVencidoEsElQueDiceLaPoliticaYNoOtro() {
        Usuario u = bloqueadoHace(20);

        assertTrue(new PoliticaDeBloqueo(15).vencido(u, AHORA));
        assertFalse(new PoliticaDeBloqueo(30).vencido(u, AHORA));
    }

    // El PIN del administrador (ADR-0032) no es un Usuario: guarda «marcado» y «desde»
    // en sus propias columnas, y mide el plazo con la misma regla.

    @Test
    void sinMarcaNuncaEstaVencidoNiBloqueado() {
        var politica = new PoliticaDeBloqueo(15);

        assertFalse(politica.vencido(false, null, AHORA));
        assertFalse(politica.vencido(false, AHORA.minusMinutes(60), AHORA));
        assertFalse(politica.bloqueadoHoy(false, null, AHORA));
        assertFalse(politica.bloqueadoHoy(false, AHORA.minusMinutes(5), AHORA));
    }

    @Test
    void marcadoYDentroDelPlazoEstaBloqueado() {
        var politica = new PoliticaDeBloqueo(15);

        assertFalse(politica.vencido(true, AHORA.minusMinutes(14), AHORA));
        assertTrue(politica.bloqueadoHoy(true, AHORA.minusMinutes(14), AHORA));
        assertTrue(politica.bloqueadoHoy(true, AHORA, AHORA));
    }

    @Test
    void alCumplirseElPlazoElInstanteExactoYaVencio() {
        var politica = new PoliticaDeBloqueo(15);

        assertTrue(politica.vencido(true, AHORA.minusMinutes(15), AHORA));
        assertFalse(politica.bloqueadoHoy(true, AHORA.minusMinutes(15), AHORA));
        assertTrue(politica.vencido(true, AHORA.minusMinutes(15).minusSeconds(1), AHORA));
        assertFalse(politica.vencido(true, AHORA.minusMinutes(15).plusSeconds(1), AHORA));
    }

    @Test
    void marcadoSinInstanteSeTomaPorVencido() {
        var politica = new PoliticaDeBloqueo(15);

        assertTrue(politica.vencido(true, null, AHORA));
        assertFalse(politica.bloqueadoHoy(true, null, AHORA));
    }

    @Test
    void lasDosFormasDeAsomarseALaReglaContestanLoMismo() {
        var politica = new PoliticaDeBloqueo(15);

        for (int minutos : new int[]{0, 1, 14, 15, 16, 60}) {
            Usuario u = bloqueadoHace(minutos);
            LocalDateTime desde = AHORA.minusMinutes(minutos);

            assertEquals(politica.vencido(u, AHORA), politica.vencido(true, desde, AHORA),
                    "vencido a los " + minutos);
            assertEquals(politica.bloqueadoHoy(u, AHORA), politica.bloqueadoHoy(true, desde, AHORA),
                    "bloqueado hoy a los " + minutos);
        }
    }

    /**
     * Solo la política lee la propiedad y escribe su valor por omisión. Los
     * servicios la reciben ya resuelta: así no hay un segundo «15» que corregir.
     */
    @Test
    void soloLaPoliticaLeeLaPropiedadDelPlazo() {
        assertTrue(leeLaPropiedad(PoliticaDeBloqueo.class));
        assertFalse(leeLaPropiedad(ServicioAutenticacion.class));
        assertFalse(leeLaPropiedad(ServicioPlataforma.class));
        assertFalse(leeLaPropiedad(ServicioAutorizacion.class));
    }

    private static boolean leeLaPropiedad(Class<?> clase) {
        return Arrays.stream(clase.getDeclaredConstructors())
                .flatMap(c -> Arrays.stream(c.getParameters()))
                .map(PoliticaDeBloqueoTest::valorDe)
                .anyMatch(v -> v != null && v.contains("minutos-de-bloqueo"));
    }

    private static String valorDe(Parameter p) {
        Value v = p.getAnnotation(Value.class);
        return v == null ? null : v.value();
    }
}
