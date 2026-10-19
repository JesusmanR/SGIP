package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.ReglaDeMargen;
import co.com.bspstore.sgip.api.dto.SolicitudReglaMargen;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioPrecios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Las reglas de ganancia (RF-30, RF-32).
 *
 * Esta clase no calcula ni un precio. El cálculo vive en
 * `v_precios_producto`, que aplica la fórmula que cada regla declara, y
 * eso no es un detalle de implementación: es lo que hace cierto el
 * criterio del RF-30 —«la modificación surte efecto en el cálculo
 * siguiente sin desplegar»— y lo que impide que el RF-31 se incumpla por
 * descuido, porque no hay ningún sitio donde un precio se pueda escribir
 * a mano.
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

    private final RepositorioPrecios precios;
    private final Clock reloj;

    public ServicioPrecios(RepositorioPrecios precios, Clock reloj) {
        this.precios = precios;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public List<ReglaDeMargen> listar() {
        return precios.listar(ContextoEmpresa.empresaActual());
    }

    @Transactional(readOnly = true)
    public Optional<ReglaDeMargen> buscar(Integer reglaId) {
        return precios.buscar(ContextoEmpresa.empresaActual(), reglaId);
    }

    /**
     * Cuántas referencias del catálogo alcanzaría una regla de este
     * ámbito, para que la pantalla lo pueda decir antes de guardar.
     */
    @Transactional(readOnly = true)
    public int alcance(Integer categoriaId, Integer proveedorId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        exigirAmbitoPropio(empresa, categoriaId, proveedorId);
        return precios.productosAlcanzados(empresa, categoriaId, proveedorId);
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

    @Transactional
    public ReglaDeMargen crear(SolicitudReglaMargen solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
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
        return precios.buscar(empresa, id).orElseThrow(() -> new IllegalStateException(
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
        Optional<ReglaDeMargen> actual = precios.buscar(empresa, reglaId);
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
        return precios.buscar(empresa, reglaId);
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
        Optional<ReglaDeMargen> actual = precios.buscar(empresa, reglaId);
        if (actual.isEmpty()) {
            return Optional.empty();
        }
        LocalDate fin = hasta != null ? hasta : LocalDate.now(reloj).minusDays(1);
        if (fin.isBefore(actual.get().vigenteDesde())) {
            throw new ReglaInvalidaException(
                    "La regla empezó a regir el " + actual.get().vigenteDesde()
                            + " y no puede cerrarse antes de esa fecha.");
        }
        precios.cerrar(empresa, reglaId, fin);
        return precios.buscar(empresa, reglaId);
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
