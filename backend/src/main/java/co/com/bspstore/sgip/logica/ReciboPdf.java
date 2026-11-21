package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.api.dto.Recibo.LineaDeRecibo;
import co.com.bspstore.sgip.api.dto.Recibo.PagoDeRecibo;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * El recibo en PDF (RF-70, ADR-0035): páginas de 80 mm de ancho, como la tirilla de la impresora, con el alto que
 * pide el contenido. Casi siempre es una sola página; una venta tan larga que pasaría el máximo de una página PDF
 * ({@value #ALTO_MAXIMO} pt) sigue en otra del mismo ancho. Es la única clase que conoce PDFBox.
 *
 * La plantilla es sobria y está toda aquí, en {@link #componer}: el usuario diseñará el recibo después y basta
 * con cambiar ese método (el orden, los rótulos) o las constantes de tamaño. El resto, el ajuste de líneas, el
 * alto y el dibujo, no depende de qué se escribe.
 *
 * Se compone en dos pasadas: primero se colocan los textos en una {@link Hoja}, que va sumando el alto, y luego
 * se crean las páginas con ese alto y se dibuja.
 *
 * <h2>La fuente</h2>
 *
 * Liberation Sans Regular, que el propio jar de PDFBox trae ({@value #FUENTE}), incrustada en cada documento y
 * subconjunta: no se descarga nada, no se instala nada y no se usa el mapeador de fuentes de PDFBox, que recorre las
 * fuentes del sistema y escribe una caché en la carpeta personal. Por eso esta clase no toca ninguna fuente al
 * construirse ni al cargarse. Cada recibo carga la suya (un {@code PDType0Font} guarda mapas que se llenan al usarlo
 * y PDFBox no es seguro entre hilos).
 *
 * La negrita es falsa: la misma fuente con trazo (modo {@code FILL_STROKE}) de {@value #TRAZO_DE_NEGRITA} veces el
 * tamaño. Se prefirió a usar la fuente normal un poco más grande porque el trazo conserva el ancho de las letras,
 * que es lo que mantiene los importes alineados a la derecha y evita que un título ocupe otro renglón.
 *
 * <b>Todo texto pasa por {@link #limpiar}.</b> PDFBox lanza una excepción con cualquier carácter que la fuente
 * no tenga, y los nombres de producto los teclea el usuario: un emoji, un alfabeto no latino o un espacio de ancho
 * especial no puede tumbar el recibo, así que lo que la fuente no tiene sale como «?».
 */
@Component
public class ReciboPdf {

    /** 80 mm en puntos tipográficos (72 por pulgada). */
    static final float ANCHO = 80f / 25.4f * 72f;

    /** El alto máximo de una página PDF que los visores aceptan. */
    static final float ALTO_MAXIMO = 14400f;

    /** La fuente que trae el jar de pdfbox 3.0.x. */
    static final String FUENTE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    static final float TRAZO_DE_NEGRITA = 0.035f;

    private static final float MARGEN = 10f;
    private static final float TAMANO = 8.5f;
    private static final float TAMANO_PEQUENO = 7.5f;
    private static final float TAMANO_TITULO = 11f;
    private static final float INTERLINEADO = 1.25f;
    private static final float SEPARACION = 4f;

    private static final DateTimeFormatter FECHA_Y_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private enum Estilo { NORMAL, NEGRITA }

    public byte[] generar(Recibo recibo) {
        try (PDDocument documento = new PDDocument()) {
            PDFont fuente = cargarFuente(documento);
            Hoja hoja = new Hoja(fuente);
            componer(hoja, recibo);
            documento.getDocumentInformation().setTitle(limpiar("Recibo " + recibo.consecutivo(), fuente));
            hoja.dibujar(documento);
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            documento.save(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF del recibo.", e);
        }
    }

    private static PDFont cargarFuente(PDDocument documento) throws IOException {
        try (InputStream flujo = PDDocument.class.getResourceAsStream(FUENTE)) {
            if (flujo == null) {
                throw new IllegalStateException("El jar de PDFBox no trae la fuente " + FUENTE);
            }
            return PDType0Font.load(documento, flujo, true);
        }
    }

    // ------------------------------------------------------------ la plantilla

    private static void componer(Hoja h, Recibo r) {
        h.centrado(r.empresa(), Estilo.NEGRITA, TAMANO_TITULO);
        if (r.nitEmpresa() != null) {
            h.centrado("NIT " + r.nitEmpresa(), Estilo.NORMAL, TAMANO);
        }
        h.espacio();

        h.izquierda("Venta " + r.consecutivo(), Estilo.NORMAL, TAMANO);
        h.izquierda("Fecha: " + r.fecha().format(FECHA_Y_HORA), Estilo.NORMAL, TAMANO);
        h.izquierda("Cajero: " + r.cajero(), Estilo.NORMAL, TAMANO);
        h.izquierda("Cliente: " + r.cliente() + (r.identificacionCliente() == null ? "" : " (" + r.identificacionCliente() + ")"),
                Estilo.NORMAL, TAMANO);
        h.raya();

        for (LineaDeRecibo l : r.lineas()) {
            h.fila(l.nombre(), pesos(l.total()), Estilo.NORMAL, TAMANO);
            StringBuilder detalle = new StringBuilder(l.cantidad() + " × " + pesos(l.precioUnitario()));
            if (l.descuento() != null && l.descuento().signum() > 0) {
                detalle.append(" · descuento ").append(pesos(l.descuento()));
            }
            if (l.iva() != null && l.iva().signum() > 0) {
                detalle.append(" · IVA ").append(pesos(l.iva()));
            }
            h.izquierda(detalle.toString(), Estilo.NORMAL, TAMANO_PEQUENO);
            h.hueco(SEPARACION / 2);
        }
        h.raya();

        h.fila("Subtotal", pesos(r.subtotal()), Estilo.NORMAL, TAMANO);
        if (r.descuento() != null && r.descuento().signum() > 0) {
            h.fila("Descuento", pesos(r.descuento()), Estilo.NORMAL, TAMANO);
        }
        if (r.iva() != null) {
            h.fila("IVA", pesos(r.iva()), Estilo.NORMAL, TAMANO);
        }
        h.fila("Total", pesos(r.total()), Estilo.NEGRITA, TAMANO_TITULO);
        h.raya();

        for (PagoDeRecibo p : r.pagos()) {
            String medio = p.tipo() == TipoMedio.SALDO_A_FAVOR ? "Saldo a favor" : p.medio();
            h.fila(medio, pesos(p.monto()), Estilo.NORMAL, TAMANO);
            if (p.recibido() != null && p.recibido().compareTo(p.monto()) != 0) {
                h.fila("Recibido", pesos(p.recibido()), Estilo.NORMAL, TAMANO_PEQUENO);
            }
            if (p.referencia() != null && !p.referencia().isBlank()) {
                h.izquierda("Ref. " + p.referencia(), Estilo.NORMAL, TAMANO_PEQUENO);
            }
        }
        if (r.cambio() != null && r.cambio().signum() > 0) {
            h.fila("Cambio", pesos(r.cambio()), Estilo.NEGRITA, TAMANO);
        }
        if (!r.pagos().isEmpty() || (r.cambio() != null && r.cambio().signum() > 0)) {
            h.raya();
        }

        for (String marca : r.marcas()) {
            h.izquierda(marca, Estilo.NEGRITA, TAMANO);
        }
        if (!r.marcas().isEmpty()) {
            h.espacio();
        }
        h.centrado(Recibo.PIE, Estilo.NORMAL, TAMANO_PEQUENO);
    }

    // ------------------------------------------------------------ los importes

    /** Pesos colombianos con punto de miles; los centavos solo si los hay. */
    static String pesos(BigDecimal valor) {
        if (valor == null) {
            return "";
        }
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        boolean entero = valor.stripTrailingZeros().scale() <= 0;
        return new DecimalFormat(entero ? "$#,##0" : "$#,##0.00", simbolos).format(valor);
    }

    // ------------------------------------------------------------ los caracteres

    /**
     * El texto tal como la fuente puede escribirlo: los espacios raros y los controles (tabulación, salto de
     * línea) pasan a un espacio, y cada carácter que la fuente no tiene (un emoji, un alfabeto no latino) pasa a
     * «?». Nunca lanza por el contenido.
     */
    static String limpiar(String texto, PDFont fuente) {
        if (texto == null) {
            return "";
        }
        String compuesto = Normalizer.normalize(texto, Normalizer.Form.NFC);
        StringBuilder limpio = new StringBuilder(compuesto.length());
        compuesto.codePoints().forEach(punto -> {
            if (Character.isSpaceChar(punto) || Character.isWhitespace(punto) || Character.isISOControl(punto)) {
                limpio.append(' ');
                return;
            }
            String letra = new String(Character.toChars(punto));
            try {
                fuente.encode(letra);
                limpio.append(letra);
            } catch (IOException | IllegalArgumentException noSePuedeEscribir) {
                limpio.append('?');
            }
        });
        return limpio.toString().strip();
    }

    private static float ancho(String texto, PDFont fuente, float tamano) {
        try {
            return fuente.getStringWidth(texto) / 1000f * tamano;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parte el texto en renglones que caben en {@code maximo} puntos; una palabra más ancha se corta. */
    static List<String> renglones(String texto, PDFont fuente, float tamano, float maximo) {
        List<String> renglones = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.split(" ")) {
            if (palabra.isEmpty()) {
                continue;
            }
            String candidato = actual.isEmpty() ? palabra : actual + " " + palabra;
            if (ancho(candidato, fuente, tamano) <= maximo) {
                actual = new StringBuilder(candidato);
                continue;
            }
            if (!actual.isEmpty()) {
                renglones.add(actual.toString());
                actual = new StringBuilder();
            }
            // una palabra sola que no cabe se corta por letras
            StringBuilder trozo = new StringBuilder();
            for (int i = 0; i < palabra.length(); i++) {
                char letra = palabra.charAt(i);
                if (!trozo.isEmpty() && ancho(trozo.toString() + letra, fuente, tamano) > maximo) {
                    renglones.add(trozo.toString());
                    trozo = new StringBuilder();
                }
                trozo.append(letra);
            }
            actual = trozo;
        }
        if (!actual.isEmpty() || renglones.isEmpty()) {
            renglones.add(actual.toString());
        }
        return renglones;
    }

    // ------------------------------------------------------------ la hoja

    /** Lo ya colocado y el alto de cada página; se dibuja cuando se conoce el alto total. */
    private static final class Hoja {

        private record Texto(String texto, boolean negrita, float tamano, float x, float arriba, int pagina) {}

        private record Raya(float arriba, int pagina) {}

        private static final float UTIL = ANCHO - 2 * MARGEN;
        private static final float HOLGURA = 3 * SEPARACION;

        private final PDFont fuente;
        private final List<Object> piezas = new ArrayList<>();
        private final List<Float> altosCerrados = new ArrayList<>();
        private float y = MARGEN;

        Hoja(PDFont fuente) {
            this.fuente = fuente;
        }

        private int pagina() {
            return altosCerrados.size();
        }

        /** Las páginas que quedan: las que se cerraron por llenarse y la actual con el alto que lleva. */
        private List<Float> altos() {
            List<Float> altos = new ArrayList<>(altosCerrados);
            altos.add(y + MARGEN);
            return altos;
        }

        /**
         * Si lo que sigue no cabe en la página actual, la cierra y sigue en otra. Deja de holgura lo que pueden
         * sumar los huecos entre renglones, que no se comprueban uno a uno.
         */
        private void asegurar(float altura) {
            if (y + altura + MARGEN + HOLGURA > ALTO_MAXIMO && y > MARGEN) {
                altosCerrados.add(y + MARGEN);
                y = MARGEN;
            }
        }

        void espacio() {
            hueco(SEPARACION * 2);
        }

        void hueco(float puntos) {
            y += puntos;
        }

        void raya() {
            asegurar(SEPARACION * 2);
            y += SEPARACION / 2;
            piezas.add(new Raya(y, pagina()));
            y += SEPARACION;
        }

        void izquierda(String texto, Estilo estilo, float tamano) {
            for (String renglon : renglones(limpiar(texto, fuente), fuente, tamano, UTIL)) {
                colocar(renglon, estilo, tamano, MARGEN);
            }
        }

        void centrado(String texto, Estilo estilo, float tamano) {
            for (String renglon : renglones(limpiar(texto, fuente), fuente, tamano, UTIL)) {
                colocar(renglon, estilo, tamano, MARGEN + (UTIL - ancho(renglon, fuente, tamano)) / 2);
            }
        }

        /** Un rótulo a la izquierda y su importe pegado a la derecha, en el primer renglón del rótulo. */
        void fila(String rotulo, String importe, Estilo estilo, float tamano) {
            String valor = limpiar(importe, fuente);
            float anchoDelValor = ancho(valor, fuente, tamano);
            List<String> partes = renglones(limpiar(rotulo, fuente), fuente, tamano,
                    UTIL - anchoDelValor - SEPARACION * 1.5f);
            for (int i = 0; i < partes.size(); i++) {
                asegurar(tamano * INTERLINEADO);
                if (i == 0) {
                    piezas.add(new Texto(valor, estilo == Estilo.NEGRITA, tamano, ANCHO - MARGEN - anchoDelValor, y,
                            pagina()));
                }
                colocar(partes.get(i), estilo, tamano, MARGEN);
            }
        }

        private void colocar(String renglon, Estilo estilo, float tamano, float x) {
            asegurar(tamano * INTERLINEADO);
            piezas.add(new Texto(renglon, estilo == Estilo.NEGRITA, tamano, x, y, pagina()));
            y += tamano * INTERLINEADO;
        }

        /** Crea las páginas con su alto y dibuja en cada una lo que le tocó. */
        void dibujar(PDDocument documento) throws IOException {
            List<Float> altos = altos();
            for (int numero = 0; numero < altos.size(); numero++) {
                float alto = altos.get(numero);
                PDPage pagina = new PDPage(new PDRectangle(ANCHO, alto));
                documento.addPage(pagina);
                try (PDPageContentStream contenido = new PDPageContentStream(documento, pagina)) {
                    for (Object pieza : piezas) {
                        if (pieza instanceof Texto t && t.pagina() == numero) {
                            dibujarTexto(contenido, t, alto);
                        } else if (pieza instanceof Raya r && r.pagina() == numero) {
                            contenido.setLineWidth(0.5f);
                            contenido.setLineDashPattern(new float[]{2f, 2f}, 0);
                            contenido.moveTo(MARGEN, alto - r.arriba());
                            contenido.lineTo(ANCHO - MARGEN, alto - r.arriba());
                            contenido.stroke();
                            contenido.setLineDashPattern(new float[0], 0);
                        }
                    }
                }
            }
        }

        private void dibujarTexto(PDPageContentStream contenido, Texto t, float alto) throws IOException {
            if (t.negrita()) {
                contenido.setRenderingMode(RenderingMode.FILL_STROKE);
                contenido.setLineWidth(t.tamano() * TRAZO_DE_NEGRITA);
            }
            contenido.beginText();
            contenido.setFont(fuente, t.tamano());
            contenido.newLineAtOffset(t.x(), alto - t.arriba() - t.tamano());
            contenido.showText(t.texto());
            contenido.endText();
            if (t.negrita()) {
                contenido.setRenderingMode(RenderingMode.FILL);
            }
        }
    }
}
