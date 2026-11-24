package co.com.bspstore.sgip.logica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * El documento con que se identifica una empresa (NIT o CC): solo dígitos, de 5 a 15, sin dígito de
 * verificación. La norma no cambia con el tipo, así que no hay una prueba por tipo.
 */
class DocumentoEmpresaTest {

    @Test
    void nitConPuntosYEspaciosSeNormaliza() {
        assertEquals("900123456", DocumentoEmpresa.normalizar("900.123.456"));
        assertEquals("900123456", DocumentoEmpresa.normalizar(" 900 123 456 "));
        assertEquals("900123456", DocumentoEmpresa.normalizar("900-123-456"));
    }

    @Test
    void ccSeNormaliza() {
        assertEquals("1098765432", DocumentoEmpresa.normalizar("1.098.765.432"));
        assertEquals("1098765432", DocumentoEmpresa.normalizar("1 098 765 432"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"90012345A", "ABCDEFGHI", "900_123_456", "900/123/456", "9001234,56", "", "   ", "...", "---"})
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
     * El dígito de verificación nunca se pide ni se valida (decisión del usuario): ni con guion ni
     * pegado, y un dígito cualquiera no hace inválido el número. {@code 900123456-1} y
     * {@code 900123456-7} son el mismo documento más un dígito, y los dos pasan: lo que se guarda es
     * lo que el usuario escribió, sin recalcular nada.
     */
    @Test
    void nuncaExigeDigitoDeVerificacion() {
        // Sin dígito: pasa.
        assertEquals("900123456", DocumentoEmpresa.normalizar("900123456"));
        // Con un dígito que no corresponde al módulo 11 de la DIAN: pasa igual, no se valida.
        assertEquals("9001234561", DocumentoEmpresa.normalizar("900123456-1"));
        assertEquals("9001234567", DocumentoEmpresa.normalizar("900123456-7"));
        assertEquals("9001234560", DocumentoEmpresa.normalizar("900123456-0"));
    }
}
