package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.SolicitudCliente;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioClientes;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

/**
 * Los clientes de la caja (RF-66): buscar por documento, crear y editar.
 *
 * Los usan el administrador y el vendedor; el cliente y el super administrador
 * reciben 403. No hay ruta para leer por identificador ni para borrar: la caja
 * llega al cliente por su documento.
 */
@RestController
@RequestMapping("/api/v1/caja/clientes")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class ClientesControlador {

    private final ServicioClientes servicio;

    public ClientesControlador(ServicioClientes servicio) {
        this.servicio = servicio;
    }

    /** 404 si la empresa no tiene un cliente con ese documento. */
    @GetMapping
    public Cliente porIdentificacion(@RequestParam TipoDocumento tipo,
                                     @RequestParam String identificacion) {
        return servicio.porIdentificacion(tipo, identificacion)
                .orElseThrow(() -> new RecursoNoEncontradoException("El cliente no existe."));
    }

    /** Sin cabecera {@code Location}: no existe una ruta para leer un cliente por su identificador. */
    @PostMapping
    public ResponseEntity<Cliente> crear(@Valid @RequestBody SolicitudCliente solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crear(solicitud));
    }

    @PutMapping("/{id}")
    public Cliente actualizar(@PathVariable Integer id, @Valid @RequestBody SolicitudCliente solicitud) {
        return servicio.actualizar(id, solicitud);
    }
}
