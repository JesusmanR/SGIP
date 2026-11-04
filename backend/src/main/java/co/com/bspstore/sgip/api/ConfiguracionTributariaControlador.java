package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ConfiguracionTributaria;
import co.com.bspstore.sgip.api.dto.ReglaDeIva;
import co.com.bspstore.sgip.api.dto.SolicitudConfiguracionTributaria;
import co.com.bspstore.sgip.api.dto.SolicitudReglaIva;
import co.com.bspstore.sgip.api.dto.TipoImpuestoNacional;
import co.com.bspstore.sgip.logica.ServicioConfiguracionTributaria;
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
 * Lo que la empresa decide sobre sus impuestos (ADR-0016, ADR-0024).
 *
 * Es del administrador de la empresa. La empresa sale de la sesión y nunca
 * de la ruta, de modo que no hay forma de configurar la de otro; y el super
 * administrador recibe 403 porque la plataforma no opera dentro de una
 * empresa (ADR-0020). Aquí no hay ninguna operación que otro rol deba poder
 * hacer, así que la anotación va en la clase y cierra también los métodos
 * que alguien añada mañana.
 *
 * No hay {@code PUT} ni {@code DELETE} de reglas, y no es un olvido: una
 * venta se explica con la regla que regía el día que se hizo, igual que en
 * {@link PreciosControlador}. Una regla se crea y se cierra.
 *
 * Nada de esto es requisito para vender. Una empresa que no configura
 * impuestos vende al precio, sin impuesto (ADR-0024).
 */
@RestController
@RequestMapping("/api/v1/empresa/tributacion")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ConfiguracionTributariaControlador {

    private final ServicioConfiguracionTributaria servicio;

    public ConfiguracionTributariaControlador(ServicioConfiguracionTributaria servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public ConfiguracionTributaria configuracion() {
        return servicio.configuracion();
    }

    /**
     * Fija la configuración entera y la deja completa. Es un PUT: guardar es
     * decir «así queda», y mandarlo dos veces deja el mismo estado.
     */
    @PutMapping
    public ConfiguracionTributaria guardar(
            @Valid @RequestBody SolicitudConfiguracionTributaria solicitud) {
        return servicio.guardar(solicitud);
    }

    /** El catálogo nacional que la plataforma mantiene, solo lectura. */
    @GetMapping("/impuestos")
    public List<TipoImpuestoNacional> impuestos() {
        return servicio.impuestos();
    }

    @GetMapping("/reglas")
    public List<ReglaDeIva> reglas() {
        return servicio.reglas();
    }

    /** 409 si ya hay una regla del mismo alcance con vigencia solapada. */
    @PostMapping("/reglas")
    public ResponseEntity<ReglaDeIva> crearRegla(
            @Valid @RequestBody SolicitudReglaIva solicitud) {
        ReglaDeIva creada = servicio.crearRegla(solicitud);
        return ResponseEntity
                .created(URI.create("/api/v1/empresa/tributacion/reglas/" + creada.reglaId()))
                .body(creada);
    }

    /**
     * Cierra la vigencia, que no es borrar. Sin fecha, cierra ayer, de modo
     * que la regla deja de aplicar hoy mismo. 404 si la regla es de otra
     * empresa o no existe.
     */
    @PostMapping("/reglas/{reglaId}/cerrar")
    public ResponseEntity<ReglaDeIva> cerrarRegla(
            @PathVariable Integer reglaId,
            @RequestParam(required = false) LocalDate hasta) {
        return servicio.cerrarRegla(reglaId, hasta)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
