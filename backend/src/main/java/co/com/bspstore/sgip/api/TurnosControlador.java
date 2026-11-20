package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ResumenDeTurno;
import co.com.bspstore.sgip.api.dto.SolicitudApertura;
import co.com.bspstore.sgip.api.dto.SolicitudCierre;
import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * El turno de caja (RF-45): abrirlo, consultar el del usuario en sesión, ver su resumen, cerrarlo con el
 * efectivo contado y listar los turnos.
 *
 * Los usan el administrador y el vendedor; el cliente y el super administrador reciben
 * 403. El turno siempre es el de quien hace la petición: ninguna ruta recibe el cajero. En la lista,
 * el administrador ve los turnos de toda la empresa y el vendedor solo los suyos, y quien filtra es
 * el servidor. Una empresa en consulta ve el resumen y la lista, y no abre ni cierra
 * ({@code FiltroEmpresaActiva}).
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

    /** 404 si el usuario no tiene un turno abierto. El esperado es el de este momento; no cierra nada. */
    @GetMapping("/actual/resumen")
    public ResumenDeTurno resumen() {
        return servicio.resumenActual();
    }

    /**
     * 200 con el resumen del turno ya cerrado, no 201: no se crea nada nuevo. 400 si el contado no son
     * pesos enteros o si hay diferencia y no se explica; 409 si no hay turno abierto, que es también lo que
     * recibe quien cierra por segunda vez.
     */
    @PostMapping("/actual/cierre")
    public ResumenDeTurno cerrar(@Valid @RequestBody SolicitudCierre solicitud) {
        return servicio.cerrar(solicitud);
    }

    /** Los turnos abiertos entre {@code desde} y {@code hasta}, ambos aaaa-mm-dd y obligatorios. */
    @GetMapping
    public List<ResumenDeTurno> listar(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return servicio.listar(desde, hasta);
    }
}
