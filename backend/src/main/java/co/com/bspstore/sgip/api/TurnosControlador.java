package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudApertura;
import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El turno de caja (RF-45): abrirlo y consultar el del usuario en sesión.
 *
 * Los usan el administrador y el vendedor; el cliente y el super administrador reciben
 * 403. El turno siempre es el de quien hace la petición: ninguna ruta recibe el cajero.
 * El cierre llega con la tarea 10.
 */
@RestController
@RequestMapping("/api/v1/caja/turnos")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class TurnosControlador {

    private final ServicioTurnos servicio;

    public TurnosControlador(ServicioTurnos servicio) {
        this.servicio = servicio;
    }

    /** 409 si el cajero ya tiene un turno abierto. Sin {@code Location}: el turno se lee en {@code /actual}. */
    @PostMapping
    public ResponseEntity<Turno> abrir(@Valid @RequestBody SolicitudApertura solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.abrir(solicitud));
    }

    /** 204 si el usuario no tiene un turno abierto: no tenerlo no es un error. */
    @GetMapping("/actual")
    public ResponseEntity<Turno> actual() {
        return servicio.actual()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
