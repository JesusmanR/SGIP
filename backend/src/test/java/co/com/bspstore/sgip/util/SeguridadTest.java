package co.com.bspstore.sgip.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeguridadTest {

    @Test
    void aceptaLaClaveCorrecta() {
        String hash = Seguridad.derivar("SgipDev2026");
        assertTrue(Seguridad.verificar("SgipDev2026", hash));
    }

    @Test
    void rechazaUnaClaveIncorrecta() {
        String hash = Seguridad.derivar("SgipDev2026");
        assertFalse(Seguridad.verificar("SgipDev2027", hash));
    }

    @Test
    void laMismaClaveProduceHashesDistintos() {
        assertNotEquals(Seguridad.derivar("igual"), Seguridad.derivar("igual"));
    }

    @Test
    void elFormatoEsElDocumentado() {
        String[] partes = Seguridad.derivar("x").split("\\$");
        assertEquals(4, partes.length);
        assertEquals("pbkdf2", partes[0]);
        assertEquals("120000", partes[1]);
    }

    @Test
    void rechazaFormatosCorruptosSinLanzarExcepciones() {
        assertFalse(Seguridad.verificar("x", null));
        assertFalse(Seguridad.verificar(null, Seguridad.derivar("x")));
        assertFalse(Seguridad.verificar("x", "texto-plano"));
        assertFalse(Seguridad.verificar("x", "pbkdf2$abc$sal$hash"));
    }

    @Test
    void laClaveNuncaApareceEnElHash() {
        assertFalse(Seguridad.derivar("SgipDev2026").contains("SgipDev2026"));
    }

    @Test
    void cadaTokenEsDistinto() {
        assertNotEquals(Seguridad.nuevoToken(), Seguridad.nuevoToken());
    }

    @Test
    void elTokenViajaSeguroEnUnaUrl() {
        String token = Seguridad.nuevoToken();
        assertFalse(token.contains("+"));
        assertFalse(token.contains("/"));
        assertFalse(token.contains("="));
        assertTrue(token.length() >= 40);
    }

    @Test
    void laHuellaEsEstableYDeLargoFijo() {
        String token = Seguridad.nuevoToken();
        assertEquals(Seguridad.huella(token), Seguridad.huella(token));
        assertEquals(64, Seguridad.huella(token).length());
    }

    @Test
    void tokensDistintosProducenHuellasDistintas() {
        assertNotEquals(Seguridad.huella(Seguridad.nuevoToken()),
                Seguridad.huella(Seguridad.nuevoToken()));
    }

    @Test
    void laHuellaNoPermiteRecuperarElToken() {
        String token = Seguridad.nuevoToken();
        assertFalse(Seguridad.huella(token).contains(token));
    }
}