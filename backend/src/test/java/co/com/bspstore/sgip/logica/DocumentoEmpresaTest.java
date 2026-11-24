package co.com.bspstore.sgip.logica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * El documento con que se identifica una empresa (NIT o CC): solo dígitos, de 5 a 15, y NUNCA con dígito de
 * verificación. Se aceptan puntos y espacios como separadores; el guion no, porque el guion es justo lo que
 * separa el dígito de verificación ({@code 900123456-1}) y quitarlo guardaría otro número (9001234561).
 */
class DocumentoEmpresaTest {

    @Test
    void nitConPuntosYEspaciosSeNormaliza() {
        assertEquals("900123456", DocumentoEmpresa.normalizar("900.123.456"));
        assertEquals("900123456", DocumentoEmpresa.normalizar(" 900 123 456 "));
    }

    @Test
    void nitConPuntosSinDigitoSeNormaliza() {
        assertEquals("900123456", DocumentoEmpresa.normalizar("900.123.456"));
    }

    @Test
    void ccSeNormaliza() {
        assertEquals("1098765432", DocumentoEmpresa.normalizar("1.098.765.432"));
        assertEquals("1098765432", DocumentoEmpresa.normalizar("1 098 765 432"));
    }

    @Test
    void nitConGuionYDigitoEs400ConMensajeClaro() {
        for (String texto : new String[]{"900123456-1", "900.123.456-1", "900123456 - 1", "900-123-456", "-900123456"}) {
            DocumentoEmpresaInvalidoException e = assertThrows(DocumentoEmpresaInvalidoException.class,
                    () -> DocumentoEmpresa.normalizar(texto), texto);

            assertEquals("Escribe solo el número, sin el dígito de verificación.", e.getMessage(), texto);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"90012345A", "ABCDEFGHI", "900_123_456", "900/123/456", "9001234,56", "", "   ", "..."})
    @NullSource
    void letrasSonInvalidas(String texto) {
        DocumentoEmpresaInvalidoException e = assertThrows(DocumentoEmpresaInvalidoException.class,
                () -> DocumentoEmpresa.normalizar(texto));

        // El mensaje es del código y no repite lo que escribió el cliente.
        assertEquals(DocumentoEmpresaInvalidoException.MENSAJE, e.getMessage());
    }

    @Test
    void cortoOLargoEsInvalido() {
        assertThrows(DocumentoEmpresaInvalidoException.class, () -> DocumentoEmpresa.normalizar("1234"));
        assertThrows(DocumentoEmpresaInvalidoException.class, () -> DocumentoEmpresa.normalizar("1.2.3.4"));
        assertThrows(DocumentoEmpresaInvalidoException.class,
                () -> DocumentoEmpresa.normalizar("1234567890123456"));

        // Los extremos del rango sí entran.
        assertEquals("12345", DocumentoEmpresa.normalizar("12345"));
        assertEquals("123456789012345", DocumentoEmpresa.normalizar("123456789012345"));
    }

    /**
     * El dígito de verificación nunca se pide ni se valida (decisión del usuario): el número se escribe sin
     * él, y el sistema no calcula nada. Lo que se guarda es lo que el usuario escribió.
     */
    @Test
    void nuncaExigeDigitoDeVerificacion() {
        assertEquals("900123456", DocumentoEmpresa.normalizar("900123456"));
        assertEquals("900123456", DocumentoEmpresa.normalizar("900.123.456"));
    }

    /**
     * Las semillas anteriores guardan el NIT como {@code NNNNNNNNN-D}: para compararlas con un número
     * nuevo se les quita el sufijo del dígito de verificación, y solo a ellas.
     */
    @Test
    void elFormatoViejoConDigitoSeComparaSinElDigito() {
        assertEquals("900123456", DocumentoEmpresa.normalizadoOTalCual("900123456-1"));
        assertEquals("900000000", DocumentoEmpresa.normalizadoOTalCual("900000000-0"));
        assertEquals("900123456", DocumentoEmpresa.normalizadoOTalCual("900.123.456-7"));
        assertEquals("900123456", DocumentoEmpresa.normalizadoOTalCual("900123456"));
    }
}
