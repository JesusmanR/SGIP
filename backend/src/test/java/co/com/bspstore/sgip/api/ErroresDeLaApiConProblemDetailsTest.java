package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Las mismas comprobaciones que {@link ErroresDeLaApiTest} con
 * {@code spring.mvc.problemdetails.enabled=true}. Con esa propiedad Spring
 * registra su propio manejador de las excepciones estándar, con texto en
 * inglés, y los textos de {@link ManejadorErrores} tienen que seguir mandando.
 */
@WebMvcTest(controllers = {ProductoControlador.class, ImportacionControlador.class,
        ImagenControlador.class})
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
@TestPropertySource(properties = "spring.mvc.problemdetails.enabled=true")
class ErroresDeLaApiConProblemDetailsTest extends ErroresDeLaApiTest {
}
