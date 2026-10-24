package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.Panel;
import co.com.bspstore.sgip.logica.ServicioPanel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El panel de indicadores (P-02).
 *
 * Un solo GET. La especificación del mockup lo describe como «dar en una
 * sola vista el estado del inventario y las alertas que exigen
 * decisión», y una sola vista se sirve de una sola lectura: ver
 * {@link Panel} para por qué no son cinco endpoints.
 *
 * Solo el administrador de empresa, que es el actor que la
 * especificación nombra. No es una restricción de comodidad: el panel
 * enseña el capital inmovilizado de la empresa y el resultado de la
 * última carga de precios, que son datos de gestión y no de venta. Un
 * vendedor no los necesita para vender y un cliente no debe verlos en
 * absoluto (RN-17).
 */
@RestController
@RequestMapping("/api/v1/panel")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class PanelControlador {

    private final ServicioPanel servicio;

    public PanelControlador(ServicioPanel servicio) {
        this.servicio = servicio;
    }

    /**
     * Siempre 200, nunca 404.
     *
     * Una empresa recién instalada no tiene productos, ni cargas, ni
     * movimientos, y eso no es una ausencia de panel: es un panel en
     * cero, que es el primero de los tres estados que el mockup pide
     * diseñar. Devolver 404 obligaría a la pantalla a distinguir «no
     * hay datos» de «no hay panel», que es una distinción sin
     * diferencia.
     */
    @GetMapping
    public Panel ver() {
        return servicio.ver();
    }
}
