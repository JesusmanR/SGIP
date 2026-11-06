package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ConfiguracionTributaria;
import co.com.bspstore.sgip.api.dto.ReglaDeIva;
import co.com.bspstore.sgip.api.dto.SolicitudConfiguracionTributaria;
import co.com.bspstore.sgip.api.dto.SolicitudReglaIva;
import co.com.bspstore.sgip.api.dto.TipoImpuestoNacional;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.datos.RepositorioConfiguracionTributaria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lo que cada empresa decide sobre sus impuestos (ADR-0016, ADR-0024).
 *
 * La plataforma publica el catálogo de tipos de impuesto y su tarifa; la
 * empresa elige cuál le aplica, a qué, y desde cuándo. Esta clase no
 * calcula ningún impuesto —eso lo hace {@code v_regla_iva}— y tampoco
 * decide si la empresa puede vender: desde el ADR-0024 puede hacerlo sin
 * haber configurado nada.
 *
 * Las reglas se crean y se cierran, no se editan: una venta se explica
 * con la regla que regía el día que se hizo, igual que las reglas de
 * ganancia. La única que se borra es la que todavía no empezó a regir, porque
 * nunca explicó nada (ver {@link #cerrarRegla}).
 */
@Service
public class ServicioConfiguracionTributaria {

    /**
     * Qué pasó con una regla al «cerrarla»: se cerró y queda su fila, o se
     * retiró porque aún no había empezado.
     */
    public record ResultadoCierre(ReglaDeIva regla, boolean retirada) {}

    /** Lo que ve una empresa que nadie ha configurado: los valores por omisión de la V12. */
    private static final ConfiguracionTributaria SIN_CONFIGURAR =
            new ConfiguracionTributaria(false, false, false, null, false, 5, false);

    private final RepositorioConfiguracionTributaria repositorio;
    private final CategoriaRepositorio categorias;
    private final ProductoRepositorio productos;
    private final Clock reloj;

    public ServicioConfiguracionTributaria(RepositorioConfiguracionTributaria repositorio,
                                           CategoriaRepositorio categorias,
                                           ProductoRepositorio productos,
                                           Clock reloj) {
        this.repositorio = repositorio;
        this.categorias = categorias;
        this.productos = productos;
        this.reloj = reloj;
    }

    // ------------------------------------------------------ la configuración

    @Transactional(readOnly = true)
    public ConfiguracionTributaria configuracion() {
        return repositorio.buscarConfiguracion(ContextoEmpresa.empresaActual())
                .orElse(SIN_CONFIGURAR);
    }

    /**
     * Fija la configuración entera y la deja marcada como completa.
     *
     * El ICA solo tiene sentido si la empresa practica retenciones; el
     * esquema lo exige con {@code ck_empresa_config_ica_coherente}, y aquí se
     * repite para que el rechazo llegue como un 400 con su motivo y no como
     * un error de integridad.
     */
    @Transactional
    public ConfiguracionTributaria guardar(SolicitudConfiguracionTributaria s) {
        Integer empresa = ContextoEmpresa.empresaActual();
        if (s.tarifaIca() != null && !s.practicaRetenciones()) {
            throw new DatoTributarioInvalidoException(
                    "La tarifa de ICA solo se puede fijar si la empresa practica retenciones.");
        }
        repositorio.guardarConfiguracion(empresa, s.responsableIva(),
                s.aplicaExclusionUmbral(), s.practicaRetenciones(), s.tarifaIca(),
                s.manejaEnvios(), s.cotizacionVigenciaDias());
        return repositorio.buscarConfiguracion(empresa).orElseThrow(() ->
                new IllegalStateException(
                        "La configuración tributaria no se encontró justo después de guardarla."));
    }

    // ------------------------------------------------------------- el catálogo

    @Transactional(readOnly = true)
    public List<TipoImpuestoNacional> impuestos() {
        // Exige empresa aunque el catálogo no sea de ninguna: es la pantalla
        // de una empresa, y la plataforma tiene la suya (ADR-0020).
        ContextoEmpresa.empresaActual();
        return repositorio.listarImpuestosActivos();
    }

    // --------------------------------------------------------------- las reglas

    @Transactional(readOnly = true)
    public List<ReglaDeIva> reglas() {
        return repositorio.listarReglas(ContextoEmpresa.empresaActual());
    }

    @Transactional
    public ReglaDeIva crearRegla(SolicitudReglaIva s) {
        Integer empresa = ContextoEmpresa.empresaActual();
        // Lo primero, antes de leer nada: el solape se comprueba y se escribe
        // con la empresa bloqueada, y el bloqueo se suelta al confirmar. Con
        // un doble clic, la segunda petición espera aquí y ve la regla de la
        // primera.
        repositorio.bloquearEmpresa(empresa);

        if (s.categoriaId() != null && s.productoId() != null) {
            throw new DatoTributarioInvalidoException(
                    "Una regla es de una categoría o de un producto, no de las dos cosas a la vez. "
                            + "Sin ninguna de las dos es la regla general de la empresa.");
        }
        if (s.vigenteHasta() != null && s.vigenteHasta().isBefore(s.vigenteDesde())) {
            throw new DatoTributarioInvalidoException(
                    "La vigencia termina el " + s.vigenteHasta()
                            + ", antes de empezar el " + s.vigenteDesde() + ".");
        }
        if (s.categoriaId() != null
                && categorias.findByEmpresaIdAndId(empresa, s.categoriaId()).isEmpty()) {
            throw new ReferenciaAjenaException(
                    "La categoría " + s.categoriaId() + " no es de esta empresa.");
        }
        if (s.productoId() != null
                && productos.findByEmpresaIdAndId(empresa, s.productoId()).isEmpty()) {
            throw new ReferenciaAjenaException(
                    "El producto " + s.productoId() + " no es de esta empresa.");
        }
        TipoImpuestoNacional impuesto = repositorio.buscarImpuestoActivo(s.codigoImpuesto())
                .orElseThrow(() -> new ImpuestoNoDisponibleException(
                        "El impuesto «" + s.codigoImpuesto()
                                + "» no existe en el catálogo o no está activo."));

        if (repositorio.haySolape(empresa, s.categoriaId(), s.productoId(),
                s.vigenteDesde(), s.vigenteHasta())) {
            throw new RegistroRepetidoException(describirSolape(s));
        }

        Integer id = repositorio.insertarRegla(empresa, s.categoriaId(), s.productoId(),
                impuesto.tipoImpuestoId(), s.vigenteDesde(), s.vigenteHasta(),
                limpiar(s.fundamento()));
        return repositorio.buscarRegla(empresa, id).orElseThrow(() ->
                new IllegalStateException(
                        "La regla " + id + " no se encontró justo después de crearla."));
    }

    /**
     * Cierra la vigencia de una regla. Casi nunca es borrarla: la fila se
     * conserva porque una venta se explica con la regla que regía.
     *
     * <ul>
     *   <li>Con {@code hasta}, la regla termina ese día. Vale desde el día de
     *       inicio (rige un día) y también para una regla futura.</li>
     *   <li>Sin {@code hasta}, cierra ayer: así deja de aplicar desde hoy. Con
     *       la fecha de hoy seguiría vigente la jornada entera, que casi
     *       nunca es lo que quiere quien la retira.</li>
     *   <li>Excepción: sin {@code hasta} y con la regla todavía por empezar,
     *       se <b>retira</b> (se borra). Una regla que nunca rigió no explicó
     *       ninguna venta, y cerrarla «ayer» sería cerrarla antes de empezar.
     *       Es la forma de deshacer una regla creada por equivocación.</li>
     *   <li>Sin {@code hasta} y con la regla que empieza hoy no se hace nada:
     *       rige hoy, y se cierra indicando {@code hasta} con la fecha de hoy.</li>
     * </ul>
     *
     * Cerrar solo acorta. Alargar el cierre de una regla que ya terminaba
     * podría solaparla con la que la sustituyó, y el solape es justo lo que
     * {@link #crearRegla} impide al crear.
     */
    @Transactional
    public Optional<ResultadoCierre> cerrarRegla(Integer reglaId, LocalDate hasta) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<ReglaDeIva> actual = repositorio.buscarRegla(empresa, reglaId);
        if (actual.isEmpty()) {
            return Optional.empty();
        }
        ReglaDeIva regla = actual.get();
        LocalDate hoy = LocalDate.now(reloj);

        if (hasta == null && regla.vigenteDesde().isAfter(hoy)) {
            if (repositorio.borrarRegla(empresa, reglaId) == 0) {
                // La base ya tiene vigente la regla que este reloj cree por
                // empezar: no se borró y no se puede decir que se retiró.
                throw new DatoTributarioInvalidoException(
                        "La regla ya empezó a regir y no se puede retirar. "
                                + "Para cerrarla, indica «hasta» con una fecha.");
            }
            return Optional.of(new ResultadoCierre(regla, true));
        }

        LocalDate fin = hasta != null ? hasta : hoy.minusDays(1);
        if (fin.isBefore(regla.vigenteDesde())) {
            if (hasta == null) {
                throw new DatoTributarioInvalidoException(
                        "La regla rige desde hoy, " + hoy + ", y sin fecha se cierra ayer, "
                                + "antes de empezar. Para cerrarla, indica «hasta» con la fecha "
                                + "de hoy (" + hoy + ").");
            }
            throw new DatoTributarioInvalidoException(
                    "La regla empieza el " + regla.vigenteDesde() + ": «hasta» (" + hasta
                            + ") no puede ser anterior a esa fecha.");
        }
        if (regla.vigenteHasta() != null && fin.isAfter(regla.vigenteHasta())) {
            throw new DatoTributarioInvalidoException(
                    "La regla ya termina el " + regla.vigenteHasta()
                            + ": cerrarla puede acortar su vigencia, no alargarla.");
        }
        repositorio.cerrarRegla(empresa, reglaId, fin);
        return repositorio.buscarRegla(empresa, reglaId)
                .map(r -> new ResultadoCierre(r, false));
    }

    /** Un fundamento en blanco se guarda como ausente, no como una cadena vacía. */
    private static String limpiar(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return texto.strip();
    }

    private static String describirSolape(SolicitudReglaIva s) {
        String alcance;
        if (s.productoId() != null) {
            alcance = "la regla del producto " + s.productoId();
        } else if (s.categoriaId() != null) {
            alcance = "la regla de la categoría " + s.categoriaId();
        } else {
            alcance = "la regla general";
        }
        return "Ya hay " + alcance + " con una vigencia que se solapa. Dos reglas del mismo "
                + "alcance a la vez no dan error, pero solo manda una, y corregir la otra no "
                + "cambiaría ningún impuesto. Cierra la que está antes de crear esta.";
    }
}
