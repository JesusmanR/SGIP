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

    /**
     * Las formas de escribir un 404 sin cuerpo: {@code notFound()}, un
     * {@code NOT_FOUND} o {@code status(404)} cerrados con {@code build()},
     * {@code ResponseEntity.of(Optional)} y {@code ofNullable}, que responden
     * 404 vacío cuando no hay valor, y {@code new ResponseEntity} que lleva solo
     * el estado 404 (o un cuerpo nulo con él).
     */
    private static final Pattern RESPUESTA_VACIA = Pattern.compile(
            "\\.notFound\\(\\)"
                    + "|NOT_FOUND\\)\\s*\\.build\\(\\)"
                    + "|status\\(\\s*404\\s*\\)\\s*\\.build\\(\\)"
                    + "|ResponseEntity\\s*\\.\\s*of(Nullable)?\\s*\\("
                    + "|new\\s+ResponseEntity\\s*<[^>]*>\\s*\\(\\s*(null\\s*,\\s*)?"
                    + "(HttpStatus\\s*\\.\\s*)?NOT_FOUND\\s*\\)");

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

    @Test
    void laBusquedaReconoceCadaFormaDeUn404Vacio() {
        List<String> formas = List.of(
                "return ResponseEntity.notFound().build();",
                "return ResponseEntity.status(HttpStatus.NOT_FOUND).build();",
                "return ResponseEntity.status(404).build();",
                "return ResponseEntity.of(repositorio.buscar(id));",
                "return ResponseEntity.ofNullable(servicio.buscar(id));",
                "return new ResponseEntity<>(HttpStatus.NOT_FOUND);",
                "return new ResponseEntity<Producto>(HttpStatus.NOT_FOUND);",
                "return new ResponseEntity<>(null, HttpStatus.NOT_FOUND);");
        for (String forma : formas) {
            assertTrue(RESPUESTA_VACIA.matcher(forma).find(), "no detecta: " + forma);
        }
    }

    @Test
    void laBusquedaNoMarcaRespuestasConCuerpo() {
        List<String> inocuas = List.of(
                "return ResponseEntity.ok(producto);",
                "return ResponseEntity.noContent().build();",
                "return new ResponseEntity<>(problema, HttpStatus.NOT_FOUND);",
                "return ResponseEntity.status(HttpStatus.CREATED).body(x);");
        for (String codigo : inocuas) {
            assertTrue(!RESPUESTA_VACIA.matcher(codigo).find(), "marca de más: " + codigo);
        }
    }

    private static String leer(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + p, e);
        }
    }
}
