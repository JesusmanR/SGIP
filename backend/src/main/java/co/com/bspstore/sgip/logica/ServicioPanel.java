package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.Panel;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioPanel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * El panel de indicadores (P-02): el estado del inventario y las
 * alertas que exigen decisión, en una sola vista.
 *
 * Cubre la parte de lectura de cuatro requisitos y no los agota, y
 * conviene no confundirlo:
 *
 * <ul>
 *   <li>RF-57 pide un reporte de existencias valorizadas con filtro por
 *       categoría y menos de cinco segundos sobre diez mil referencias.
 *       El panel enseña su total, no el reporte; el reporte es P-12.</li>
 *   <li>RF-50 pide notificar los productos que bajan del umbral, y su
 *       criterio exige histéresis: «un producto ya por debajo no vuelve
 *       a notificar hasta que suba y baje de nuevo». Eso es un
 *       mecanismo con memoria. El panel muestra el estado actual, que
 *       es otra cosa: no notifica, se consulta.</li>
 *   <li>RF-49 ya está cumplido en el kárdex. El panel solo lo agrega
 *       por día.</li>
 *   <li>RF-35 ya está cumplido en la importación, antes de aplicar. El
 *       panel añade la mirada de después.</li>
 * </ul>
 *
 * ## Por qué una transacción de solo lectura
 *
 * Son cinco consultas que juntas forman una sola afirmación sobre el
 * inventario. Sin transacción, cada una se confirma sola y pueden caer a
 * los dos lados de un movimiento: el conteo de agotadas vendría de antes
 * y la tabla de críticas de después, y la pantalla mostraría doce
 * agotadas y una tabla con trece. Nada habría fallado y el panel estaría
 * mintiendo.
 *
 * Con una transacción de solo lectura, MySQL en REPEATABLE READ —que es
 * su nivel por omisión— sirve las cinco desde la misma instantánea. El
 * `readOnly` además le dice al motor que no hay nada que registrar para
 * deshacer.
 */
@Service
public class ServicioPanel {

    /**
     * Cuántas referencias críticas trae la tabla corta.
     *
     * El mockup dibuja tres y un «Ver todas». Van cinco: tres es el
     * dibujo de un mockup con sitio para tres, y la diferencia entre
     * llamar a un proveedor y a tres es lo que esta tabla decide. El
     * enlace sigue estando para el resto, de modo que el tope no esconde
     * nada; solo decide cuánto se ve sin navegar.
     */
    private static final int FILAS_CRITICAS = 5;

    private final RepositorioPanel repositorio;

    public ServicioPanel(RepositorioPanel repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional(readOnly = true)
    public Panel ver() {
        Integer empresaId = ContextoEmpresa.empresaActual();

        Optional<Panel.CargaEnPanel> ultima = repositorio.ultimaCarga(empresaId);

        // Los cruces solo tienen sentido sobre una carga aplicada. Una
        // carga validada y aún sin decidir no ha movido ningún costo, de
        // modo que no ha cambiado el tratamiento de nada: lo que esa
        // carga tiene son cruces *hipotéticos*, y esos los enseña la
        // pantalla de importación antes de aplicar (P-07). Pedirlos aquí
        // devolvería una lista vacía, que es correcta pero por el motivo
        // equivocado: parecería que no hay cruces cuando lo que pasa es
        // que todavía no se ha decidido.
        List<CruceDeUmbral> cruces = ultima
                .filter(carga -> "APLICADA".equals(carga.estado()))
                .map(carga -> repositorio.cruzaronEnLaCarga(empresaId, carga.cargaId()))
                .orElseGet(List::of);

        return new Panel(
                repositorio.cifras(empresaId),
                repositorio.criticas(empresaId, FILAS_CRITICAS),
                ultima.orElse(null),
                cruces,
                repositorio.movimientosPorDia(empresaId));
    }
}
