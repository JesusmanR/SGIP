package co.com.bspstore.sgip.modelo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tres constantes y un método parecen no merecer una prueba. La merecen
 * porque de este método cuelga la RN-15: es lo que va a decidir si una
 * petición de escritura entra o se rechaza, en un solo sitio y para todo
 * el sistema. Un día que alguien «simplifique» esto a
 * {@code this == ACTIVA}, las empresas en su mes de prueba dejarían de
 * poder usar el producto que están probando, y el síntoma aparecería
 * lejos de aquí.
 */
class EstadoEmpresaTest {

    @Test
    void laEmpresaEnPruebaEscribe() {
        assertTrue(EstadoEmpresa.PRUEBA.permiteEscritura(),
                "el mes gratuito es para usar el producto, no para mirarlo");
    }

    @Test
    void laEmpresaActivaEscribe() {
        assertTrue(EstadoEmpresa.ACTIVA.permiteEscritura());
    }

    @Test
    void laEmpresaDesactivadaSoloLee() {
        assertFalse(EstadoEmpresa.DESACTIVADA.permiteEscritura(),
                "el modo de consulta de la RN-15 conserva la lectura y pierde la escritura");
    }

    /**
     * Los nombres son los del ENUM de la columna. Si alguien los cambia
     * aquí, {@code EstadoEmpresa.valueOf(...)} dejará de reconocer lo que
     * la base devuelve y el fallo aparecerá como un
     * IllegalArgumentException en la primera consulta.
     */
    @Test
    void losNombresSonLosDeLaColumna() {
        assertEquals("PRUEBA", EstadoEmpresa.PRUEBA.name());
        assertEquals("ACTIVA", EstadoEmpresa.ACTIVA.name());
        assertEquals("DESACTIVADA", EstadoEmpresa.DESACTIVADA.name());
        assertEquals(3, EstadoEmpresa.values().length,
                "si aparece un cuarto estado, hay que decidir si escribe");
    }
}
