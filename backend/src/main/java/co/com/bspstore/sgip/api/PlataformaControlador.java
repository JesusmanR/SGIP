package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.EmpresaEnPlataforma;
import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.api.dto.SolicitudCambioEstado;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * El ciclo de vida de las empresas, para el super administrador
 * (RF-01 a RF-03).
 *
 * La anotación de rol está en la clase y no en cada método. En los demás
 * controladores va método a método porque conviven operaciones de
 * vendedor y de administrador; aquí no hay ninguna que alguien distinto
 * del super administrador deba poder hacer, de modo que ponerla arriba
 * cierra también los métodos que alguien añada mañana sin acordarse.
 *
 * Lo que este controlador no expone, y es la otra mitad de la RN-17:
 * nada de los datos operativos de las empresas. No hay forma de llegar
 * desde aquí al catálogo, al inventario ni a las ventas de un comercio,
 * y los servicios que los sirven piden la empresa al contexto de la
 * sesión, que para un usuario de plataforma está vacío y rechaza con
 * 403. El super administrador administra comercios; no entra en ellos.
 */
@RestController
@RequestMapping("/api/v1/plataforma/empresas")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlataformaControlador {

    private final ServicioPlataforma servicio;

    public PlataformaControlador(ServicioPlataforma servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<EmpresaEnPlataforma> listar() {
        return servicio.listar();
    }

    @GetMapping("/{empresaId}")
    public ResponseEntity<EmpresaEnPlataforma> ver(@PathVariable Integer empresaId) {
        return servicio.buscar(empresaId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** RF-01. Devuelve 409 si la identificación tributaria ya está registrada. */
    @PostMapping
    public ResponseEntity<EmpresaEnPlataforma> darDeAlta(
            @Valid @RequestBody SolicitudAltaEmpresa solicitud) {
        EmpresaEnPlataforma creada = servicio.darDeAlta(solicitud);
        return ResponseEntity
                .created(URI.create("/api/v1/plataforma/empresas/" + creada.empresaId()))
                .body(creada);
    }

    /**
     * RF-02 y RF-03. Son POST sobre un recurso y no un PUT del estado,
     * por lo mismo que el desbloqueo de usuarios y la desactivación de
     * categorías: son decisiones con nombre, no la escritura de un campo.
     * Un PUT invitaría además a mandar el estado como texto libre, y
     * «CONSULTA» —el nombre que el informe le da al tercer estado— no es
     * el que el esquema conoce.
     *
     * El motivo es opcional en la activación y conviene en la
     * desactivación: es lo que alguien va a leer en la bitácora dentro de
     * seis meses cuando pregunte por qué este comercio dejó de escribir.
     */
    @PostMapping("/{empresaId}/activar")
    public ResponseEntity<EmpresaEnPlataforma> activar(
            @PathVariable Integer empresaId,
            @Valid @RequestBody(required = false) SolicitudCambioEstado solicitud) {
        return servicio.activar(empresaId, SolicitudCambioEstado.motivoDe(solicitud))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{empresaId}/desactivar")
    public ResponseEntity<EmpresaEnPlataforma> desactivar(
            @PathVariable Integer empresaId,
            @Valid @RequestBody(required = false) SolicitudCambioEstado solicitud) {
        return servicio.desactivar(empresaId, SolicitudCambioEstado.motivoDe(solicitud))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
