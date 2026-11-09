package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.ReglaDeMargen;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.api.dto.SolicitudReglaMargen;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioPrecios;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Las reglas de ganancia (RF-30, RF-32) y lo que se escribe para un producto
 * (ADR-0025).
 *
 * Esta clase no decide el precio de nadie. El cálculo vive en
 * `v_precios_producto`, que aplica la regla vigente en cada lectura, y eso no
 * es un detalle de implementación: es lo que hace cierto el criterio del RF-30
 * —«la modificación surte efecto en el cálculo siguiente sin desplegar»—. El
 * ADR-0025 retiró la prohibición de escribir el precio del RF-31, pero lo
 * escrito sigue siendo una regla con vigencia, no una columna del producto.
 *
 * Lo que sí hace es impedir que se configuren reglas que después nadie
 * sabría interpretar.
 */
@Service
public class ServicioPrecios {

    private static final String SOBRE_PRECIO = "SOBRE_PRECIO";

    /**
     * El tope del margen sobre el precio.
     *
     * Con factor 2 el divisor de la fórmula es cero, de modo que un
     * margen del 100 % sobre el precio de venta significaría precio
     * infinito: el costo tendría que ser cero. `ck_reglas_margen_sobre_precio`
     * lo sostiene en la base; aquí se repite para que el rechazo llegue
     * como un 400 con su motivo en lugar de como un error de integridad.
     */
    private static final BigDecimal TOPE_SOBRE_PRECIO = new BigDecimal("2");

    private static final String CONTADO = "CONTADO";
    private static final String CREDITO = "CREDITO";
    private static final String SOBRE_COSTO = "SOBRE_COSTO";

    /** El mayor que cabe en `reglas_margen.factor`, DECIMAL(6,4). */
    private static final BigDecimal TOPE_PORCENTAJE = new BigDecimal("9899.99");

    /**
     * Los dígitos de `reglas_margen.precio_fijo`, DECIMAL(12,0). No son más
     * porque `precio_unitario` de las ventas es DECIMAL(14,2): un precio más
     * largo se guardaría bien y fallaría el día de cobrarlo.
     */
    private static final int DIGITOS_DEL_PRECIO = 12;

    private final RepositorioPrecios precios;
    private final Clock reloj;

    public ServicioPrecios(RepositorioPrecios precios, Clock reloj) {
        this.precios = precios;
        this.reloj = reloj;
    }

    /** El día de Bogotá según el reloj de la aplicación: la única fuente de «hoy» (ADR-0030). */
    private LocalDate hoy() {
        return Hoy.de(reloj);
    }

    @Transactional(readOnly = true)
    public List<ReglaDeMargen> listar() {
        return precios.listar(ContextoEmpresa.empresaActual(), hoy());
    }

    @Transactional(readOnly = true)
    public Optional<ReglaDeMargen> buscar(Integer reglaId) {
        return precios.buscar(ContextoEmpresa.empresaActual(), reglaId, hoy());
    }

    /**
     * Cuántas referencias del catálogo alcanzaría una regla de este
     * ámbito, para que la pantalla lo pueda decir antes de guardar.
     */
    @Transactional(readOnly = true)
    public int alcance(Integer categoriaId, Integer proveedorId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        exigirAmbitoPropio(empresa, categoriaId, proveedorId);
        return precios.productosAlcanzados(empresa, categoriaId, proveedorId, hoy());
    }

    /**
     * Qué referencias cambiarían de tratamiento tributario si se
     * aplicara esta regla (RF-35).
     *
     * Se consulta antes de guardar, no después de romper nada: el
     * requisito habla de avisar «tras aplicar una lista», y avisar antes
     * cumple lo mismo y además deja decidir. Cuando el módulo de listas
     * de precios exista, reutilizará esta misma comparación.
     *
     * Sin cruces devuelve una lista vacía, que es lo que el requisito
     * pide expresamente que ocurra en lugar de un error.
     */
    @Transactional(readOnly = true)
    public List<CruceDeUmbral> simular(SolicitudReglaMargen solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        exigirAmbitoPropio(empresa, solicitud.categoriaId(), solicitud.proveedorId());
        exigirFactorCoherente(solicitud.formaCalculo(), solicitud.factor());
        return precios.simularCruces(empresa, solicitud.categoriaId(), solicitud.proveedorId(),
                solicitud.formaPago(), solicitud.formaCalculo(), solicitud.factor());
    }

    /**
     * Crea una regla de ámbito.
     *
     * Bloquea la empresa antes de leer nada: el solape se comprueba y se escribe
     * con ella bloqueada, y el bloqueo se suelta al confirmar. Con un doble clic,
     * la segunda petición espera aquí y ve la regla de la primera, en lugar de
     * comprobar a la vez y dejar dos reglas iguales (M14). Va lo primero porque
     * las lecturas que siguen fijarían la foto de la transacción antes de que la
     * primera confirme.
     */
    @Transactional
    public ReglaDeMargen crear(SolicitudReglaMargen solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        precios.bloquearEmpresa(empresa);
        LocalDate hoy = hoy();
        exigirAmbitoPropio(empresa, solicitud.categoriaId(), solicitud.proveedorId());
        exigirFactorCoherente(solicitud.formaCalculo(), solicitud.factor());
        exigirRangoCoherente(solicitud.vigenteDesde(), solicitud.vigenteHasta());

        if (precios.haySolape(empresa, solicitud.categoriaId(), solicitud.proveedorId(),
                solicitud.formaPago(), solicitud.vigenteDesde(), solicitud.vigenteHasta(),
                null)) {
            throw new RegistroRepetidoException(describirSolape(solicitud));
        }

        Integer id = precios.insertar(empresa, solicitud.categoriaId(), solicitud.proveedorId(),
                solicitud.formaPago(), solicitud.formaCalculo(), solicitud.factor(),
                solicitud.vigenteDesde(), solicitud.vigenteHasta());
        return precios.buscar(empresa, id, hoy).orElseThrow(() -> new IllegalStateException(
                "La regla " + id + " no se encontró justo después de crearla."));
    }

    /**
     * Corrige una regla: su fórmula, su factor y su vigencia.
     *
     * El ámbito no se puede cambiar. Mover una regla de «todos los
     * productos» a «la categoría Computadores» no es corregirla, es otra
     * regla, y haciéndolo en el sitio se perdería que la primera llegó a
     * existir y a fijar precios. Para eso se cierra esta y se crea la
     * otra.
     */
    @Transactional
    public Optional<ReglaDeMargen> actualizar(Integer reglaId, SolicitudReglaMargen solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        // Empresa primero y regla después, en el mismo orden que `crear` y
        // `reemplazar`: si no, dos escrituras que se cruzan se esperan entre sí.
        precios.bloquearEmpresa(empresa);
        LocalDate hoy = hoy();
        Optional<ReglaDeMargen> actual = precios.buscar(empresa, reglaId, hoy);
        if (actual.isEmpty()) {
            return Optional.empty();
        }
        exigirFactorCoherente(solicitud.formaCalculo(), solicitud.factor());
        exigirRangoCoherente(solicitud.vigenteDesde(), solicitud.vigenteHasta());

        ReglaDeMargen r = actual.get();
        if (precios.haySolape(empresa, r.categoriaId(), r.proveedorId(), r.formaPago(),
                solicitud.vigenteDesde(), solicitud.vigenteHasta(), reglaId)) {
            throw new RegistroRepetidoException(
                    "Con esa vigencia, la regla se solaparía con otra del mismo alcance. "
                            + "Cierra primero la que ya está.");
        }

        precios.actualizar(empresa, reglaId, solicitud.formaCalculo(), solicitud.factor(),
                solicitud.vigenteDesde(), solicitud.vigenteHasta());
        return precios.buscar(empresa, reglaId, hoy);
    }

    /**
     * Cierra la vigencia de una regla, que no es borrarla.
     *
     * La fila se conserva porque una venta de marzo se explica con la
     * regla que regía en marzo, y una regla borrada deja un precio
     * histórico sin justificación.
     *
     * Sin fecha, cierra ayer: así la regla deja de aplicar desde hoy. Con
     * fecha de hoy seguiría vigente la jornada entera, que casi nunca es
     * lo que quiere quien la está retirando.
     */
    @Transactional
    public Optional<ReglaDeMargen> cerrar(Integer reglaId, LocalDate hasta) {
        Integer empresa = ContextoEmpresa.empresaActual();
        LocalDate hoy = hoy();
        Optional<ReglaDeMargen> actual = precios.buscar(empresa, reglaId, hoy);
        if (actual.isEmpty()) {
            return Optional.empty();
        }
        LocalDate fin = hasta != null ? hasta : hoy.minusDays(1);
        if (fin.isBefore(actual.get().vigenteDesde())) {
            throw new ReglaInvalidaException(
                    "La regla empezó a regir el " + actual.get().vigenteDesde()
                            + " y no puede cerrarse antes de esa fecha.");
        }
        precios.cerrar(empresa, reglaId, fin);
        return precios.buscar(empresa, reglaId, hoy);
    }

    /**
     * Reemplaza una regla que rige: cierra la vieja el día antes de que empiece
     * la nueva y crea la nueva, en una sola transacción.
     *
     * Hecho en el navegador en dos llamadas, un fallo de la segunda dejaría
     * productos sin regla y, por tanto, sin precio de venta. Aquí o pasa todo o
     * no pasa nada.
     *
     * El alcance y la forma de pago son los de la vieja: cambiarlos no es
     * reemplazar, es otra regla. La nueva no puede empezar antes que la vieja ni
     * después del fin que la vieja ya tenía: cerrarla más tarde la alargaría y
     * podría solaparla con otra que ya la sucede. Tampoco puede empezar antes de
     * hoy: ni el navegador es la única defensa, ni se reescribe el pasado.
     * Un resultado vacío solo significa que la regla no existe; si la recién
     * creada no se relee, es un error del servidor.
     */
    @Transactional
    public Optional<ReglaDeMargen> reemplazar(Integer reglaId, SolicitudReglaMargen s) {
        Integer empresa = ContextoEmpresa.empresaActual();
        // Empresa antes que regla: el mismo orden que `crear` y `actualizar`.
        precios.bloquearEmpresa(empresa);
        LocalDate hoy = hoy();
        Optional<RepositorioPrecios.ReglaBloqueada> encontrada = precios.bloquear(empresa, reglaId);
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        RepositorioPrecios.ReglaBloqueada vieja = encontrada.get();
        exigirFactorCoherente(s.formaCalculo(), s.factor());
        exigirRangoCoherente(s.vigenteDesde(), s.vigenteHasta());

        if (!Objects.equals(s.categoriaId(), vieja.categoriaId())
                || !Objects.equals(s.proveedorId(), vieja.proveedorId())
                || !vieja.formaPago().equals(s.formaPago())) {
            throw new ReglaInvalidaException(
                    "Al reemplazar una regla no cambian su alcance ni su forma de pago: "
                            + "para eso, crea otra regla.");
        }
        if (vieja.vigenteHasta() != null && vieja.vigenteHasta().isBefore(hoy)) {
            throw new ReglaInvalidaException(
                    "La regla ya terminó el " + vieja.vigenteHasta() + " y no se puede reemplazar.");
        }
        if (!s.vigenteDesde().isAfter(vieja.vigenteDesde())) {
            throw new ReglaInvalidaException(
                    "La nueva regla tiene que empezar después del " + vieja.vigenteDesde()
                            + ", que es cuando empezó la que reemplaza.");
        }
        if (s.vigenteDesde().isBefore(hoy)) {
            throw new ReglaInvalidaException(
                    "La nueva regla no puede empezar antes de hoy: una fecha pasada "
                            + "cambiaría el precio de ventas que ya se hicieron.");
        }
        LocalDate cierre = s.vigenteDesde().minusDays(1);
        if (vieja.vigenteHasta() != null && cierre.isAfter(vieja.vigenteHasta())) {
            throw new ReglaInvalidaException(
                    "La regla actual ya termina el " + vieja.vigenteHasta()
                            + ": la nueva tiene que empezar a más tardar al día siguiente.");
        }
        if (precios.haySolape(empresa, s.categoriaId(), s.proveedorId(), s.formaPago(),
                s.vigenteDesde(), s.vigenteHasta(), reglaId)) {
            throw new RegistroRepetidoException(describirSolape(s));
        }

        precios.cerrar(empresa, reglaId, cierre);
        Integer id = precios.insertar(empresa, s.categoriaId(), s.proveedorId(), s.formaPago(),
                s.formaCalculo(), s.factor(), s.vigenteDesde(), s.vigenteHasta());
        return Optional.of(precios.buscar(empresa, id, hoy).orElseThrow(() -> new IllegalStateException(
                "La regla " + id + " no se encontró justo después de reemplazar la " + reglaId + ".")));
    }

    // ------------------------------------------- lo escrito para un producto

    /** El alta exige el precio de contado; la edición, no. */
    public void exigirPrecioDeAlta(BigDecimal costo, SolicitudPrecio contado, SolicitudPrecio credito) {
        if (contado == null) {
            throw new ReglaInvalidaException(
                    "Escribe el porcentaje de ganancia o el precio de venta de contado.");
        }
        comprobarPrecios(costo, contado, credito, null);
    }

    /**
     * Rechaza lo escrito antes de tocar nada, para que un precio inválido no
     * deje a medias el resto del producto.
     */
    public void comprobarPrecios(BigDecimal costo, SolicitudPrecio contado, SolicitudPrecio credito,
                                 Boolean creditoIgualAlContado) {
        if (credito != null && Boolean.TRUE.equals(creditoIgualAlContado)) {
            throw new ReglaInvalidaException(
                    "Si el crédito va igual que el contado, no escribas otro precio a crédito.");
        }
        reglaEscrita(costo, contado, "de contado");
        reglaEscrita(costo, credito, "a crédito");
    }

    /**
     * Convierte lo escrito en reglas propias del producto (ADR-0025).
     *
     * El crédito sin escribir se guarda igual que el contado, que es lo que ve
     * quien no marcó «Usar otro precio a crédito». Esa igualdad no es un enlace
     * guardado: son dos reglas con los mismos datos, y lo que hace que el
     * crédito siga al contado es que el formulario la vuelva a pedir mientras la
     * casilla esté sin marcar. {@code creditoIgualAlContado} en falso es la
     * casilla marcada, y entonces el crédito no se mueve.
     */
    @Transactional
    public void escribirPrecios(Integer empresa, Integer productoId, BigDecimal costo,
                                SolicitudPrecio contado, SolicitudPrecio credito,
                                Boolean creditoIgualAlContado) {
        escribirPrecios(empresa, productoId, costo, contado, credito, creditoIgualAlContado, hoy());
    }

    /**
     * Bloquea la fila del producto hasta el final de la transacción.
     *
     * Quien además escribe {@code historico_costos} lo llama antes (ver
     * {@code RepositorioImportacion.bloquearProductosDeLaCarga}): carga, productos y
     * histórico, en ese orden, en todos los caminos. Volver a bloquear lo ya bloqueado en la
     * misma transacción no cuesta nada.
     */
    @Transactional
    public void bloquearProducto(Integer empresa, Integer productoId) {
        precios.bloquearProducto(empresa, productoId);
    }

    /** Igual, con el «hoy» que ya decidió quien llama: una operación, un solo día. */
    @Transactional
    public void escribirPrecios(Integer empresa, Integer productoId, BigDecimal costo,
                                SolicitudPrecio contado, SolicitudPrecio credito,
                                Boolean creditoIgualAlContado, LocalDate hoy) {
        comprobarPrecios(costo, contado, credito, creditoIgualAlContado);
        boolean igualar = Boolean.TRUE.equals(creditoIgualAlContado)
                || (creditoIgualAlContado == null && contado != null && credito == null);
        if (contado == null && credito == null && !igualar) {
            return;
        }

        precios.bloquearProducto(empresa, productoId);
        ReglaDePrecio reglaContado = reglaEscrita(costo, contado, "de contado");
        if (reglaContado != null) {
            fijarReglaPropia(empresa, productoId, CONTADO, reglaContado, hoy);
        }
        if (credito != null) {
            fijarReglaPropia(empresa, productoId, CREDITO, reglaEscrita(costo, credito, "a crédito"), hoy);
        } else if (igualar) {
            fijarReglaPropia(empresa, productoId, CREDITO,
                    reglaContado != null ? reglaContado : reglaVigenteDeContado(empresa, productoId, hoy),
                    hoy);
        }
    }

    /**
     * El contado que rige hoy, aunque venga de la regla general: volver a «igual
     * que el contado» sin tocar el contado no tiene otra cosa que copiar.
     */
    private ReglaDePrecio reglaVigenteDeContado(Integer empresa, Integer productoId, LocalDate hoy) {
        // Primero la propia, que se lee con bloqueo. La vista es una lectura normal
        // y ve la foto de cuando empezó la transacción: tras esperar a
        // `bloquearProducto`, podría copiar el contado que otro acaba de cambiar.
        return precios.reglaPropiaVigente(empresa, productoId, CONTADO, hoy)
                .or(() -> precios.reglaVigente(empresa, productoId, CONTADO))
                .orElseThrow(() -> new ReglaInvalidaException(
                        "Este producto no tiene precio de contado que copiar. "
                                + "Escribe el porcentaje o el precio de contado."));
    }

    /**
     * Abre, corrige o deja como está la regla propia.
     *
     * Cambiar el precio cierra la regla vigente ayer y abre otra desde hoy, para
     * conservar la historia; lo que ya se había cambiado hoy se corrige en su
     * fila. Volver a escribir lo mismo no es un cambio: el formulario manda lo
     * que alguien tocó, y tocar un campo sin cambiarlo no merece una fila.
     */
    private void fijarReglaPropia(Integer empresa, Integer productoId, String formaPago,
                                  ReglaDePrecio regla, LocalDate hoy) {
        boolean igual = precios.reglaPropiaVigente(empresa, productoId, formaPago, hoy)
                .filter(actual -> dicenLoMismo(actual, regla))
                .isPresent();
        if (igual || precios.corregirReglaPropiaDeHoy(empresa, productoId, formaPago, regla, hoy) > 0) {
            return;
        }
        precios.cerrarReglaPropiaAyer(empresa, productoId, formaPago, hoy);
        precios.abrirReglaPropia(empresa, productoId, formaPago, regla, hoy);
    }

    private static boolean dicenLoMismo(ReglaDePrecio a, ReglaDePrecio b) {
        return a.formaCalculo().equals(b.formaCalculo())
                && mismaCifra(a.factor(), b.factor())
                && mismaCifra(a.precioFijo(), b.precioFijo());
    }

    private static boolean mismaCifra(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? a == b : a.compareTo(b) == 0;
    }

    /** Nulo si no se escribió nada para esta forma de pago. */
    private static ReglaDePrecio reglaEscrita(BigDecimal costo, SolicitudPrecio escrito, String forma) {
        if (escrito == null) {
            return null;
        }
        if (escrito.porcentaje() == null && escrito.precio() == null) {
            throw new ReglaInvalidaException(
                    "Escribe el porcentaje de ganancia o el precio de venta " + forma + ".");
        }
        if (escrito.porcentaje() != null && escrito.precio() != null) {
            throw new ReglaInvalidaException(
                    "Escribe el porcentaje o el precio de venta " + forma + ", no los dos.");
        }
        return escrito.porcentaje() != null
                ? reglaDePorcentaje(costo, escrito.porcentaje())
                : reglaDeCifra(escrito.precio());
    }

    private static ReglaDePrecio reglaDePorcentaje(BigDecimal costo, BigDecimal porcentaje) {
        if (porcentaje.signum() <= 0) {
            throw new ReglaInvalidaException(
                    "El porcentaje de ganancia debe ser mayor que cero. Para vender sin "
                            + "ganancia o con pérdida, escribe el precio de venta.");
        }
        if (porcentaje.stripTrailingZeros().scale() > 2) {
            throw new ReglaInvalidaException("El porcentaje admite hasta dos decimales.");
        }
        if (porcentaje.compareTo(TOPE_PORCENTAJE) > 0) {
            throw new ReglaInvalidaException("El porcentaje no puede pasar de 9.899,99 %.");
        }
        if (costo != null) {
            BigDecimal precio = CalculoDePrecio.precioDesdePorcentaje(costo, porcentaje);
            if (precio.signum() == 0) {
                throw new ReglaInvalidaException(
                        "Con ese costo y ese porcentaje el precio queda en cero pesos. "
                                + "Escribe el precio de venta.");
            }
            if (precio.precision() - precio.scale() > DIGITOS_DEL_PRECIO) {
                throw new ReglaInvalidaException(
                        "Con ese costo y ese porcentaje el precio pasa de " + DIGITOS_DEL_PRECIO
                                + " cifras y no se podría vender.");
            }
        }
        return new ReglaDePrecio(CalculoDePrecio.factorDesdePorcentaje(porcentaje), null, SOBRE_COSTO);
    }

    private static ReglaDePrecio reglaDeCifra(BigDecimal precio) {
        if (precio.signum() <= 0) {
            throw new ReglaInvalidaException("El precio de venta debe ser mayor que cero.");
        }
        BigDecimal limpio = precio.stripTrailingZeros();
        if (limpio.scale() > 0) {
            throw new ReglaInvalidaException("El precio de venta va en pesos enteros, sin centavos.");
        }
        if (limpio.precision() - limpio.scale() > DIGITOS_DEL_PRECIO) {
            throw new ReglaInvalidaException(
                    "El precio de venta admite hasta " + DIGITOS_DEL_PRECIO + " cifras.");
        }
        return new ReglaDePrecio(null, limpio.setScale(0), SOBRE_COSTO);
    }

    private void exigirAmbitoPropio(Integer empresa, Integer categoriaId, Integer proveedorId) {
        if (!precios.categoriaPropia(empresa, categoriaId)) {
            throw new ReferenciaAjenaException(
                    "La categoría " + categoriaId + " no es de esta empresa.");
        }
        if (!precios.proveedorPropio(empresa, proveedorId)) {
            throw new ReferenciaAjenaException(
                    "El proveedor " + proveedorId + " no es de esta empresa.");
        }
    }

    private void exigirFactorCoherente(String formaCalculo, BigDecimal factor) {
        if (SOBRE_PRECIO.equals(formaCalculo) && factor.compareTo(TOPE_SOBRE_PRECIO) >= 0) {
            throw new ReglaInvalidaException(
                    "Un margen sobre el precio de venta no puede llegar al 100 %: el factor "
                            + "tiene que quedar por debajo de 2, y llegó " + factor.toPlainString()
                            + ". Con un margen del 100 % el costo tendría que ser cero.");
        }
    }

    private void exigirRangoCoherente(LocalDate desde, LocalDate hasta) {
        if (hasta != null && hasta.isBefore(desde)) {
            throw new ReglaInvalidaException(
                    "La vigencia termina el " + hasta + ", antes de empezar el " + desde + ".");
        }
    }

    private static String describirSolape(SolicitudReglaMargen s) {
        String ambito;
        if (s.categoriaId() == null && s.proveedorId() == null) {
            ambito = "la regla general";
        } else if (s.proveedorId() == null) {
            ambito = "la regla de la categoría " + s.categoriaId();
        } else if (s.categoriaId() == null) {
            ambito = "la regla del proveedor " + s.proveedorId();
        } else {
            ambito = "la regla de la categoría " + s.categoriaId()
                    + " con el proveedor " + s.proveedorId();
        }
        return "Ya hay " + ambito + " para " + s.formaPago().toLowerCase()
                + " con una vigencia que se solapa. Dos reglas del mismo alcance a la vez "
                + "no dan error, pero solo manda una, y corregir la otra no cambiaría "
                + "ningún precio. Cierra la que está antes de crear esta.";
    }
}
