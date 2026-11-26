package co.com.bspstore.sgip.logica.informes;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * El libro .xlsx de un informe: una hoja con el título, los filtros usados, los totales y la tabla
 * completa, sin paginar (RF-57, RF-58, RF-59, RF-69).
 *
 * Se usa con try-with-resources y se llena de arriba abajo, en este orden: {@link #filtro} y
 * {@link #total} (cuantos haga falta, en cualquier mezcla), {@link #encabezados} y {@link #fila}. El libro
 * se escribe en la salida al {@link #close() cerrar}; la salida es de quien la abrió y no se cierra.
 *
 * ## Memoria
 *
 * {@code SXSSFWorkbook(100)} mantiene cien filas en memoria y manda el resto a un archivo temporal, de
 * modo que lo que se retiene no crece con el número de filas. El archivo temporal se borra en el
 * {@code finally} de {@link #close()}, que llama a {@code dispose()}.
 *
 * ## Los textos no son fórmulas
 *
 * Un nombre que empiece por {@code =}, {@code +}, {@code -} o {@code @} lo tomaría Excel por una fórmula,
 * y el nombre lo escribe el catálogo. Todo texto va como celda de texto con el estilo {@code quotePrefixed},
 * como en {@code GeneradorPlantillaPrecios}: el valor guardado es el texto exacto.
 *
 * ## Si algo falla a medias
 *
 * {@code close()} escribe lo que haya, también cuando el cuerpo del {@code try} lanzó una excepción:
 * try-with-resources no se lo dice. Con una {@code Descarga} eso significa un 200 con un libro incompleto.
 * Quien llena el libro con datos que pueden fallar a medias debe llamar a {@link #descartar()} antes de
 * dejar que la excepción salga (por ejemplo en un {@code catch} que la relanza).
 */
public final class LibroDeInforme implements AutoCloseable {

    /** Cómo se muestra un valor. */
    public enum Formato { PESOS, UNIDADES, TEXTO, FECHA }

    /** Un valor con el formato que debe llevar en una {@link #fila}. */
    public record Celda(Object valor, Formato formato) {
    }

    /** Filas que SXSSF conserva en memoria antes de mandarlas al disco. */
    private static final int FILAS_EN_MEMORIA = 100;

    private static final String FORMATO_PESOS = "\"$\"#,##0";
    private static final String FORMATO_UNIDADES = "#,##0";
    private static final String FORMATO_FECHA = "yyyy-mm-dd";
    private static final String FORMATO_FECHA_HORA = "yyyy-mm-dd hh:mm";

    /** El nombre de una hoja admite 31 caracteres y no admite {@code [ ] : * ? / \}. */
    private static final int LARGO_DEL_NOMBRE_DE_HOJA = 31;

    private final OutputStream salida;
    private final SXSSFWorkbook libro = new SXSSFWorkbook(FILAS_EN_MEMORIA);
    private final SXSSFSheet hoja;
    private final Estilos estilos;
    private int siguienteFila;
    private boolean tablaIniciada;
    private boolean encabezadosEscritos;
    private boolean descartado;
    private boolean cerrado;

    public LibroDeInforme(OutputStream salida, String titulo) {
        this.salida = salida;
        try {
            this.hoja = libro.createSheet(nombreDeHoja(titulo));
            this.estilos = new Estilos(libro);
            Row fila = hoja.createRow(siguienteFila++);
            Cell celda = fila.createCell(0);
            celda.setCellValue(titulo);
            celda.setCellStyle(estilos.titulo);
            hoja.setColumnWidth(0, 28 * 256);
            hoja.setColumnWidth(1, 22 * 256);
        } catch (RuntimeException e) {
            libro.dispose();
            throw e;
        }
    }

    /** Un filtro usado, en una fila: su nombre y su valor. Va antes de la tabla. */
    public void filtro(String nombre, String valor) {
        antesDeLaTabla();
        Row fila = hoja.createRow(siguienteFila++);
        texto(fila.createCell(0), nombre, estilos.negrita);
        texto(fila.createCell(1), valor, estilos.texto);
    }

    /** Un total, en una fila: su nombre y su valor con el formato dado. Va antes de la tabla. */
    public void total(String nombre, BigDecimal valor, Formato formato) {
        antesDeLaTabla();
        Row fila = hoja.createRow(siguienteFila++);
        texto(fila.createCell(0), nombre, estilos.negrita);
        escribir(fila.createCell(1), valor, formato);
    }

    /** La fila de encabezados de la tabla, con una fila vacía antes para separarla de los totales. */
    public void encabezados(String... columnas) {
        if (encabezadosEscritos || tablaIniciada) {
            throw new IllegalStateException("Los encabezados van una sola vez y antes de las filas.");
        }
        tablaIniciada = true;
        encabezadosEscritos = true;
        siguienteFila++;
        Row fila = hoja.createRow(siguienteFila++);
        for (int i = 0; i < columnas.length; i++) {
            texto(fila.createCell(i), columnas[i], estilos.encabezado);
            if (i > 1) {
                hoja.setColumnWidth(i, Math.max(16, columnas[i].length() + 4) * 256);
            }
        }
        hoja.createFreezePane(0, siguienteFila);
    }

    /**
     * Una fila de la tabla. El tipo de cada celda decide su formato: un texto es texto, un {@link Number}
     * son unidades, una {@link LocalDate} una fecha, {@code null} una celda vacía. Para pesos o para otro
     * formato, se envuelve con {@link #celda(Object, Formato)}.
     */
    public void fila(Object... celdas) {
        tablaIniciada = true;
        Row fila = hoja.createRow(siguienteFila++);
        for (int i = 0; i < celdas.length; i++) {
            Object valor = celdas[i];
            if (valor instanceof Celda c) {
                escribir(fila.createCell(i), c.valor(), c.formato());
            } else {
                escribir(fila.createCell(i), valor, null);
            }
        }
    }

    /** Un valor con el formato que debe llevar en {@link #fila}: {@code celda(precio, Formato.PESOS)}. */
    public static Celda celda(Object valor, Formato formato) {
        return new Celda(valor, formato);
    }

    /**
     * Marca el libro como no entregable: {@link #close()} libera lo que tenga pero no escribe nada en la
     * salida. Para cuando el llenado falló a medias y la excepción va a salir; ver la nota de la clase.
     */
    public void descartar() {
        descartado = true;
    }

    /** Escribe el libro en la salida y libera el archivo temporal. No cierra la salida. */
    @Override
    public void close() throws IOException {
        if (cerrado) {
            return;
        }
        cerrado = true;
        try {
            if (!descartado) {
                libro.write(salida);
            }
        } finally {
            // El archivo temporal en que SXSSF vuelca las filas no se borra solo.
            libro.dispose();
            libro.close();
        }
    }

    /**
     * El nombre con que se descarga: {@code informe-<informe>-<hoy>.xlsx} sin período, o
     * {@code informe-<informe>-<desde>-<hasta>.xlsx} con él. Solo ASCII, en minúsculas y con guiones:
     * va entre comillas en {@code Content-Disposition} y nada de lo que reciba puede romper la cabecera.
     */
    public static String nombreDeArchivo(String informe, Periodo periodoONull, LocalDate hoy) {
        String fechas = periodoONull == null ? hoy.toString() : periodoONull.desde() + "-" + periodoONull.hasta();
        return "informe-" + enMinusculasConGuiones(informe) + "-" + fechas + ".xlsx";
    }

    // ------------------------------------------------------------------------------------ por dentro

    private void antesDeLaTabla() {
        if (tablaIniciada) {
            throw new IllegalStateException("Los filtros y los totales van antes de la tabla.");
        }
    }

    private void escribir(Cell celda, Object valor, Formato formato) {
        if (valor == null) {
            celda.setBlank();
            celda.setCellStyle(estilos.texto);
        } else if (formato == Formato.TEXTO || formato == null && !esNumeroOFecha(valor)) {
            texto(celda, valor instanceof Boolean b ? (b ? "Sí" : "No") : String.valueOf(valor), estilos.texto);
        } else if (valor instanceof LocalDateTime momento) {
            celda.setCellValue(DateUtil.getExcelDate(momento));
            celda.setCellStyle(estilos.fechaHora);
        } else if (valor instanceof LocalDate fecha) {
            celda.setCellValue(DateUtil.getExcelDate(fecha));
            celda.setCellStyle(estilos.fecha);
        } else if (valor instanceof Number numero) {
            celda.setCellValue(numero.doubleValue());
            celda.setCellStyle(formato == Formato.PESOS ? estilos.pesos : estilos.unidades);
        } else {
            texto(celda, String.valueOf(valor), estilos.texto);
        }
    }

    private static boolean esNumeroOFecha(Object valor) {
        return valor instanceof Number || valor instanceof LocalDate || valor instanceof LocalDateTime;
    }

    /** Un texto como texto: nunca como fórmula, y sin un apóstrofo escrito delante. */
    private static void texto(Cell celda, String valor, CellStyle estilo) {
        celda.setCellValue(valor == null ? "" : valor);
        celda.setCellStyle(estilo);
    }

    private static String nombreDeHoja(String titulo) {
        String limpio = (titulo == null ? "" : titulo).replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (limpio.length() > LARGO_DEL_NOMBRE_DE_HOJA) {
            limpio = limpio.substring(0, LARGO_DEL_NOMBRE_DE_HOJA).trim();
        }
        // Tampoco puede empezar ni terminar con apóstrofo.
        limpio = limpio.replaceAll("^'+|'+$", "");
        return limpio.isEmpty() ? "Informe" : limpio;
    }

    /** A minúsculas ASCII separadas por un guion; sin ninguna letra ni dígito deja {@code informe}. */
    private static String enMinusculasConGuiones(String nombre) {
        String sinTildes = Normalizer.normalize(nombre == null ? "" : nombre, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String limpio = sinTildes.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return limpio.isEmpty() ? "informe" : limpio;
    }

    /**
     * Los estilos del libro, creados una sola vez: un estilo por celda agotaría el límite del formato
     * (64 000) y cada uno pesa. Todas las celdas del mismo tipo comparten el suyo.
     */
    private static final class Estilos {
        final CellStyle titulo;
        final CellStyle negrita;
        final CellStyle encabezado;
        final CellStyle texto;
        final CellStyle pesos;
        final CellStyle unidades;
        final CellStyle fecha;
        final CellStyle fechaHora;

        Estilos(SXSSFWorkbook libro) {
            Font grande = libro.createFont();
            grande.setBold(true);
            grande.setFontHeightInPoints((short) 14);
            titulo = libro.createCellStyle();
            titulo.setFont(grande);
            titulo.setQuotePrefixed(true);

            Font fuenteNegrita = libro.createFont();
            fuenteNegrita.setBold(true);
            negrita = libro.createCellStyle();
            negrita.setFont(fuenteNegrita);
            negrita.setQuotePrefixed(true);

            encabezado = libro.createCellStyle();
            encabezado.setFont(fuenteNegrita);
            encabezado.setQuotePrefixed(true);

            texto = libro.createCellStyle();
            texto.setQuotePrefixed(true);

            pesos = libro.createCellStyle();
            pesos.setDataFormat(libro.createDataFormat().getFormat(FORMATO_PESOS));
            unidades = libro.createCellStyle();
            unidades.setDataFormat(libro.createDataFormat().getFormat(FORMATO_UNIDADES));
            fecha = libro.createCellStyle();
            fecha.setDataFormat(libro.createDataFormat().getFormat(FORMATO_FECHA));
            fechaHora = libro.createCellStyle();
            fechaHora.setDataFormat(libro.createDataFormat().getFormat(FORMATO_FECHA_HORA));
        }
    }
}
