package co.com.bspstore.sgip.logica.venta;

import co.com.bspstore.sgip.logica.DescuentoInvalidoException;
import co.com.bspstore.sgip.logica.EfectivoRecibidoInvalidoException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cuánto se cobra por una venta: descuentos, IVA, total y cambio. Es lógica pura, sin base de datos;
 * el servicio de ventas solo la orquesta dentro de su transacción.
 *
 * <p>Reglas (diseño de la caja):
 * <ul>
 *   <li>El descuento de una línea se aplica antes que el de toda la compra.
 *   <li>Un descuento en porcentaje se redondea a pesos enteros con HALF_UP.
 *   <li>El descuento sobre toda la compra se reparte en proporción al valor de cada línea después de su
 *       propio descuento, al peso con HALF_UP. El residuo del redondeo, positivo o negativo, va a la
 *       línea de mayor valor y, si empatan, a la de menor {@code productoId}; así lo repartido suma
 *       exactamente el descuento total.
 *   <li>El IVA de cada línea es {@code base · tarifa} redondeado a pesos con HALF_UP, sobre la base ya
 *       descontada, y la venta suma los IVA de las líneas. {@code CalculoDePrecio} no sirve aquí:
 *       solo convierte ganancia en precio.
 * </ul>
 *
 * <p>Los importes salen en pesos enteros con escala 2, la de {@code DECIMAL(14,2)} de
 * {@code venta_items}, para guardarlos sin conversión.
 *
 * <p>Entradas inválidas: lo que tiene que ver con un descuento lanza
 * {@link DescuentoInvalidoException} (400) y el efectivo recibido que no sirve,
 * {@link EfectivoRecibidoInvalidoException} (400). Cualquier otro dato fuera de regla (lista vacía,
 * cantidad no positiva, precio o tarifa inválidos, tratamiento desconocido) lanza
 * {@link IllegalArgumentException}: son datos que el servicio de ventas debe haber validado o sacado
 * de la base, así que si llegan aquí es un error de programación y no un 400 para el cliente.
 *
 * <p>Lo que esta clase NO valida y debe validar la frontera (el DTO y el servicio de ventas):
 * <ul>
 *   <li>un tope de cantidad por línea (aquí solo se topa el importe que resulta);
 *   <li>que el producto exista, sea de la empresa y esté activo, y que el precio, la tarifa y el
 *       tratamiento salgan de la vista de precios y no del cliente;
 *   <li>que el precio sea mayor que cero: aquí se acepta 0, pero {@code venta_items} exige
 *       {@code precio_unitario > 0};
 *   <li>que no haya productos repetidos: {@code UNIQUE (venta_id, producto_id)}; aquí no se fusionan;
 *   <li>que el efectivo recibido solo se pida cuando hay pago en efectivo.
 * </ul>
 *
 * <p>Un número que viene de fuera se acota antes de operar con él: como mucho 4 decimales y 12 cifras
 * enteras. Sin eso, un {@code 1E+99999999} haría trabajar al hilo sin terminar. Ningún importe
 * calculado puede pasar de lo que cabe en {@code DECIMAL(14,2)}.
 */
public final class CalculoDeVenta {

    private static final int ESCALA = 2;
    private static final int ESCALA_TARIFA = 4;
    private static final int MAX_ESCALA_ENTRADA = 4;
    private static final int MAX_CIFRAS_ENTERAS = 12;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    /** Lo más que cabe en {@code DECIMAL(14,2)}. */
    private static final BigDecimal MAXIMO_IMPORTE = new BigDecimal("999999999999.99");

    private static final String DESCUENTO_SIN_VALOR = "El descuento debe tener un tipo y un valor.";
    private static final String DESCUENTO_NEGATIVO = "El descuento no puede ser negativo.";
    private static final String DESCUENTO_PORCENTAJE = "Un descuento en porcentaje no puede pasar de 100.";
    private static final String DESCUENTO_CON_CENTAVOS = "Un descuento en pesos debe ser un monto entero.";
    private static final String DESCUENTO_DESMESURADO =
            "El descuento tiene más cifras de las que se admiten.";
    private static final String DESCUENTO_MAYOR = "El descuento no puede ser mayor que el valor al que se aplica.";
    private static final String EFECTIVO_RECIBIDO =
            "El efectivo recibido debe ser un monto en pesos enteros que cubra el total en efectivo.";

    private CalculoDeVenta() {}

    public static VentaCalculada calcular(List<LineaPedida> pedidas, Descuento descuentoTotal) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw new IllegalArgumentException("La venta necesita al menos una línea.");
        }
        int n = pedidas.size();
        List<BigDecimal> subtotales = new ArrayList<>(n);
        List<BigDecimal> propios = new ArrayList<>(n);
        List<BigDecimal> bases = new ArrayList<>(n);
        // Cuenta el descuento pedido y no el que salió en pesos: uno del 1 % sobre 10 pesos redondea a
        // cero, pero el vendedor lo pidió y la autorización con PIN se decide por eso.
        boolean tieneDescuento = false;
        BigDecimal sumaDeBases = BigDecimal.ZERO;

        for (LineaPedida p : pedidas) {
            validarLinea(p);
            BigDecimal subtotal = toparImporte(p.precioUnitario().multiply(BigDecimal.valueOf(p.cantidad())));
            BigDecimal propio = importeDelDescuento(p.descuento(), subtotal);
            BigDecimal base = subtotal.subtract(propio);
            subtotales.add(subtotal);
            propios.add(propio);
            bases.add(base);
            sumaDeBases = sumaDeBases.add(base);
            tieneDescuento |= p.descuento() != null && p.descuento().valor().signum() > 0;
        }

        BigDecimal totalADescontar = importeDelDescuento(descuentoTotal, sumaDeBases);
        tieneDescuento |= descuentoTotal != null && descuentoTotal.valor().signum() > 0;
        List<BigDecimal> repartido = repartir(pedidas, bases, sumaDeBases, totalADescontar);

        List<LineaCalculada> lineas = new ArrayList<>(n);
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal descuento = BigDecimal.ZERO;
        BigDecimal iva = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            LineaPedida p = pedidas.get(i);
            BigDecimal descuentoDeLinea = propios.get(i).add(repartido.get(i));
            BigDecimal base = subtotales.get(i).subtract(descuentoDeLinea);
            BigDecimal ivaDeLinea = base.multiply(p.tarifaIva()).setScale(0, RoundingMode.HALF_UP);
            toparImporte(base.add(ivaDeLinea));
            lineas.add(new LineaCalculada(
                    p.productoId(),
                    p.cantidad(),
                    pesos(p.precioUnitario()),
                    pesos(subtotales.get(i)),
                    pesos(descuentoDeLinea),
                    pesos(base),
                    p.tarifaIva().setScale(ESCALA_TARIFA, RoundingMode.UNNECESSARY),
                    tratamientoGuardado(p),
                    pesos(ivaDeLinea),
                    pesos(base.add(ivaDeLinea))));
            subtotal = subtotal.add(subtotales.get(i));
            descuento = descuento.add(descuentoDeLinea);
            iva = iva.add(ivaDeLinea);
        }
        BigDecimal total = toparImporte(subtotal.subtract(descuento).add(iva));
        toparImporte(subtotal);
        toparImporte(iva);
        return new VentaCalculada(List.copyOf(lineas), pesos(subtotal), pesos(descuento), pesos(iva), pesos(total),
                tieneDescuento);
    }

    /**
     * Lo que hay que devolver al cliente: lo recibido menos el total que se cobra en efectivo.
     * El efectivo recibido viene del cliente de la API, así que un valor ausente, con centavos o que
     * no cubre el total es un 400; el total en efectivo lo calcula el servidor.
     */
    public static BigDecimal cambio(BigDecimal totalEfectivo, BigDecimal recibido) {
        if (totalEfectivo == null || !esRazonable(totalEfectivo) || totalEfectivo.signum() < 0) {
            throw new IllegalArgumentException("El total en efectivo debe ser un monto no negativo.");
        }
        if (recibido == null || !esRazonable(recibido) || tieneCentavos(recibido) || recibido.compareTo(totalEfectivo) < 0) {
            throw new EfectivoRecibidoInvalidoException(EFECTIVO_RECIBIDO);
        }
        return pesos(recibido.subtract(totalEfectivo));
    }

    private static void validarLinea(LineaPedida p) {
        if (p == null || p.productoId() == null) {
            throw new IllegalArgumentException("Cada línea necesita un producto.");
        }
        if (p.cantidad() <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
        }
        BigDecimal precio = p.precioUnitario();
        if (precio == null || !esRazonable(precio) || precio.signum() < 0 || tieneCentavos(precio)) {
            throw new IllegalArgumentException("El precio debe ser un monto en pesos enteros no negativo.");
        }
        BigDecimal tarifa = p.tarifaIva();
        if (tarifa == null || !esRazonable(tarifa) || tarifa.signum() < 0 || tarifa.compareTo(BigDecimal.ONE) > 0
                || tarifa.stripTrailingZeros().scale() > ESCALA_TARIFA) {
            throw new IllegalArgumentException("La tarifa de IVA debe estar entre 0 y 1, con hasta cuatro decimales.");
        }
        tratamientoGuardado(p);
        // Sin IVA no hay tarifa: la vista da 0 a EXCLUIDO, EXENTO y NO_GRAVADO, y a la regla vacía
        // (tratamiento nulo, ADR-0024) le da tarifa 0. Una tarifa mayor que cero aquí es una fila
        // incoherente. GRAVADO con tarifa 0 sí es válido.
        String t = p.tratamientoIva();
        boolean sinIva = t == null || t.equals("EXCLUIDO") || t.equals("EXENTO") || t.equals("NO_GRAVADO");
        if (sinIva && tarifa.signum() > 0) {
            throw new IllegalArgumentException("Un tratamiento sin IVA no puede llevar tarifa.");
        }
    }

    /**
     * Cuántos pesos descuenta {@code descuento} de {@code valor}. Un descuento nulo no descuenta nada.
     * El 100 % y un monto igual al valor son válidos; uno mayor no.
     */
    private static BigDecimal importeDelDescuento(Descuento descuento, BigDecimal valor) {
        if (descuento == null) {
            return BigDecimal.ZERO;
        }
        if (descuento.tipo() == null || descuento.valor() == null) {
            throw new DescuentoInvalidoException(DESCUENTO_SIN_VALOR);
        }
        BigDecimal pedido = descuento.valor();
        if (!esRazonable(pedido)) {
            throw new DescuentoInvalidoException(DESCUENTO_DESMESURADO);
        }
        if (pedido.signum() < 0) {
            throw new DescuentoInvalidoException(DESCUENTO_NEGATIVO);
        }
        BigDecimal importe;
        if (descuento.tipo() == TipoDescuento.PORCENTAJE) {
            if (pedido.compareTo(CIEN) > 0) {
                throw new DescuentoInvalidoException(DESCUENTO_PORCENTAJE);
            }
            importe = valor.multiply(pedido).divide(CIEN, 0, RoundingMode.HALF_UP);
        } else {
            if (tieneCentavos(pedido)) {
                throw new DescuentoInvalidoException(DESCUENTO_CON_CENTAVOS);
            }
            importe = pedido.setScale(0, RoundingMode.UNNECESSARY);
        }
        if (importe.compareTo(valor) > 0) {
            throw new DescuentoInvalidoException(DESCUENTO_MAYOR);
        }
        return importe;
    }

    /**
     * Reparte {@code total} entre las líneas en proporción a su base. Cada parte es
     * {@code total · base / suma} al peso con HALF_UP, y el residuo va a la línea de mayor base (a igual
     * base, la de menor {@code productoId}). Si esa línea no puede absorberlo sin pasarse de su base ni
     * quedar en negativo, lo que falte sigue a la siguiente del mismo orden: así ninguna línea queda
     * con un descuento imposible.
     */
    private static List<BigDecimal> repartir(List<LineaPedida> pedidas, List<BigDecimal> bases,
            BigDecimal sumaDeBases, BigDecimal total) {
        int n = bases.size();
        BigDecimal[] partes = new BigDecimal[n];
        BigDecimal repartido = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            partes[i] = sumaDeBases.signum() == 0
                    ? BigDecimal.ZERO
                    : total.multiply(bases.get(i)).divide(sumaDeBases, 0, RoundingMode.HALF_UP);
            repartido = repartido.add(partes[i]);
        }
        BigDecimal residuo = total.subtract(repartido);
        if (residuo.signum() != 0) {
            List<Integer> orden = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                orden.add(i);
            }
            // sort es estable: a igual base y producto manda el orden de entrada.
            orden.sort(Comparator
                    .comparing((Integer i) -> bases.get(i)).reversed()
                    .thenComparing(i -> pedidas.get(i).productoId()));
            for (int i : orden) {
                if (residuo.signum() == 0) {
                    break;
                }
                BigDecimal cabe = residuo.signum() > 0
                        ? bases.get(i).subtract(partes[i]).min(residuo)
                        : partes[i].negate().max(residuo);
                partes[i] = partes[i].add(cabe);
                residuo = residuo.subtract(cabe);
            }
        }
        return List.of(partes);
    }

    /** El tratamiento que se guarda en {@code venta_items.tratamiento_iva} (M6, ADR-0024). */
    private static String tratamientoGuardado(LineaPedida p) {
        String tratamiento = p.tratamientoIva();
        if (tratamiento == null) {
            return "NO_GRAVADO";
        }
        return switch (tratamiento) {
            // La vista ya pone la tarifa en 0 bajo el umbral: eso decide si fue excluido o gravado.
            case "EXCLUIDO_HASTA_UMBRAL" -> p.tarifaIva().signum() == 0 ? "EXCLUIDO" : "GRAVADO";
            case "GRAVADO", "EXCLUIDO", "EXENTO", "NO_GRAVADO" -> tratamiento;
            default -> throw new IllegalArgumentException("Tratamiento de IVA desconocido.");
        };
    }

    /**
     * Acota un número de fuera antes de operar con él, solo con {@code scale()} y {@code precision()},
     * que no calculan nada: a lo sumo cuatro decimales y doce cifras enteras. La escala negativa
     * ({@code 1E+3}) vale mientras el número siga teniendo pocas cifras. La resta va en {@code long}
     * porque una escala extrema desbordaría el {@code int}.
     */
    private static boolean esRazonable(BigDecimal valor) {
        return valor.scale() <= MAX_ESCALA_ENTRADA
                && (long) valor.precision() - valor.scale() <= MAX_CIFRAS_ENTERAS;
    }

    private static BigDecimal toparImporte(BigDecimal importe) {
        if (importe.compareTo(MAXIMO_IMPORTE) > 0) {
            throw new IllegalArgumentException("El importe de la venta es mayor que el que se puede guardar.");
        }
        return importe;
    }

    private static boolean tieneCentavos(BigDecimal valor) {
        return valor.stripTrailingZeros().scale() > 0;
    }

    private static BigDecimal pesos(BigDecimal valor) {
        return valor.setScale(ESCALA, RoundingMode.UNNECESSARY);
    }
}
