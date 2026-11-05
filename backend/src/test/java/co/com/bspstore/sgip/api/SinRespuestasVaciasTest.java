package co.com.bspstore.sgip.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Impide volver a escribir un 404 sin cuerpo en un controlador.
 *
 * {@code ResponseEntity.notFound().build()} responde con el cuerpo vacío, y
 * el cliente solo sabe decir «No se pudo completar la operación». Un recurso
 * que no existe se dice lanzando {@code RecursoNoEncontradoException}, que
 * {@link ManejadorErrores} convierte en un {@code ProblemDetail}.
 *
 * Es una búsqueda en el código fuente y no una regla de arquitectura de
 * verdad: no hay ArchUnit en el proyecto y el patrón es textual.
 */
class SinRespuestasVaciasTest {

    private static final Path CONTROLADORES =
            Path.of("src/main/java/co/com/bspstore/sgip/api");

    private static final Pattern RESPUESTA_VACIA = Pattern.compile(
            "\\.notFound\\(\\)|NOT_FOUND\\)\\s*\\.build\\(\\)|status\\(\\s*404\\s*\\)\\s*\\.build\\(\\)");

    @Test
    void ningunControladorDevuelveUn404SinCuerpo() throws IOException {
        List<String> culpables;
        try (Stream<Path> archivos = Files.walk(CONTROLADORES)) {
            culpables = archivos
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> RESPUESTA_VACIA.matcher(leer(p)).find())
                    .map(p -> CONTROLADORES.relativize(p).toString())
                    .sorted()
                    .toList();
        }

        assertTrue(Files.isDirectory(CONTROLADORES),
                "la búsqueda no encontró los controladores: la ruta cambió");
        assertEquals(List.of(), culpables,
                "estos archivos devuelven un 404 sin cuerpo; lanza RecursoNoEncontradoException");
    }

    private static String leer(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + p, e);
        }
    }
}
