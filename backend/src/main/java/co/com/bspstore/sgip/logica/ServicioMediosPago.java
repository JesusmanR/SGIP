package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.SolicitudMedioPago;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioMediosPago;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Los medios de pago de la empresa (RF-43, M17).
 *
 * La empresa sale del contexto y no de un parámetro (RN-16). El medio de sistema
 * «Saldo a favor» lo gestiona el sistema: nace con la empresa, inactivo, y ni se
 * crea ni se activa ni se desactiva a mano. Lo usa el pago con saldo del cliente.
 */
@Service
public class ServicioMediosPago {

    /**
     * Con lo que nace toda empresa: los tres medios del mostrador, activos, y el
     * de sistema, inactivo. Es la misma lista que la V40 siembra a las empresas
     * que ya existían.
     */
    public static final List<MedioDePago> MEDIOS_DEL_ALTA = List.of(
            new MedioDePago(null, "Efectivo", TipoMedio.EFECTIVO, true),
            new MedioDePago(null, "Tarjeta", TipoMedio.TARJETA, true),
            new MedioDePago(null, "Transferencia", TipoMedio.TRANSFERENCIA, true),
            new MedioDePago(null, "Saldo a favor", TipoMedio.SALDO_A_FAVOR, false));

    private static final String DE_SISTEMA =
            "«Saldo a favor» lo gestiona el sistema: no se crea, no se activa y no se desactiva a mano.";

    private final RepositorioMediosPago medios;

    public ServicioMediosPago(RepositorioMediosPago medios) {
        this.medios = medios;
    }

    /** Los que el cajero puede elegir. El medio de sistema nunca está entre ellos. */
    @Transactional(readOnly = true)
    public List<MedioDePago> activos() {
        return medios.listarActivos(ContextoEmpresa.empresaActual());
    }

    /** Todos, el de sistema incluido (pantalla de administración). */
    @Transactional(readOnly = true)
    public List<MedioDePago> todos() {
        return medios.listar(ContextoEmpresa.empresaActual());
    }

    @Transactional
    public MedioDePago crear(SolicitudMedioPago solicitud) {
        if (solicitud.tipo().esDeSistema()) {
            throw new MedioDePagoInvalidoException(DE_SISTEMA);
        }
        Integer empresa = ContextoEmpresa.empresaActual();
        String nombre = solicitud.nombre().trim();
        if (medios.existeNombre(empresa, nombre)) {
            throw new NombreRepetidoException("Ya existe un medio de pago con ese nombre.");
        }
        Integer id = medios.insertar(empresa, nombre, solicitud.tipo());
        return medios.buscar(empresa, id).orElseThrow(() -> new IllegalStateException(
                "El medio de pago " + id + " no se encontró justo después de crearlo."));
    }

    /**
     * Activa o desactiva un medio.
     *
     * Los medios de la empresa se leen con bloqueo y de ahí salen las tres
     * decisiones: si el medio es de esta empresa (si no, 404, igual que si no
     * existiera), si es el de sistema (400) y si es el último activo (409). El
     * bloqueo evita que dos administradores desactiven a la vez los dos últimos.
     * Pedir el estado en que el medio ya está no escribe nada.
     */
    @Transactional
    public MedioDePago cambiarActivo(Integer id, boolean activo) {
        Integer empresa = ContextoEmpresa.empresaActual();
        List<MedioDePago> deLaEmpresa = medios.bloquearDeLaEmpresa(empresa);
        MedioDePago medio = deLaEmpresa.stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("El medio de pago no existe."));
        if (medio.tipo().esDeSistema()) {
            throw new MedioDePagoInvalidoException(DE_SISTEMA);
        }
        if (medio.activo() == activo) {
            return medio;
        }
        if (!activo && deLaEmpresa.stream()
                .noneMatch(m -> m.activo() && !m.tipo().esDeSistema() && !m.id().equals(id))) {
            throw new UltimoMedioActivoException(
                    "La empresa necesita al menos un medio de pago activo para poder cobrar.");
        }
        medios.actualizarActivo(empresa, id, activo);
        return medios.buscar(empresa, id).orElseThrow(() -> new IllegalStateException(
                "El medio de pago " + id + " desapareció dentro de su propia transacción."));
    }
}
