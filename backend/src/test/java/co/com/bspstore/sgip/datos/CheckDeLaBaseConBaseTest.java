package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.config.UsuarioAutenticado;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La red de seguridad del error 3819 de MySQL (una restricción CHECK) con un CHECK de verdad: un
 * punto de prueba ejecuta por SQL directo una fila que lo viola y la API responde 400 con un texto
 * fijo, sin el nombre de la restricción. Spring no lo traduce a una excepción de integridad: sin el
 * manejador sería un 500.
 */
@Tag("conBase")
@SpringBootTest
@AutoConfigureMockMvc
class CheckDeLaBaseConBaseTest {

    @RestController
    static class PuntoDePrueba {
        private final JdbcClient jdbc;

        PuntoDePrueba(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/api/v1/prueba/check")
        ResponseEntity<Void> violarUnCheck() {
            // ck_consecutivos_siguiente: siguiente >= 1.
            jdbc.sql("INSERT INTO consecutivos (empresa_id, tipo, siguiente) VALUES (1, 'DEVOLUCION', 0)").update();
            return ResponseEntity.ok().build();
        }
    }

    @TestConfiguration
    static class Configuracion {
        @Bean
        PuntoDePrueba puntoDePrueba(JdbcClient jdbc) {
            return new PuntoDePrueba(jdbc);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void unCheckQueLlegaAlMotorEsUn400ConTextoFijoYNoUn500() throws Exception {
        var u = new UsuarioAutenticado(1, "admin@ejemplo.co", "ADMINISTRADOR", 1);

        mvc.perform(get("/api/v1/prueba/check").with(authentication(new UsernamePasswordAuthenticationToken(u, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dato no válido"))
                .andExpect(jsonPath("$.detail").value("Uno de los datos no cumple una regla del sistema."))
                .andExpect(content().string(not(containsString("ck_consecutivos_siguiente"))))
                .andExpect(content().string(not(containsString("Check constraint"))));
    }
}
