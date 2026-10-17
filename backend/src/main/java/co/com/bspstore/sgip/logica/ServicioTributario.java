package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudTipoImpuesto;
import co.com.bspstore.sgip.api.dto.SolicitudValorUvt;
import co.com.bspstore.sgip.api.dto.TipoImpuestoNacional;
import co.com.bspstore.sgip.api.dto.ValorUvt;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.datos.RepositorioTributario;
import co.com.bspstore.sgip.modelo.AccionPlataforma;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Los datos tributarios de alcance nacional (RF-05).
 *
 * «La modificación surte efecto en el cálculo de todas las empresas sin
 * recompilar ni desplegar.» Eso no lo consigue esta clase: lo consigue
 * el esquema, porque las vistas de precio multiplican por la tarifa y
 * llaman a {@code fn_valor_uvt(CURDATE())} en cada lectura. Está
 * comprobado y anotado en {@code database/README.md}: bajando la UVT de
 * 2026 de 52.374 a 48.000, los tres computadores de la demostración
 * pasan a gravados de contado sin tocar una línea de Java.
 *
 * Lo que aporta esta clase es la otra mitad del requisito —que el cambio
 * quede registrado con responsable y fecha— y las dos comprobaciones que
 * una restricción de la base rechazaría sin explicar por qué.
 */
@Service
public class ServicioTributario {

    private static final String TRATAMIENTO_CON_UMBRAL = "EXCLUIDO_HASTA_UMBRAL";

    private final RepositorioTributario tributario;
    private final RepositorioPlataforma plataforma;

    public ServicioTributario(RepositorioTributario tributario,
                              RepositorioPlataforma plataforma) {
        this.tributario = tributario;
        this.plataforma = plataforma;
    }

    @Transactional(readOnly = true)
    public List<ValorUvt> listarUvt() {
        return tributario.listarUvt();
    }

    @Transactional(readOnly = true)
    public List<TipoImpuestoNacional> listarImpuestos() {
        return tributario.listarImpuestos();
    }

    /**
     * Fija el valor de la UVT de un año, creándolo si no existía.
     *
     * Es una sola operación y no dos —alta y cambio— porque desde fuera
     * es la misma intención: «la UVT de 2027 vale esto». Lo que cambia es
     * lo que se anota en la bitácora, que distingue UVT_ALTA de
     * UVT_CAMBIO porque la primera no tiene valor anterior y la segunda
     * sí, y esa diferencia es la que permite leer el historial.
     *
     * El año no se valida aquí contra el rango 2006-2100: lo hace
     * {@code ck_uvt_anio}, y repetirlo en Java sería mantener el mismo
     * número en dos sitios. Lo que sí se repite es lo que la base
     * rechazaría sin decir el motivo.
     */
    @Transactional
    public ValorUvt fijarUvt(Integer anio, SolicitudValorUvt solicitud) {
        Optional<ValorUvt> anterior = tributario.buscarUvt(anio);
        String resolucion = texto(solicitud.resolucion());

        if (anterior.isEmpty()) {
            tributario.insertarUvt(anio, solicitud.valor(), resolucion);
            plataforma.registrar(responsable(), null, AccionPlataforma.UVT_ALTA,
                    "uvt_anual", String.valueOf(anio),
                    null, solicitud.valor().toPlainString(), texto(solicitud.motivo()));
        } else {
            tributario.actualizarUvt(anio, solicitud.valor(), resolucion);
            plataforma.registrar(responsable(), null, AccionPlataforma.UVT_CAMBIO,
                    "uvt_anual", String.valueOf(anio),
                    anterior.get().valor().toPlainString(),
                    solicitud.valor().toPlainString(), texto(solicitud.motivo()));
        }
        return tributario.buscarUvt(anio).orElseThrow(() -> new IllegalStateException(
                "La UVT de " + anio + " no se encontró justo después de fijarla."));
    }

    /**
     * Cambia la tarifa, el umbral y el fundamento de un tipo de impuesto.
     *
     * Vacío si el código no existe, para que el controlador responda 404.
     * No crea tipos nuevos: dar de alta una clase de impuesto es decidir
     * que otra norma aplica, y eso pide reapuntar las reglas que la usan,
     * que no es algo que deba pasar como efecto secundario de un PUT.
     */
    @Transactional
    public Optional<TipoImpuestoNacional> cambiarImpuesto(String codigo,
                                                          SolicitudTipoImpuesto solicitud) {
        Optional<TipoImpuestoNacional> anterior = tributario.buscarImpuesto(codigo);
        if (anterior.isEmpty()) {
            return Optional.empty();
        }
        TipoImpuestoNacional actual = anterior.get();

        // La restricción ck_tipos_impuesto_umbral exige umbral en los
        // tipos de umbral. Sin esta comprobación el rechazo llegaría como
        // un error de integridad, que no le dice a nadie qué falta.
        if (TRATAMIENTO_CON_UMBRAL.equals(actual.tratamiento()) && solicitud.umbralUvt() == null) {
            throw new DatoTributarioInvalidoException(
                    "El tipo " + codigo + " excluye del impuesto hasta un umbral, "
                            + "de modo que el umbral en UVT es obligatorio.");
        }
        // Y al contrario: un umbral en un tipo que no lo usa es un dato
        // que nadie va a leer y que hace creer que hay un tope donde no lo
        // hay. Se rechaza en lugar de guardarlo en silencio.
        if (!TRATAMIENTO_CON_UMBRAL.equals(actual.tratamiento()) && solicitud.umbralUvt() != null) {
            throw new DatoTributarioInvalidoException(
                    "El tipo " + codigo + " tiene tratamiento " + actual.tratamiento()
                            + ", que no usa umbral. Deja el umbral vacío.");
        }

        tributario.actualizarImpuesto(codigo, solicitud.tarifa(), solicitud.umbralUvt(),
                texto(solicitud.fundamento()));

        plataforma.registrar(responsable(), null, AccionPlataforma.IMPUESTO_CAMBIO,
                "tipos_impuesto", codigo,
                descripcion(actual.tarifa().toPlainString(), actual.umbralUvt()),
                descripcion(solicitud.tarifa().toPlainString(), solicitud.umbralUvt()),
                texto(solicitud.motivo()));

        return tributario.buscarImpuesto(codigo);
    }

    /**
     * Lo que se guarda como valor anterior y nuevo en la bitácora.
     *
     * Las dos cifras van juntas en una cadena porque las dos cambian a la
     * vez y se leen juntas: «0,1900 hasta 50 UVT» dice lo que regía mejor
     * que dos filas separadas, y el requisito pide registrar la
     * modificación, no cada columna.
     */
    private static String descripcion(String tarifa, java.math.BigDecimal umbral) {
        return umbral == null
                ? "tarifa " + tarifa
                : "tarifa " + tarifa + " hasta " + umbral.toPlainString() + " UVT";
    }

    private Integer responsable() {
        return ContextoEmpresa.actual()
                .map(UsuarioAutenticado::usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Operación de plataforma sin usuario en el contexto. "
                                + "Revise la cadena de seguridad."));
    }

    private static String texto(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        return limpio.isEmpty() ? null : limpio;
    }
}
