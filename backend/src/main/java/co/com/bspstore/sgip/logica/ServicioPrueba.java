package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * La prueba gratuita de la empresa de quien pregunta (RF-73).
 *
 * El tiempo restante sale del reloj del servidor y no del equipo del usuario:
 * adelantar la hora del equipo no cambia el contador. La regla de vigencia es la
 * misma que aplica {@code FiltroEmpresaActiva}, porque las dos preguntan a
 * {@link VigenciaDePrueba}.
 */
@Service
public class ServicioPrueba {

    private final RepositorioPlataforma plataforma;
    private final Clock reloj;

    public ServicioPrueba(RepositorioPlataforma plataforma, Clock reloj) {
        this.plataforma = plataforma;
        this.reloj = reloj;
    }

    /**
     * {@code fin_prueba} solo se lee cuando la empresa está en PRUEBA: en los
     * otros dos estados no hay nada que contar. Una empresa de la sesión que ya
     * no existe es un 404, como en el resto de la API.
     */
    @Transactional(readOnly = true)
    public SituacionDePrueba situacion() {
        Integer empresa = ContextoEmpresa.empresaActual();
        EstadoEmpresa estado = plataforma.estadoDe(empresa)
                .orElseThrow(() -> new RecursoNoEncontradoException("La empresa no existe."));
        LocalDate fin = estado == EstadoEmpresa.PRUEBA
                ? plataforma.finDePruebaDe(empresa).orElse(null)
                : null;
        return VigenciaDePrueba.situacion(estado, fin, reloj.instant());
    }
}
