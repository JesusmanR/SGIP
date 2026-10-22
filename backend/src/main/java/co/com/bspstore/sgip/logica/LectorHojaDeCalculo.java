package co.com.bspstore.sgip.logica;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lee listas de precios en hoja de cálculo: .xlsx y .csv (RF-24).
 *
 * Es la única clase del backend que importa Apache POI. Esa acotación es
 * la razón de ser de {@link LectorListaPrecios} (ADR-0007).
 *
 * ## Cómo encuentra las columnas
 *
 * El RF-25 —configurar por proveedor la correspondencia entre columnas
 * de origen y campos del sistema— es de la versión 1.1, de modo que
 * aquí el encabezado se reconoce por su texto, entre un puñado de
 * nombres habituales. No es adivinar: es la lista explícita de abajo, y
 * un encabezado que no cae en ella detiene la carga con su causa en vez
 * de leer la columna equivocada. Cuando exista `mapeos_proveedor` en uso,
 * esa tabla manda y esto queda como respaldo.
 *
 * El cotejo ignora mayúsculas, tildes y espacios de sobra, porque las
 * tres cosas cambian entre archivos del mismo proveedor sin que nadie lo
 * decida.
 */
@Component
public class LectorHojaDeCalculo implements LectorListaPrecios {

    /** Nombres de encabezado admitidos para cada campo. */
    private static final List<String> REFERENCIA =
            List.of("codigo", "referencia", "sku", "ref", "codigo producto", "item");
    private static final List<String> COSTO =
            List.of("costo", "precio", "valor", "costo unitario", "precio unitario",
                    "costo neto", "precio neto");
    private static final List<String> DESCRIPCION =
            List.of("descripcion", "nombre", "detalle", "producto");

    /**
     * Cuántas filas se miran buscando el encabezado.
     *
     * Las hojas de proveedor suelen traer el logotipo y un par de líneas
     * de texto antes de la tabla. Diez es holgado y acota el daño de un
     * archivo que no tiene encabezado en ninguna parte.
     */
    private static final int FILAS_PARA_BUSCAR_ENCABEZADO = 10;

    private final DataFormatter formateador = new DataFormatter(Locale.of("es", "CO"));

    @Override
    public boolean entiende(String nombreArchivo) {
        String n = nombreArchivo == null ? "" : nombreArchivo.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".csv");
    }

    @Override
    public List<FilaLeida> leer(InputStream entrada, String nombreArchivo, int maximoFilas)
            throws IOException {
        if (!entiende(nombreArchivo)) {
            throw new ArchivoInvalidoException(
                    "Solo se admiten archivos .xlsx y .csv, y llegó «" + nombreArchivo + "».");
        }
        return nombreArchivo.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? leerSeparadoPorComas(entrada, maximoFilas)
                : leerLibro(entrada, maximoFilas);
    }


    // ---------------------------------------------------------------- .xlsx

    private List<FilaLeida> leerLibro(InputStream entrada, int maximoFilas) throws IOException {
        // Abrir va aparte del resto a propósito. Ante algo que no es un
        // libro, POI lanza IOException o una de sus excepciones no
        // comprobadas según por dónde se dé cuenta, y las dos significan
        // lo mismo para quien subió el archivo. Fuera de esta llamada un
        // IOException sí es un fallo de lectura de verdad y debe subir
        // como tal.
        Workbook libro;
        try {
            libro = WorkbookFactory.create(entrada);
        } catch (IOException | RuntimeException e) {
            throw new ArchivoInvalidoException(
                    "El archivo no se pudo abrir como hoja de cálculo. "
                            + "¿Está completo y es realmente un .xlsx?");
        }
        try (libro) {
            if (libro.getNumberOfSheets() == 0) {
                throw new ArchivoInvalidoException("El archivo no tiene ninguna hoja.");
            }
            Sheet hoja = libro.getSheetAt(0);

            int filaEncabezado = -1;
            Columnas columnas = null;
            int tope = Math.min(hoja.getLastRowNum(), FILAS_PARA_BUSCAR_ENCABEZADO - 1);
            for (int i = hoja.getFirstRowNum(); i <= tope; i++) {
                Row fila = hoja.getRow(i);
                if (fila == null) {
                    continue;
                }
                List<String> textos = new ArrayList<>();
                for (int c = 0; c < fila.getLastCellNum(); c++) {
                    textos.add(textoDe(fila.getCell(c)));
                }
                Columnas halladas = Columnas.desde(textos);
                if (halladas != null) {
                    filaEncabezado = i;
                    columnas = halladas;
                    break;
                }
            }
            if (columnas == null) {
                throw new ArchivoInvalidoException(encabezadoNoEncontrado());
            }

            List<FilaLeida> filas = new ArrayList<>();
            for (int i = filaEncabezado + 1; i <= hoja.getLastRowNum(); i++) {
                Row fila = hoja.getRow(i);
                if (fila == null) {
                    continue;
                }
                String referencia = textoDe(fila.getCell(columnas.referencia()));
                String costo = textoDe(fila.getCell(columnas.costo()));
                String descripcion = columnas.descripcion() < 0
                        ? null : textoDe(fila.getCell(columnas.descripcion()));
                // Una fila del todo vacía no es un rechazo: es el relleno
                // que deja una hoja al borrar contenido sin borrar filas.
                if (referencia.isBlank() && costo.isBlank()) {
                    continue;
                }
                exigirTope(filas.size(), maximoFilas);
                filas.add(new FilaLeida(i + 1, referencia, vacioANulo(descripcion), costo));
            }
            return filas;
        } catch (ArchivoInvalidoException e) {
            throw e;
        } catch (RuntimeException e) {
            // Ya abierto, POI sigue pudiendo reventar al recorrer una
            // hoja con partes corruptas. Para quien subió el archivo es
            // lo mismo: el archivo no sirve.
            throw new ArchivoInvalidoException(
                    "El archivo se abrió pero no se pudo recorrer entero. "
                            + "Vuelve a exportarlo desde la hoja de cálculo.");
        }
    }


    // ---------------------------------------------------------------- .csv

    private List<FilaLeida> leerSeparadoPorComas(InputStream entrada, int maximoFilas)
            throws IOException {
        List<FilaLeida> filas = new ArrayList<>();
        Columnas columnas = null;
        int numero = 0;
        char separador = 0;

        try (BufferedReader lector = new BufferedReader(
                new InputStreamReader(entrada, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = lector.readLine()) != null) {
                numero++;
                if (numero == 1) {
                    // El indicador de orden de bytes que escribe Excel al
                    // guardar como CSV se pega al primer encabezado y lo
                    // haría irreconocible.
                    linea = linea.replace("﻿", "");
                }
                if (separador == 0 && !linea.isBlank()) {
                    separador = separadorDe(linea);
                }
                List<String> celdas = partir(linea, separador == 0 ? ',' : separador);
                if (columnas == null) {
                    if (numero > FILAS_PARA_BUSCAR_ENCABEZADO) {
                        throw new ArchivoInvalidoException(encabezadoNoEncontrado());
                    }
                    columnas = Columnas.desde(celdas);
                    continue;
                }
                String referencia = enPosicion(celdas, columnas.referencia());
                String costo = enPosicion(celdas, columnas.costo());
                String descripcion = enPosicion(celdas, columnas.descripcion());
                if (referencia.isBlank() && costo.isBlank()) {
                    continue;
                }
                exigirTope(filas.size(), maximoFilas);
                filas.add(new FilaLeida(numero, referencia, vacioANulo(descripcion), costo));
            }
        }
        if (columnas == null) {
            throw new ArchivoInvalidoException(encabezadoNoEncontrado());
        }
        return filas;
    }

    /**
     * Con qué carácter está separado este archivo.
     *
     * No se puede dar por hecho que sea la coma. Un Excel configurado en
     * español guarda el CSV con punto y coma, porque la coma es el
     * separador decimal: en esos archivos el costo viene como «1.850,00».
     * Partir por los dos caracteres a la vez —que es lo que hacía la
     * primera versión de esto— rompía ese costo en dos celdas y corría
     * todas las columnas de ahí en adelante, de modo que el costo se
     * leía de donde no era.
     *
     * Se decide una sola vez, con la primera línea con contenido, y se
     * respeta en todo el archivo: mezclar separadores dentro de un mismo
     * archivo no es algo que produzca ninguna hoja de cálculo.
     */
    private static char separadorDe(String linea) {
        int comas = 0;
        int puntoYComas = 0;
        boolean entreComillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (c == '"') {
                entreComillas = !entreComillas;
            } else if (!entreComillas && c == ',') {
                comas++;
            } else if (!entreComillas && c == ';') {
                puntoYComas++;
            }
        }
        return puntoYComas > comas ? ';' : ',';
    }

    /**
     * Parte una línea por su separador respetando las comillas dobles.
     *
     * Una descripción con coma dentro —«Tornillo 1/4", galvanizado»— es
     * lo corriente en una lista de ferretería, y partir a secas la
     * rompería en dos celdas y correría todas las demás, de modo que el
     * costo se leería de la columna equivocada. Dos comillas seguidas
     * dentro de un campo entrecomillado son una comilla literal, que es
     * como lo escribe cualquier hoja de cálculo.
     */
    private static List<String> partir(String linea, char separador) {
        List<String> celdas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean entreComillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (entreComillas) {
                if (c == '"') {
                    if (i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
                        actual.append('"');
                        i++;
                    } else {
                        entreComillas = false;
                    }
                } else {
                    actual.append(c);
                }
            } else if (c == '"') {
                entreComillas = true;
            } else if (c == separador) {
                celdas.add(actual.toString().trim());
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        celdas.add(actual.toString().trim());
        return celdas;
    }


    // ---------------------------------------------------------------- comunes

    private void exigirTope(int yaLeidas, int maximoFilas) {
        if (yaLeidas >= maximoFilas) {
            throw new ArchivoInvalidoException(
                    "El archivo supera las " + maximoFilas + " filas admitidas. "
                            + "Divídelo en varias listas.");
        }
    }

    private String textoDe(Cell celda) {
        return celda == null ? "" : formateador.formatCellValue(celda).trim();
    }

    private static String enPosicion(List<String> celdas, int indice) {
        return indice >= 0 && indice < celdas.size() ? celdas.get(indice) : "";
    }

    private static String vacioANulo(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String encabezadoNoEncontrado() {
        return "No se encontró el encabezado. Hacen falta una columna de referencia ("
                + String.join(", ", REFERENCIA) + ") y una de costo ("
                + String.join(", ", COSTO) + "), en alguna de las primeras "
                + FILAS_PARA_BUSCAR_ENCABEZADO + " filas.";
    }

    /** En qué posición quedó cada campo dentro del encabezado. */
    private record Columnas(int referencia, int costo, int descripcion) {

        /** Null cuando esa fila no es un encabezado utilizable. */
        static Columnas desde(List<String> textos) {
            int ref = -1;
            int cos = -1;
            int des = -1;
            for (int i = 0; i < textos.size(); i++) {
                String t = normalizar(textos.get(i));
                if (ref < 0 && REFERENCIA.contains(t)) {
                    ref = i;
                } else if (cos < 0 && COSTO.contains(t)) {
                    cos = i;
                } else if (des < 0 && DESCRIPCION.contains(t)) {
                    des = i;
                }
            }
            return (ref >= 0 && cos >= 0) ? new Columnas(ref, cos, des) : null;
        }

        private static String normalizar(String s) {
            if (s == null) {
                return "";
            }
            String t = s.trim().toLowerCase(Locale.ROOT);
            t = t.replace('á', 'a').replace('é', 'e').replace('í', 'i')
                 .replace('ó', 'o').replace('ú', 'u').replace('ñ', 'n');
            return t.replaceAll("\\s+", " ");
        }
    }
}
