package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.api.dto.AlertaUvt;
import co.com.bspstore.sgip.logica.ServicioTributario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Anota en el log, al arrancar, si falta la UVT del año que viene.
 *
 * Existe porque todavía no hay una pantalla de plataforma donde mostrar la
 * alerta; el endpoint {@code GET /api/v1/plataforma/tributario/alerta-uvt}
 * ya la expone para cuando la haya.
 *
 * Lee {@code uvt_anual}, que es un catálogo nacional sin {@code empresa_id}:
 * no necesita empresa en {@link ContextoEmpresa} y corre sin sesión.
 *
 * Nunca impide arrancar. Un aviso que tumbara la aplicación sería peor que
 * el problema que avisa, así que cualquier fallo de la consulta —el esquema
 * aún no listo, la base caída— se anota y el arranque sigue.
 */
@Component
public class AvisoUvtAlArrancar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AvisoUvtAlArrancar.class);

    private final ServicioTributario tributario;

    public AvisoUvtAlArrancar(ServicioTributario tributario) {
        this.tributario = tributario;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            AlertaUvt alerta = tributario.alertaUvt();
            if (alerta.activa()) {
                log.warn("Falta la UVT de {} (faltan {} días). Nadie deja de vender por esto: "
                                + "solo la empresa responsable de IVA con la exclusión por umbral "
                                + "activada paga la tarifa en lugar de quedar excluida. "
                                + "Cárguela con PUT /api/v1/plataforma/tributario/uvt/{}.",
                        alerta.anio(), alerta.diasRestantes(), alerta.anio());
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo comprobar si falta la UVT del año que viene; "
                    + "el arranque continúa: {}", e.toString());
        }
    }
}
