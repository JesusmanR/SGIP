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
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * El recibo en PDF (RF-70, ADR-0035): una sola página de 80 mm de ancho, como la tirilla de la impresora, cuyo
 * alto sale del contenido. Es la única clase que conoce PDFBox.
 *
 * La plantilla es sobria y está toda aquí, en {@link #componer}: el usuario diseñará el recibo después y basta
 * con cambiar ese método (el orden, los rótulos) o las constantes de tamaño. El resto, el ajuste de líneas, el
 * alto y el dibujo, no depende de qué se escribe.
 *
 * Se compone en dos pasadas: primero se colocan los textos en una {@link Hoja}, que va sumando el alto, y luego
 * se crea la página con ese alto y se dibuja. Las fuentes son las estándar del PDF (Helvetica, WinAnsi): cubren
 * las tildes, la eñe y los signos del español, y no hay que incrustar nada.
 *
 * <b>Todo texto pasa por {@link #limpiar}.</b> PDFBox lanza una excepción con cualquier carácter que la fuente
 * no pueda escribir, y los nombres de producto los teclea el usuario: un emoji o un espacio de ancho especial no
 * puede tumbar el recibo, así que lo que la fuente no escribe sale como «?».
 */
public class ReciboPdf {

    /** 80 mm en puntos tipográficos (72 por pulgada). */
    static final float ANCHO = 80f / 25.4f * 72f;

    private static final float MARGEN = 10f;
    private static final float TAMANO = 8.5f;
    private static final float TAMANO_PEQUENO = 7.5f;
    private static final float TAMANO_TITULO = 11f;
    private static final float INTERLINEADO = 1.25f;
    private static final float SEPARACION = 4f;

    /**
     * Las fuentes NO son estáticas: un {@code PDType1Font} guarda mapas que se llenan al usarlo
     * ({@code codeToBytesMap}, {@code codeToWidthMap}) y PDFBox no es seguro entre hilos, así que cada recibo crea
     * las suyas. Crear la primera hace que PDFBox recorra las fuentes instaladas del sistema (unos segundos la
     * primera vez en una máquina); este bloque lo paga al cargar la clase, que es al arrancar la aplicación, y no
     * en el primer recibo que alguien pida.
     */
    static {
        new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static final DateTimeFormatter FECHA_Y_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public byte[] generar(Recibo recibo) {
        PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont negrita = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        Hoja hoja = new Hoja();
        componer(hoja, recibo, normal, negrita);
        try (PDDocument documento = new PDDocument()) {
            PDPage pagina = new PDPage(new PDRectangle(ANCHO, hoja.alto()));
            documento.addPage(pagina);
            documento.getDocumentInformation().setTitle(limpiar("Recibo " + recibo.consecutivo(), normal));
            try (PDPageContentStream contenido = new PDPageContentStream(documento, pagina)) {
                hoja.dibujar(contenido, pagina.getMediaBox().getHeight());
            }
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            documento.save(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF del recibo.", e);
        }
    }

    // ------------------------------------------------------------ la plantilla

    private static void componer(Hoja h, Recibo r, PDFont normal, PDFont negrita) {
        h.centrado(r.empresa(), negrita, TAMANO_TITULO);
        if (r.nitEmpresa() != null) {
            h.centrado("NIT " + r.nitEmpresa(), normal, TAMANO);
        }
        h.espacio();

        h.izquierda("Venta " + r.consecutivo(), normal, TAMANO);
        h.izquierda("Fecha: " + r.fecha().format(FECHA_Y_HORA), normal, TAMANO);
        h.izquierda("Cajero: " + r.cajero(), normal, TAMANO);
        h.izquierda("Cliente: " + r.cliente() + (r.identificacionCliente() == null ? "" : " (" + r.identificacionCliente() + ")"),
                normal, TAMANO);
        h.raya();

        for (LineaDeRecibo l : r.lineas()) {
            h.fila(l.nombre(), pesos(l.total()), normal, TAMANO);
            StringBuilder detalle = new StringBuilder(l.cantidad() + " × " + pesos(l.precioUnitario()));
            if (l.descuento() != null && l.descuento().signum() > 0) {
                detalle.append(" · descuento ").append(pesos(l.descuento()));
            }
            if (l.iva() != null && l.iva().signum() > 0) {
                detalle.append(" · IVA ").append(pesos(l.iva()));
            }
            h.izquierda(detalle.toString(), normal, TAMANO_PEQUENO);
            h.hueco(SEPARACION / 2);
        }
        h.raya();

        h.fila("Subtotal", pesos(r.subtotal()), normal, TAMANO);
        if (r.descuento() != null && r.descuento().signum() > 0) {
            h.fila("Descuento", pesos(r.descuento()), normal, TAMANO);
        }
        if (r.iva() != null) {
            h.fila("IVA", pesos(r.iva()), normal, TAMANO);
        }
        h.fila("Total", pesos(r.total()), negrita, TAMANO_TITULO);
        h.raya();

        for (PagoDeRecibo p : r.pagos()) {
            String medio = p.tipo() == TipoMedio.SALDO_A_FAVOR ? "Saldo a favor" : p.medio();
            h.fila(medio, pesos(p.monto()), normal, TAMANO);
            if (p.recibido() != null && p.recibido().compareTo(p.monto()) != 0) {
                h.fila("Recibido", pesos(p.recibido()), normal, TAMANO_PEQUENO);
            }
            if (p.referencia() != null && !p.referencia().isBlank()) {
                h.izquierda("Ref. " + p.referencia(), normal, TAMANO_PEQUENO);
            }
        }
        if (r.cambio() != null && r.cambio().signum() > 0) {
            h.fila("Cambio", pesos(r.cambio()), negrita, TAMANO);
        }
        if (!r.pagos().isEmpty() || (r.cambio() != null && r.cambio().signum() > 0)) {
            h.raya();
        }

        for (String marca : r.marcas()) {
            h.izquierda(marca, negrita, TAMANO);
        }
        if (!r.marcas().isEmpty()) {
            h.espacio();
        }
        h.centrado(Recibo.PIE, normal, TAMANO_PEQUENO);
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
     * línea) pasan a un espacio, y lo que la fuente no codifica pasa a «?». Nunca lanza por el contenido.
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

    /** Lo ya colocado y el alto que lleva; se dibuja cuando se conoce el alto total. */
    private static final class Hoja {

        private record Texto(String texto, PDFont fuente, float tamano, float x, float arriba) {}

        private record Raya(float arriba) {}

        private static final float UTIL = ANCHO - 2 * MARGEN;

        private final List<Object> piezas = new ArrayList<>();
        private float y = MARGEN;

        float alto() {
            return y + MARGEN;
        }

        void espacio() {
            hueco(SEPARACION * 2);
        }

        void hueco(float puntos) {
            y += puntos;
        }

        void raya() {
            y += SEPARACION / 2;
            piezas.add(new Raya(y));
            y += SEPARACION;
        }

        void izquierda(String texto, PDFont fuente, float tamano) {
            for (String renglon : renglones(limpiar(texto, fuente), fuente, tamano, UTIL)) {
                colocar(renglon, fuente, tamano, MARGEN);
            }
        }

        void centrado(String texto, PDFont fuente, float tamano) {
            for (String renglon : renglones(limpiar(texto, fuente), fuente, tamano, UTIL)) {
                colocar(renglon, fuente, tamano, MARGEN + (UTIL - ancho(renglon, fuente, tamano)) / 2);
            }
        }

        /** Un rótulo a la izquierda y su importe pegado a la derecha, en el primer renglón del rótulo. */
        void fila(String rotulo, String importe, PDFont fuente, float tamano) {
            String valor = limpiar(importe, fuente);
            float anchoDelValor = ancho(valor, fuente, tamano);
            List<String> partes = renglones(limpiar(rotulo, fuente), fuente, tamano,
                    UTIL - anchoDelValor - SEPARACION * 1.5f);
            for (int i = 0; i < partes.size(); i++) {
                if (i == 0) {
                    piezas.add(new Texto(valor, fuente, tamano, ANCHO - MARGEN - anchoDelValor, y));
                }
                colocar(partes.get(i), fuente, tamano, MARGEN);
            }
        }

        private void colocar(String renglon, PDFont fuente, float tamano, float x) {
            piezas.add(new Texto(renglon, fuente, tamano, x, y));
            y += tamano * INTERLINEADO;
        }

        void dibujar(PDPageContentStream contenido, float altoDePagina) throws IOException {
            for (Object pieza : piezas) {
                if (pieza instanceof Texto t) {
                    contenido.beginText();
                    contenido.setFont(t.fuente(), t.tamano());
                    contenido.newLineAtOffset(t.x(), altoDePagina - t.arriba() - t.tamano());
                    contenido.showText(t.texto());
                    contenido.endText();
                } else if (pieza instanceof Raya r) {
                    contenido.setLineWidth(0.5f);
                    contenido.setLineDashPattern(new float[]{2f, 2f}, 0);
                    contenido.moveTo(MARGEN, altoDePagina - r.arriba());
                    contenido.lineTo(ANCHO - MARGEN, altoDePagina - r.arriba());
                    contenido.stroke();
                }
            }
        }
    }
}
