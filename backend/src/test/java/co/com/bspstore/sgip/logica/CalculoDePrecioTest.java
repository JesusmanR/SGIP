package co.com.bspstore.sgip.logica;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El redondeo al peso, contra la tabla que comparte con la vista y el navegador.
 *
 * El archivo es el mismo que leen `database/verificar_precios.sh` y
 * `web/src/servicios/precio.prueba.js`, de modo que ninguno de los tres puede
 * redondear distinto sin que su prueba se ponga en rojo. Si el archivo no se
 * encuentra, la prueba falla: una tabla que no se lee no prueba nada.
 *
 * Solo mira las filas de precio. El margen no se calcula en Java: lo da la
 * vista, y las filas de margen las comprueban los otros dos.
 */
class CalculoDePrecioTest {

    /** Maven ejecuta las pruebas con `backend/` como directorio de trabajo. */
    private static final Path TABLA = Path.of("../database/muestras/casos_de_precio.csv");

    @Test
    void elPrecioDesdeElPorcentajeCoincideConLaTablaCompartida() throws IOException {
        List<String[]> filas = Files.readAllLines(TABLA, StandardCharsets.UTF_8).stream()
                .skip(1)
                .map(linea -> linea.split(",", 5))
                .filter(c -> c[0].equals("precio_desde_porcentaje"))
                .toList();

        assertTrue(filas.size() >= 10, "la tabla de casos parece recortada: " + filas.size());
        assertAll(filas.stream().map(c -> () -> {
            BigDecimal obtenido = CalculoDePrecio.precioDesdePorcentaje(
                    new BigDecimal(c[1]), new BigDecimal(c[2]));
            assertEquals(0, new BigDecimal(c[3]).compareTo(obtenido),
                    "costo " + c[1] + " con " + c[2] + " %: esperaba " + c[3]
                            + " y dio " + obtenido.toPlainString() + " (" + c[4] + ")");
        }));
    }

    @Test
    void elFactorGuardaUnoMasElPorcentaje() {
        assertEquals(new BigDecimal("1.2000"),
                CalculoDePrecio.factorDesdePorcentaje(new BigDecimal("20")));
        assertEquals(new BigDecimal("1.3333"),
                CalculoDePrecio.factorDesdePorcentaje(new BigDecimal("33.33")));
    }
}
