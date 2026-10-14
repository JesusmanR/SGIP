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
    void elTokenNoLlevaCaracteresQueHayaQueEscapar() {
        // Viaja en una cabecera HTTP: nada de +, / ni =
        assertTrue(Seguridad.nuevoToken().matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void elHashDelTokenCabeEnLaColumna() {
        // token_hash está declarada CHAR(64): si esto crece, la fila no entra
        assertEquals(64, Seguridad.hashToken(Seguridad.nuevoToken()).length());
    }

    @Test
    void elHashDelTokenEsEstable() {
        String token = Seguridad.nuevoToken();
        assertEquals(Seguridad.hashToken(token), Seguridad.hashToken(token));
    }

    @Test
    void tokensDistintosDanHashesDistintos() {
        assertNotEquals(Seguridad.hashToken("uno"), Seguridad.hashToken("otro"));
    }

    @Test
    void elTokenNuncaApareceEnSuHash() {
        String token = Seguridad.nuevoToken();
        assertFalse(Seguridad.hashToken(token).contains(token));
    }
}