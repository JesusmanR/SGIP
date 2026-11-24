package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.datos.RepositorioImportacion;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Escribe la plantilla de precios de un proveedor: un .xlsx con sus productos activos y el
 * costo que rige hoy, para que el administrador lo llene y lo suba de nuevo.
 *
 * Es, junto con {@link LectorHojaDeCalculo}, una de las dos clases que importan Apache POI
 * (ADR-0007): la que lee y la que escribe hojas de cálculo. Lo demás del backend no sabe qué
 * biblioteca hay detrás.
 *
 * ## Que vuelva a entrar sin errores
 *
 * La hoja 0 se llama «Precios» y sus encabezados —Referencia, Descripción, Costo— son tres de
 * los nombres que el lector reconoce, de modo que la plantilla se sube tal cual. La referencia
 * es el código del producto, que es por lo que el juicio de la carga busca primero. El costo
 * va como número con dos decimales, que es la escala de la columna: sin decimales se perderían
 * los centavos, y en «General» una cifra grande se vuelve notación científica que el lector
 * rechazaría. Un costo nulo o de cero queda en una celda vacía, para que se note que falta; si
 * se sube así, la fila se rechaza como costo no numérico y no se aplica nada.
 *
 * ## Los textos no son fórmulas
 *
 * Una referencia o un nombre que empiece por `=`, `+`, `-` o `@` lo tomaría Excel por una
 * fórmula, y el contenido lo escribe el catálogo (o el proveedor, antes). Todos los textos van
 * como celdas de texto con el estilo `quotePrefixed`, que es lo que Excel escribe cuando alguien
 * teclea un apóstrofo delante: el valor guardado es el texto exacto, sin un `'` de verdad, y el
 * lector lo devuelve igual.
 *
 * ## Memoria
 *
 * `SXSSFWorkbook(100)` mantiene en memoria cien filas y manda el resto a un archivo temporal,
 * y los productos llegan con un cursor (ver
 * {@link RepositorioImportacion#recorrerProductosActivosDelProveedor}), de modo que lo que se
 * retiene no crece con el número de productos. El archivo temporal se borra en el `finally`.
 */
@Component
public class GeneradorPlantillaPrecios {

    static final String HOJA_PRECIOS = "Precios";
    static final String HOJA_INSTRUCCIONES = "Instrucciones";

    /** Filas que SXSSF conserva en memoria antes de mandarlas al disco. */
    private static final int FILAS_EN_MEMORIA = 100;

    /** El nombre del archivo no necesita más que el proveedor se reconozca. */
    private static final int LARGO_DEL_NOMBRE = 60;

    private final RepositorioImportacion importacion;

    public GeneradorPlantillaPrecios(RepositorioImportacion importacion) {
        this.importacion = importacion;
    }

    /**
     * El nombre con que se descarga: `plantilla-precios-<proveedor>.xlsx`, con el nombre del
     * proveedor en ASCII, en minúsculas y con guiones.
     *
     * Solo deja letras y dígitos, de modo que nada del nombre puede romper la cabecera
     * `Content-Disposition` (comillas, saltos de línea, punto y coma). También es el punto donde
     * se comprueba que el proveedor sea de la empresa: el controlador lo llama antes de escribir
     * nada, para que un proveedor ajeno sea un 404 y no una descarga a medias.
     *
     * @throws RecursoNoEncontradoException si el proveedor no existe, es de otra empresa o es el
     *                                      reservado de los productos sin proveedor
     */
    public String nombreDeArchivo(Integer empresaId, Integer proveedorId) {
        return "plantilla-precios-" + enMinusculasConGuiones(proveedorDe(empresaId, proveedorId).nombre())
                + ".xlsx";
    }

    /**
     * Escribe el .xlsx en `salida`.
     *
     * No cierra `salida`: es de quien la abrió. Vuelve a comprobar el proveedor aunque el
     * controlador ya lo haya hecho con {@link #nombreDeArchivo}: esta clase no debe poder
     * escribir una plantilla de un proveedor que no es de la empresa, llámese como se llame.
     *
     * @throws RecursoNoEncontradoException antes de escribir ningún byte
     */
    public void escribir(Integer empresaId, Integer proveedorId, OutputStream salida)
            throws IOException {
        ProveedorDePlantilla proveedor = proveedorDe(empresaId, proveedorId);

        try (SXSSFWorkbook libro = new SXSSFWorkbook(FILAS_EN_MEMORIA)) {
            try {
                Estilos estilos = new Estilos(libro);
                escribirPrecios(libro.createSheet(HOJA_PRECIOS), estilos, empresaId, proveedorId);
                escribirInstrucciones(libro.createSheet(HOJA_INSTRUCCIONES), estilos, proveedor);
                libro.write(salida);
            } finally {
                // El archivo temporal en que SXSSF vuelca las filas no se borra solo.
                libro.dispose();
            }
        }
    }

    private ProveedorDePlantilla proveedorDe(Integer empresaId, Integer proveedorId) {
        // Responder igual ante «no existe» y «es de otra empresa» es a propósito: distinguirlos
        // le diría a quien sondea qué identificadores están en uso en otras empresas.
        ProveedorDePlantilla proveedor = importacion.proveedorParaPlantilla(empresaId, proveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "El proveedor " + proveedorId + " no existe en esta empresa."));
        if (RepositorioPlataforma.NIT_SIN_PROVEEDOR.equals(proveedor.nit())) {
            throw new RecursoNoEncontradoException(
                    "Los productos sin proveedor no tienen plantilla: asígnalos a su proveedor "
                            + "desde el catálogo.");
        }
        return proveedor;
    }

    private void escribirPrecios(Sheet hoja, Estilos estilos, Integer empresaId, Integer proveedorId) {
        hoja.setColumnWidth(0, 22 * 256);
        hoja.setColumnWidth(1, 60 * 256);
        hoja.setColumnWidth(2, 18 * 256);
        hoja.createFreezePane(0, 1);

        Row encabezado = hoja.createRow(0);
        encabezar(encabezado, 0, "Referencia", estilos);
        encabezar(encabezado, 1, "Descripción", estilos);
        encabezar(encabezado, 2, "Costo", estilos);

        AtomicInteger siguiente = new AtomicInteger(1);
        importacion.recorrerProductosActivosDelProveedor(empresaId, proveedorId, producto -> {
            Row fila = hoja.createRow(siguiente.getAndIncrement());
            texto(fila, 0, producto.codigo(), estilos);
            texto(fila, 1, producto.nombre(), estilos);
            BigDecimal costo = producto.costo();
            Cell celda = fila.createCell(2);
            celda.setCellStyle(estilos.costo);
            if (costo != null && costo.signum() > 0) {
                celda.setCellValue(costo.doubleValue());
            }
        });
    }

    private void escribirInstrucciones(Sheet hoja, Estilos estilos, ProveedorDePlantilla proveedor) {
        hoja.setColumnWidth(0, 110 * 256);
        List<String> lineas = List.of(
                "Plantilla de precios de " + proveedor.nombre(),
                "",
                "Cómo usarla",
                "1. Escribe el costo nuevo de cada producto en la columna Costo de la hoja Precios.",
                "2. No cambies la columna Referencia: con ella el sistema reconoce cada producto.",
                "3. Un producto cuyo costo no cambia puede quedar como viene, o puedes borrar su fila.",
                "4. Una fila sin costo se rechaza al subir el archivo. Si no tienes el costo de un "
                        + "producto, borra su fila.",
                "5. Escribe el costo como número, sin símbolo de moneda. Tiene que ser mayor que cero.",
                "6. Solo salen los productos activos de este proveedor. Una referencia que sea de "
                        + "otro proveedor se rechaza.",
                "7. Sube el archivo en la importación de listas de precios, elige este mismo "
                        + "proveedor y revisa la vista previa antes de aplicarla.");
        for (int i = 0; i < lineas.size(); i++) {
            Row fila = hoja.createRow(i);
            texto(fila, 0, lineas.get(i), estilos);
        }
    }

    private static void encabezar(Row fila, int columna, String texto, Estilos estilos) {
        Cell celda = fila.createCell(columna);
        celda.setCellValue(texto);
        celda.setCellStyle(estilos.encabezado);
    }

    /** Un texto como texto: nunca como fórmula, y sin un apóstrofo escrito delante. */
    private static void texto(Row fila, int columna, String valor, Estilos estilos) {
        Cell celda = fila.createCell(columna);
        celda.setCellValue(valor == null ? "" : valor);
        celda.setCellStyle(estilos.texto);
    }

    /**
     * Pasa un nombre a minúsculas ASCII separadas por un guion.
     *
     * Las tildes se descomponen y se descartan (`Ñandú` queda `nandu`), y cualquier otra cosa que
     * no sea letra o dígito —espacios, puntos, comillas, saltos de línea— es un separador. Un
     * nombre sin ninguna letra ni dígito deja `proveedor` en vez de un nombre de archivo vacío.
     */
    private static String enMinusculasConGuiones(String nombre) {
        String sinTildes = Normalizer.normalize(nombre == null ? "" : nombre, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String limpio = sinTildes.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (limpio.length() > LARGO_DEL_NOMBRE) {
            limpio = limpio.substring(0, LARGO_DEL_NOMBRE).replaceAll("-+$", "");
        }
        return limpio.isEmpty() ? "proveedor" : limpio;
    }

    /**
     * Los estilos del libro, creados una sola vez.
     *
     * Un estilo por celda agotaría el límite de estilos del formato (64 000) con veinte mil
     * productos, y cada uno pesa. Todas las celdas del mismo tipo comparten el suyo.
     */
    private static final class Estilos {
        final CellStyle encabezado;
        final CellStyle texto;
        final CellStyle costo;

        Estilos(SXSSFWorkbook libro) {
            Font negrita = libro.createFont();
            negrita.setBold(true);
            encabezado = libro.createCellStyle();
            encabezado.setFont(negrita);

            texto = libro.createCellStyle();
            texto.setQuotePrefixed(true);

            // Dos decimales, que es la escala de `productos.costo`: nunca la notación científica
            // ni los miles con punto, que el lector tomaría por otra cosa.
            costo = libro.createCellStyle();
            costo.setDataFormat(libro.createDataFormat().getFormat("0.00"));
        }
    }
}
