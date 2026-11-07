package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.logica.ClaveActualIncorrectaException;
import co.com.bspstore.sgip.logica.CorreoRepetidoException;
import co.com.bspstore.sgip.logica.NitRepetidoException;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que el cliente lee de un error es el {@code type} del cuerpo, y llega por el
 * manejador que Spring elige: con las excepciones hijas de
 * {@link RegistroRepetidoException} se comprueba que gana la más específica y que
 * el resto de registros repetidos no hereda el tipo.
 */
class TiposDeErrorReconocibleTest {

    @RestController
    static class Lanzador {
        @GetMapping("/nit")
        void nit() { throw new NitRepetidoException("NIT repetido."); }

        @GetMapping("/correo")
        void correo() { throw new CorreoRepetidoException("Correo repetido."); }

        @GetMapping("/otro")
        void otro() { throw new RegistroRepetidoException("Regla repetida."); }

        @GetMapping("/clave")
        void clave() { throw new ClaveActualIncorrectaException(); }
    }

    private MockMvc mvc;

    @BeforeEach
    void montar() {
        mvc = MockMvcBuilders.standaloneSetup(new Lanzador())
                .setControllerAdvice(new ManejadorErrores()).build();
    }

    @Test
    void elNitRepetidoLlegaConSuTipo() throws Exception {
        mvc.perform(get("/nit")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:sgip:nit-repetido"))
                .andExpect(jsonPath("$.detail").value("NIT repetido."));
    }

    @Test
    void elCorreoRepetidoLlegaConSuTipo() throws Exception {
        mvc.perform(get("/correo")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:sgip:correo-repetido"));
    }

    @Test
    void otroRegistroRepetidoLlegaSinTipoPropio() throws Exception {
        String cuerpo = mvc.perform(get("/otro")).andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertFalse(cuerpo.contains("urn:sgip:"), cuerpo);
    }

    @Test
    void laClaveActualIncorrectaLlegaConSuTipo() throws Exception {
        mvc.perform(get("/clave")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:sgip:clave-actual-incorrecta"));
    }
}
