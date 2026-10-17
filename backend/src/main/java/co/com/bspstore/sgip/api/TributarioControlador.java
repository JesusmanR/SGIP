package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudTipoImpuesto;
import co.com.bspstore.sgip.api.dto.SolicitudValorUvt;
import co.com.bspstore.sgip.api.dto.TipoImpuestoNacional;
import co.com.bspstore.sgip.api.dto.ValorUvt;
import co.com.bspstore.sgip.logica.ServicioTributario;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Los datos tributarios de alcance nacional (RF-05).
 *
 * «Un administrador de empresa que intente modificarlos recibe 403.» Eso
 * lo da la anotación de la clase, puesta arriba por lo mismo que en
 * {@link PlataformaControlador}: aquí no hay ninguna operación que otro
 * rol deba poder hacer, de modo que cierra también los métodos que
 * alguien añada mañana.
 *
 * Son PUT y no POST, al contrario que activar o desactivar una empresa.
 * La diferencia no es de gusto: aquello eran decisiones con nombre
 * —«actívala»— y esto es fijar el valor de algo que ya tiene uno. Un PUT
 * sobre {@code /uvt/2027} dice exactamente lo que hace y además es
 * idempotente, que es lo que se quiere de una cifra oficial: mandarla
 * dos veces deja el mismo estado.
 *
 * La lectura también exige super administrador, y conviene justificarlo
 * porque es tentador abrirla: la tarifa y el umbral vigentes ya le
 * llegan a cada empresa en su catálogo, a través de
 * {@code v_precios_producto}, que es donde le sirven. Lo que esta
 * pantalla muestra es el catálogo nacional completo con su historial de
 * años, y eso es material de la plataforma.
 */
@RestController
@RequestMapping("/api/v1/plataforma/tributario")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TributarioControlador {

    private final ServicioTributario servicio;

    public TributarioControlador(ServicioTributario servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/uvt")
    public List<ValorUvt> listarUvt() {
        return servicio.listarUvt();
    }

    /**
     * Fija el valor de la UVT de un año. Crea el año si no existía y lo
     * actualiza si existía, y en los dos casos responde 200 con lo que
     * quedó: desde fuera es la misma intención, y la bitácora es la que
     * distingue el alta del cambio.
     */
    @PutMapping("/uvt/{anio}")
    public ValorUvt fijarUvt(@PathVariable Integer anio,
                             @Valid @RequestBody SolicitudValorUvt solicitud) {
        return servicio.fijarUvt(anio, solicitud);
    }

    @GetMapping("/impuestos")
    public List<TipoImpuestoNacional> listarImpuestos() {
        return servicio.listarImpuestos();
    }

    /**
     * Cambia la tarifa, el umbral y el fundamento de un tipo de impuesto.
     * 404 si el código no existe: no se crean tipos por esta vía, porque
     * dar de alta una clase de impuesto obliga a reapuntar las reglas que
     * la usan y eso no debe pasar como efecto secundario de un PUT.
     */
    @PutMapping("/impuestos/{codigo}")
    public ResponseEntity<TipoImpuestoNacional> cambiarImpuesto(
            @PathVariable String codigo,
            @Valid @RequestBody SolicitudTipoImpuesto solicitud) {
        return servicio.cambiarImpuesto(codigo, solicitud)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
