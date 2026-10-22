package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CruceDeUmbral;
import co.com.bspstore.sgip.api.dto.ReglaDeMargen;
import co.com.bspstore.sgip.api.dto.SolicitudReglaMargen;
import co.com.bspstore.sgip.logica.ServicioPrecios;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Las reglas de ganancia de la empresa (RF-30, RF-32).
 *
 * Es del administrador: una regla de margen fija el precio de todo lo
 * que alcanza, de modo que es de las pantallas que más cuesta deshacer
 * si alguien se equivoca.
 *
 * No hay ningún endpoint que escriba un precio, y no es un olvido: el
 * RF-31 pide que «el precio de venta no admita captura manual en
 * ninguna interfaz». El precio sale de `v_precios_producto`, que aplica
 * la regla vigente al costo en cada lectura. Se cambia el costo o se
 * cambia la regla; el precio es consecuencia.
 */
@RestController
@RequestMapping("/api/v1/precios/reglas")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class PreciosControlador {

    private final ServicioPrecios servicio;

    public PreciosControlador(ServicioPrecios servicio) {
        this.servicio = servicio;
    }

    /**
     * Las reglas, ordenadas como el motor las elige: primero la forma de
     * pago, después de más específica a más general. Así la pantalla las
     * muestra en el orden en que compiten.
     */
    @GetMapping
    public List<ReglaDeMargen> listar() {
        return servicio.listar();
    }

    @GetMapping("/{reglaId}")
    public ResponseEntity<ReglaDeMargen> ver(@PathVariable Integer reglaId) {
        return servicio.buscar(reglaId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Cuántas referencias alcanzaría una regla de este ámbito, para
     * poder decirlo antes de guardar. Un cambio de margen se hace en un
     * segundo y se nota en la factura de cada cliente.
     */
    @GetMapping("/alcance")
    public Alcance alcance(@RequestParam(required = false) Integer categoria,
                           @RequestParam(required = false) Integer proveedor) {
        return new Alcance(categoria, proveedor, servicio.alcance(categoria, proveedor));
    }

    /**
     * Las referencias que cambiarían de tratamiento tributario si se
     * aplicara esta regla (RF-35).
     *
     * Es un POST aunque no escriba nada: lo que se consulta es el efecto
     * de una regla que todavía no existe, de modo que hay que mandarla
     * entera en el cuerpo. Un GET con la regla desarmada en parámetros
     * diría lo mismo peor.
     *
     * Sin cruces devuelve una lista vacía, no un error.
     */
    @PostMapping("/simulacion")
    public List<CruceDeUmbral> simular(@Valid @RequestBody SolicitudReglaMargen solicitud) {
        return servicio.simular(solicitud);
    }

    /** 409 si ya hay una regla del mismo alcance con vigencia solapada. */
    @PostMapping
    public ResponseEntity<ReglaDeMargen> crear(
            @Valid @RequestBody SolicitudReglaMargen solicitud) {
        ReglaDeMargen creada = servicio.crear(solicitud);
        return ResponseEntity
                .created(URI.create("/api/v1/precios/reglas/" + creada.reglaId()))
                .body(creada);
    }

    /**
     * Corrige la fórmula, el factor y la vigencia. El alcance no se
     * puede cambiar: eso sería otra regla, y la que había dejaría de
     * tener historia.
     */
    @PutMapping("/{reglaId}")
    public ResponseEntity<ReglaDeMargen> editar(
            @PathVariable Integer reglaId,
            @Valid @RequestBody SolicitudReglaMargen solicitud) {
        return servicio.actualizar(reglaId, solicitud)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Cierra la vigencia, que no es borrar: una venta de marzo se
     * explica con la regla que regía en marzo. Sin fecha, cierra ayer,
     * de modo que la regla deja de aplicar hoy mismo.
     */
    @PostMapping("/{reglaId}/cerrar")
    public ResponseEntity<ReglaDeMargen> cerrar(
            @PathVariable Integer reglaId,
            @RequestParam(required = false) LocalDate hasta) {
        return servicio.cerrar(reglaId, hasta)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Las referencias que una regla de este ámbito alcanzaría. */
    public record Alcance(Integer categoriaId, Integer proveedorId, int productos) {}
}
