package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.AdministradorParaAutorizar;
import co.com.bspstore.sgip.api.dto.EstadoPin;
import co.com.bspstore.sgip.api.dto.SolicitudPin;
import co.com.bspstore.sgip.logica.ServicioAutorizacion;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * El PIN con el que el administrador autoriza en la caja (ADR-0032).
 *
 * El PIN es del administrador y lo fija él desde su perfil: las dos rutas de
 * {@code /perfil/pin} son solo suyas, y ninguna devuelve jamás el PIN. La lista de
 * administradores es del administrador y del vendedor, que elige a quién llamar.
 * El cliente y el super administrador reciben 403 en las tres: el anotado de la
 * clase lo exige, y el de cada ruta del perfil lo reemplaza por uno más estricto.
 *
 * Autorizar una operación no tiene ruta propia: el PIN viaja dentro de la petición
 * que confirma la operación y lo valida {@link ServicioAutorizacion} en ella.
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class PinControlador {

    private final ServicioAutorizacion servicio;

    public PinControlador(ServicioAutorizacion servicio) {
        this.servicio = servicio;
    }

    /** Fija el PIN (la primera vez) o lo cambia (con el actual). */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PutMapping("/perfil/pin")
    public ResponseEntity<Void> fijar(@Valid @RequestBody SolicitudPin solicitud) {
        servicio.fijarPin(solicitud.pinActual(), solicitud.pinNuevo());
        return ResponseEntity.noContent().build();
    }

    /** Si ya tiene PIN. Nunca el PIN. */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @GetMapping("/perfil/pin")
    public EstadoPin estado() {
        return servicio.estadoDelPin();
    }

    /** Los administradores de la empresa que pueden autorizar con su PIN. */
    @GetMapping("/caja/administradores")
    public List<AdministradorParaAutorizar> administradores() {
        return servicio.administradores();
    }
}
