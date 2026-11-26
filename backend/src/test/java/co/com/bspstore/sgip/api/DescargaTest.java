package co.com.bspstore.sgip.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** La salida de una descarga: las cabeceras de adjunto salen con el primer byte, no antes. */
class DescargaTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Test
    void sinEscribirNadaNoPoneCabeceras() {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        new Descarga(respuesta, "informe.xlsx");

        assertNull(respuesta.getHeader("Content-Disposition"));
        assertNull(respuesta.getContentType());
    }

    @Test
    void elPrimerByteFijaLasCabecerasYPasaElContenido() throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        Descarga salida = new Descarga(respuesta, "informe-x.xlsx");

        salida.write(new byte[] {1, 2, 3}, 0, 3);
        salida.write(4);

        assertEquals("attachment; filename=\"informe-x.xlsx\"", respuesta.getHeader("Content-Disposition"));
        assertEquals("no-store", respuesta.getHeader("Cache-Control"));
        assertEquals(XLSX, respuesta.getContentType());
        assertEquals(4, respuesta.getContentAsByteArray().length);
    }

    @Test
    void elFlushTambienFijaLasCabeceras() throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        new Descarga(respuesta, "a.xlsx").flush();

        assertEquals("attachment; filename=\"a.xlsx\"", respuesta.getHeader("Content-Disposition"));
    }

    @Test
    void aplicarCabecerasDosVecesNoLasRepite() throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        Descarga salida = new Descarga(respuesta, "a.xlsx");

        salida.aplicarCabeceras();
        salida.aplicarCabeceras();

        assertEquals(1, respuesta.getHeaders("Content-Disposition").size());
    }
}
