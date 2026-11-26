package co.com.bspstore.sgip.modelo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La fábrica {@code evento} deja en la bitácora de accesos un hecho que no es un
 * ingreso (la recuperación de la contraseña, RF-65): acepta que no haya usuario y
 * recorta lo que la columna no admite, para que un correo escrito a propósito muy
 * largo no haga fallar la inserción de la auditoría.
 */
class AuditoriaAccesoTest {

    @Test
    void eventoSinUsuario() {
        String correo = "a".repeat(300);
        String origen = "9".repeat(100);

        AuditoriaAcceso evento = AuditoriaAcceso.evento(
                null, correo, false, MotivoRechazo.RECUPERACION_CORREO_INEXISTENTE, origen);

        assertEquals(120, evento.getCorreo().length());
        assertEquals(60, evento.getOrigen().length());
        assertFalse(evento.isExitoso());
        assertEquals("RECUPERACION_CORREO_INEXISTENTE", evento.getMotivo());
    }

    @Test
    void eventoExitosoConservaLosValoresCortos() {
        AuditoriaAcceso evento = AuditoriaAcceso.evento(
                null, "ana@tienda.com", true, MotivoRechazo.RECUPERACION_SOLICITADA, "10.0.0.7");

        assertTrue(evento.isExitoso());
        assertEquals("ana@tienda.com", evento.getCorreo());
        assertEquals("10.0.0.7", evento.getOrigen());
        assertEquals("RECUPERACION_SOLICITADA", evento.getMotivo());
    }

    @Test
    void elOrigenAusenteSeConservaAusente() {
        AuditoriaAcceso evento = AuditoriaAcceso.evento(
                null, "ana@tienda.com", false, MotivoRechazo.RECUPERACION_LIMITE, null);

        assertNull(evento.getOrigen());
    }

    @Test
    void losSeisMotivosDeRecuperacionExisten() {
        for (String nombre : new String[] {
                "RECUPERACION_SOLICITADA", "RECUPERACION_CORREO_INEXISTENTE",
                "RECUPERACION_CUENTA_INACTIVA", "RECUPERACION_LIMITE",
                "RECUPERACION_EXITOSA", "RECUPERACION_CODIGO_INVALIDO"}) {
            assertEquals(nombre, MotivoRechazo.valueOf(nombre).name());
        }
    }
}
